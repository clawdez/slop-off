package tv.slopoff

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.media.Image
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import java.nio.ByteBuffer
import java.util.Locale

/** No pixels or arbitrary recognized text leave this process or get written to storage. */
internal class LocalTextReader(private val context: Context) {
    private val lock = Any()
    private var activeEngine: TessBaseAPI? = null
    data class Result(val words: List<VisualSkipGate.Word>, val inspected: Int)

    fun cancel() { synchronized(lock) { activeEngine?.stop() } }

    fun read(bitmap: Bitmap, valid: () -> Boolean): Result {
        val root = File(context.filesDir, "ocr-model")
        val model = File(root, "tessdata/eng.traineddata")
        if (model.length() != 4_113_088L) {
            check(model.parentFile!!.mkdirs() || model.parentFile!!.isDirectory)
            val temp = File(model.parentFile, "eng.traineddata.tmp")
            context.assets.open("tessdata/eng.traineddata").use { input ->
                temp.outputStream().use { output -> input.copyTo(output) }
            }
            check(temp.length() == 4_113_088L && temp.renameTo(model))
        }
        val engine = TessBaseAPI()
        try {
            check(engine.init(root.absolutePath, "eng", TessBaseAPI.OEM_LSTM_ONLY))
            synchronized(lock) { activeEngine = engine }
            val words = mutableListOf<VisualSkipGate.Word>()
            var inspected = 0
            fun runPass(left: Int, top: Int, right: Int, bottom: Int, scale: Int,
                mode: Int, pass: Int, invert: Boolean = false) {
                if (!valid()) return
                engine.setPageSegMode(mode)
                var input = if (pass == 0) bitmap else {
                    val crop = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
                    try { Bitmap.createScaledBitmap(crop, crop.width * scale, crop.height * scale, true) }
                    finally { crop.recycle() }
                }
                try {
                    if (invert) {
                        val converted = Bitmap.createBitmap(input.width, input.height, Bitmap.Config.ARGB_8888)
                        try {
                            val matrix = ColorMatrix().apply {
                                setSaturation(0f)
                                postConcat(ColorMatrix(floatArrayOf(-1f,0f,0f,0f,255f,
                                    0f,-1f,0f,0f,255f, 0f,0f,-1f,0f,255f, 0f,0f,0f,1f,0f)))
                            }
                            Canvas(converted).drawBitmap(input, 0f, 0f,
                                Paint().apply { colorFilter = ColorMatrixColorFilter(matrix) })
                        } catch (e: Exception) { converted.recycle(); throw e }
                        if (input !== bitmap) input.recycle()
                        input = converted
                    }
                    engine.setImage(input)
                    if (!valid()) return
                    engine.getUTF8Text()
                    val level = TessBaseAPI.PageIteratorLevel.RIL_WORD
                    val iterator = engine.resultIterator
                    if (iterator != null) {
                        iterator.begin()
                        do {
                            if (!valid()) break
                            val label = iterator.getUTF8Text(level)?.trim()?.lowercase(Locale.ROOT)
                                ?.trim { !it.isLetter() }.orEmpty()
                            if (label.isNotEmpty()) inspected++
                            if (label in setOf("skip", "skipad", "skipads", "sponsored")) {
                                val b = iterator.getBoundingBox(level)
                                words.add(VisualSkipGate.Word(label, iterator.confidence(level), pass,
                                    b[0] / scale + left, b[1] / scale + top,
                                    b[2] / scale + left, b[3] / scale + top))
                            }
                        } while (iterator.next(level))
                    }
                } finally { if (input !== bitmap) input.recycle() }
            }
            runPass(0, 0, bitmap.width, bitmap.height, 1, TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT, 0)
            fun primaryLabels() = words.filter {
                it.pass in setOf(0, 2, 3) && it.label in setOf("skip", "skipad", "skipads") &&
                    it.confidence >= 90f && it.left > bitmap.width * .78f && it.top > bitmap.height * .75f
            }
            val hasAdMarker = words.any { it.pass == 0 && it.label == "sponsored" &&
                it.confidence >= 90f && it.right < bitmap.width * .5f && it.top > bitmap.height * .75f }
            // A full-frame miss must not prevent looking in the observed control area.
            // At most two small regional passes; no extra screen capture is performed.
            if (hasAdMarker && primaryLabels().isEmpty()) {
                val left = (bitmap.width * .76f).toInt()
                val top = (bitmap.height * .77f).toInt()
                val right = (bitmap.width * .99f).toInt()
                val bottom = (bitmap.height * .96f).toInt()
                runPass(left, top, right, bottom, 3, TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT, 2)
                if (primaryLabels().isEmpty())
                    runPass(left, top, right, bottom, 3, TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK, 3, true)
            }
            val anchor = primaryLabels().singleOrNull()
            if (anchor != null) {
                runPass((anchor.left - 6).coerceAtLeast(0), (anchor.top - 6).coerceAtLeast(0),
                    (anchor.right + 6).coerceAtMost(bitmap.width), (anchor.bottom + 6).coerceAtMost(bitmap.height),
                    4, TessBaseAPI.PageSegMode.PSM_SINGLE_WORD, 1)
                // A tiny crop can harm segmentation even when the main read is strong.
                // Recheck the original region with surrounding button context; do not
                // accept the weak close-up or lower any confidence requirement.
                if (words.none { it.pass == 1 && it.label == anchor.label && it.confidence >= 85f }) {
                    runPass((bitmap.width * .76f).toInt(), (bitmap.height * .77f).toInt(),
                        (bitmap.width * .99f).toInt(), (bitmap.height * .96f).toInt(),
                        2, TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT, 4)
                }
            }
            return Result(words, inspected)
        } finally { synchronized(lock) { activeEngine = null; engine.recycle() } }
    }

    companion object {
        /** Native buffer copy replaces the slow per-pixel Kotlin loop on this Stick. */
        fun bitmap(image: Image): Bitmap {
            val plane = image.planes[0]
            check(plane.pixelStride == 4 && plane.rowStride % 4 == 0)
            val paddedWidth = plane.rowStride / 4
            check(paddedWidth >= image.width)
            val buffer = plane.buffer.duplicate().apply { rewind() }
            val size = plane.rowStride * image.height
            val source = if (buffer.remaining() >= size) buffer else {
                // Some producers omit trailing padding in the final row.
                ByteBuffer.allocateDirect(size).apply { put(buffer); rewind() }
            }
            val padded = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
            try {
                padded.copyPixelsFromBuffer(source)
                if (paddedWidth == image.width) return padded
                return Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
            } catch (e: Exception) { padded.recycle(); throw e }
            finally { if (paddedWidth != image.width && !padded.isRecycled) padded.recycle() }
        }
    }
}
