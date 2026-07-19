package com.example.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Singleton wrapper around Tesseract4Android (`com.googlecode.tesseract.android.TessBaseAPI`).
 *
 * Why this exists (and why ML Kit is NOT used):
 *   - The Vault "Scan Screen" feature must recognize BOTH English AND Arabic text.
 *   - Google ML Kit Text Recognition v2 does NOT ship an Arabic model and silently
 *     falls back to Latin-only recognition — that exact behaviour was the root cause
 *     of a previous failed OCR attempt on this project.
 *   - Tesseract with the official `tessdata_fast` LSTM models supports `eng+ara`
 *     combined-language initialization out of the box.
 *
 * Resource lifecycle (CRITICAL):
 *   - Native Tesseract allocates several MB of native heap per TessBaseAPI instance.
 *   - We MUST call `tessBaseAPI.end()` (or `.recycle()`) after every recognition call
 *     to avoid leaking native memory across repeated scans.
 *   - The traineddata files are copied lazily from `assets/tessdata/` to the app's
 *     private files dir on first use, then re-used for subsequent scans. We verify
 *     byte length > 0 after every copy to defend against the previous failure mode
 *     where 0-byte placeholder traineddata files were bundled silently.
 */
object OcrManager {

    private const val TAG = "OcrManager"

    /** Combined language code passed to `TessBaseAPI.init`. */
    private const val OCR_LANG = "eng+ara"

    /** Subdirectory under the app's files dir where Tesseract expects traineddata. */
    private const val TESS_DATA_DIR_NAME = "tessdata"

    /**
     * Page Segmentation Mode.
     * `TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK` (= 6) assumes a single uniform block
     * of text — the correct assumption for a tightly-cropped region the user just
     * dragged a rectangle around. The default (`PSM_AUTO`, 3) assumes a full page
     * with complex layout and tends to mis-segment small screen crops.
     */
    private val OCR_PSM = TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK

    /** Cached absolute path of the prepared tessdata directory's PARENT, populated on first init. */
    @Volatile
    private var preparedTessDataDir: String? = null

    /** Mutex-style guard so concurrent scans don't double-copy traineddata. */
    private val initLock = Any()

