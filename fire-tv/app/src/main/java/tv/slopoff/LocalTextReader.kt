package tv.slopoff

import android.content.Context
import android.graphics.Bitmap
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
            engine.setPageSegMode(TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT)
            for (pass in 0..1) {
                if (!valid()) break
                // Refine only one plausible full-frame label. A whole quadrant can
                // group the tiny label with the countdown/icon and lose the word.
                val anchor = if (pass == 1) words.singleOrNull {
                    it.pass == 0 && it.label in setOf("skip", "skipad", "skipads") &&
                        it.confidence >= 85f && it.left > bitmap.width * .78f && it.top > bitmap.height * .75f
                } else null
                if (pass == 1 && anchor == null) break
                val left = if (pass == 0) 0 else (anchor!!.left - 6).coerceAtLeast(0)
                val top = if (pass == 0) 0 else (anchor!!.top - 6).coerceAtLeast(0)
                val right = if (pass == 0) bitmap.width else (anchor!!.right + 6).coerceAtMost(bitmap.width)
                val bottom = if (pass == 0) bitmap.height else (anchor!!.bottom + 6).coerceAtMost(bitmap.height)
                val scale = if (pass == 0) 1 else 4
                engine.setPageSegMode(if (pass == 0) TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT
                    else TessBaseAPI.PageSegMode.PSM_SINGLE_WORD)
                val input = if (pass == 0) bitmap else {
                    val crop = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
                    try { Bitmap.createScaledBitmap(crop, crop.width * scale, crop.height * scale, true) }
                    finally { crop.recycle() }
                }
                try {
                    engine.setImage(input)
                    if (!valid()) break
                    engine.getUTF8Text()
                    val level = TessBaseAPI.PageIteratorLevel.RIL_WORD
                    val iterator = engine.resultIterator
                    if (iterator != null) {
                        iterator.begin()
                        do {
                            if (!valid()) break
                            inspected++
                            val label = iterator.getUTF8Text(level)?.trim()?.lowercase(Locale.ROOT)
                                ?.trim { !it.isLetter() }.orEmpty()
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