    /**
     * Public entry point. Runs OCR on [bitmap] and returns the recognized text.
     */
    suspend fun recognize(bitmap: Bitmap): String = withContext(Dispatchers.IO) {
        Log.d(TAG, "recognize() called")
        
        // 1. Verify OCR is actually receiving a valid Bitmap.
        if (bitmap.isRecycled) {
            Log.e(TAG, "OCR received an already RECYCLED bitmap!")
            return@withContext ""
        }
        Log.d(TAG, "Incoming Bitmap properties: width=${bitmap.width}, height=${bitmap.height}, config=${bitmap.config}")

        if (bitmap.width < 4 || bitmap.height < 4) {
            Log.w(TAG, "Bitmap too small for OCR (${bitmap.width}x${bitmap.height}), skipping")
            return@withContext ""
        }

        // Preprocess: grayscale + adaptive contrast/binarization. Dramatically improves
        // Tesseract accuracy on anti-aliased screen-captured text vs. raw ARGB pixels.
        val preprocessed = preprocessForOcr(bitmap)
        Log.d(TAG, "Preprocessed Bitmap generated: width=${preprocessed.width}, height=${preprocessed.height}")

        // Each TessBaseAPI instance is short-lived: init -> setImage -> getUTF8Text -> end.
        var api: TessBaseAPI? = null
        try {
            val tessDataDir = ensureTessDataDir()
            
            // 2. Log the absolute tessdata path used by Tesseract.
            Log.d(TAG, "Absolute path passed to TessBaseAPI.init(): '$tessDataDir'")

            // 3. Verify eng.traineddata and ara.traineddata exist before initializing Tesseract.
            val tessdataFolderOnDisk = File(tessDataDir, TESS_DATA_DIR_NAME)
            val engFile = File(tessdataFolderOnDisk, "eng.traineddata")
            val araFile = File(tessdataFolderOnDisk, "ara.traineddata")

            Log.d(TAG, "Verifying traineddata files before init:")
            Log.d(TAG, "  - eng.traineddata exists: ${engFile.exists()}, size: ${engFile.length()} bytes")
            Log.d(TAG, "  - ara.traineddata exists: ${araFile.exists()}, size: ${araFile.length()} bytes")

            if (!engFile.exists() || engFile.length() == 0L || !araFile.exists() || araFile.length() == 0L) {
                Log.e(TAG, "CRITICAL ERROR: One or more traineddata files are missing or empty on disk!")
            }

            Log.d(TAG, "Instantiating TessBaseAPI...")
            api = TessBaseAPI()
            
            Log.d(TAG, "Calling TessBaseAPI.init()...")
            var ok = false
            try {
                ok = api.init(tessDataDir, OCR_LANG)
            } catch (t: Throwable) {
                Log.e(TAG, "Native exception occurred during TessBaseAPI.init()", t)
            }

            // 4. Log the result of TessBaseAPI.init()
            Log.d(TAG, "TessBaseAPI.init() returned: $ok")

            if (!ok) {
                // 5. If initialization fails, print the exact reason.
                val errorMessage = buildString {
                    append("TessBaseAPI.init() failed for lang='$OCR_LANG' in parent dir='$tessDataDir'.\n")
                    append("Disk State Diagnostics:\n")
                    append("  - filesDir path: ${appContext?.filesDir?.absolutePath}\n")
                    append("  - tessdata folder exists: ${tessdataFolderOnDisk.exists()}\n")
                    if (tessdataFolderOnDisk.exists()) {
                        val files = tessdataFolderOnDisk.listFiles()
                        append("  - Files count in tessdata: ${files?.size ?: 0}\n")
                        files?.forEach { f ->
                            append("    * ${f.name}: exists=${f.exists()}, size=${f.length()} bytes, readable=${f.canRead()}\n")
                        }
                    } else {
                        append("  - tessdata folder does NOT exist!\n")
                    }
                }
                Log.e(TAG, errorMessage)
                return@withContext ""
            }

            api.pageSegMode = OCR_PSM
            Log.d(TAG, "Setting original image into Tesseract first...")
            api.setImage(bitmap)
            
            Log.d(TAG, "Extracting UTF8 text from original image...")
            var text = api.utF8Text ?: ""
            
            // Log recognized text length
            Log.d(TAG, "Original image OCR output text length: ${text.length} chars. Raw preview: '${if (text.length > 50) text.take(50) + "..." else text}'")

            if (text.trim().isBlank()) {
                Log.d(TAG, "Original image returned empty text; falling back to binarized image")
                api.setImage(preprocessed)
                text = api.utF8Text ?: ""
                Log.d(TAG, "Binarized image OCR output text length: ${text.length} chars. Raw preview: '${if (text.length > 50) text.take(50) + "..." else text}'")
            }

            Log.d(TAG, "OCR done: input ${bitmap.width}x${bitmap.height}, final output text length: ${text.length} chars")
            text.trim()
        } catch (t: Throwable) {
            Log.e(TAG, "OCR processing failed with unexpected exception", t)
            ""
        } finally {
            // CRITICAL: release native Tesseract resources.
            try {
                api?.recycle()
                Log.d(TAG, "TessBaseAPI successfully recycled")
            } catch (t: Throwable) {
                Log.w(TAG, "TessBaseAPI.recycle() threw", t)
            }
            // Recycle the intermediate preprocessed bitmap
            try {
                if (preprocessed !== bitmap) {
                    preprocessed.recycle()
                    Log.d(TAG, "Preprocessed bitmap successfully recycled")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to recycle preprocessed bitmap", t)
            }
        }
    }

    /**
     * Copies `assets/tessdata/{eng,ara}.traineddata` to `filesDir/tessdata/` on first run,
     * then returns the absolute path of `filesDir/` (the PARENT of `tessdata/`, which is
     * what `TessBaseAPI.init()` expects as its first argument).
     */
    private fun ensureTessDataDir(): String {
        preparedTessDataDir?.let { return it }
        synchronized(initLock) {
            preparedTessDataDir?.let { return it }

            val context = appContext
                ?: throw IllegalStateException("OcrManager.appContext not set; call setApplicationContext() first")
            
            val outRoot = context.filesDir.absolutePath
            val tessDataDir = File(outRoot, TESS_DATA_DIR_NAME)
            Log.d(TAG, "ensureTessDataDir() start. outRoot: '$outRoot', tessDataDir: '${tessDataDir.absolutePath}'")

            // Log packaged assets list to confirm they are physically inside the APK
            try {
                val assetList = context.assets.list("tessdata")
                Log.d(TAG, "Files packaged inside APK assets/tessdata/: [${assetList?.joinToString(", ")}]")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to list assets/tessdata/", e)
            }

            if (!tessDataDir.exists()) {
                val created = tessDataDir.mkdirs()
                Log.d(TAG, "tessdata directory did not exist. Created: $created")
            }

            val requiredFiles = listOf("eng.traineddata", "ara.traineddata")
            for (name in requiredFiles) {
                val targetFile = File(tessDataDir, name)
                Log.d(TAG, "Checking file on disk: '${targetFile.absolutePath}' (Exists: ${targetFile.exists()}, Size: ${targetFile.length()} bytes)")
                
                if (!targetFile.exists() || targetFile.length() == 0L) {
                    Log.d(TAG, "Copying asset 'tessdata/$name' to destination...")
                    copyAssetToFile(context, "tessdata/$name", targetFile)
                }

                val lenAfter = targetFile.length()
                Log.d(TAG, "Post-copy validation for '$name': size = $lenAfter bytes")
                if (lenAfter == 0L) {
                    throw IOException("Traineddata file '$name' is 0 bytes after copy. " +
                            "Asset may be missing or corrupt in the APK.")
                }
            }

            preparedTessDataDir = outRoot
            return outRoot
        }
    }

    /** Streams an asset to a destination file. Throws on I/O failure. */
    private fun copyAssetToFile(context: Context, assetPath: String, destFile: File) {
        try {
            context.assets.open(assetPath).use { input ->
                val tempFile = File(destFile.absolutePath + ".tmp")
                FileOutputStream(tempFile).use { output ->
                    val copiedBytes = input.copyTo(output)
                    output.flush()
                    Log.d(TAG, "Copied $copiedBytes bytes from asset '$assetPath' to temp file '${tempFile.absolutePath}'")
                }
                if (tempFile.exists() && tempFile.length() > 0) {
                    if (destFile.exists()) {
                        destFile.delete()
                    }
                    val renamed = tempFile.renameTo(destFile)
                    Log.d(TAG, "Renamed temp file to final destination '${destFile.absolutePath}': $renamed (Size: ${destFile.length()} bytes)")
                } else {
                    throw IOException("Temp file for '$assetPath' is empty or missing!")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy asset '$assetPath' to '${destFile.absolutePath}'", e)
            throw IOException("Failed to copy asset '$assetPath' to '${destFile.absolutePath}'", e)
        }
    }

    /**
     * Lightweight image preprocessing — grayscale + adaptive threshold binarization —
     * implemented with `android.graphics.Bitmap` only (no OpenCV dependency).
     */
    private fun preprocessForOcr(source: Bitmap): Bitmap {
        val w = source.width
        val h = source.height
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)

        var sum = 0L
        val gray = IntArray(w * h)
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = Color.red(c)
            val g = Color.green(c)
            val b = Color.blue(c)
            val lum = (r * 299 + g * 587 + b * 114) / 1000
            gray[i] = lum
            sum += lum
        }
        val mean = (sum / gray.size).toInt()

        for (i in gray.indices) {
            pixels[i] = if (gray[i] > mean) Color.WHITE else Color.BLACK
        }

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }

    // ------------------------------------------------------------------
    // Application context plumbing
    // ------------------------------------------------------------------

    @Volatile
    private var appContext: Context? = null

    /**
     * MUST be called once from [com.example.OrbitApp.onCreate].
     * Stores the application context and triggers proactive copy on launch.
     */
    fun setApplicationContext(context: Context) {
        appContext = context.applicationContext
        
        // Proactively copy assets on launch asynchronously to verify copied correctly on first launch
        java.lang.Thread {
            try {
                Log.d(TAG, "Proactive background tessdata folder copy/verify started on application launch")
                val path = ensureTessDataDir()
                Log.d(TAG, "Proactive background tessdata folder copy/verify finished successfully. Path: $path")
            } catch (e: Exception) {
                Log.e(TAG, "Proactive background tessdata folder copy/verify failed on launch", e)
            }
        }.start()
    }
}
