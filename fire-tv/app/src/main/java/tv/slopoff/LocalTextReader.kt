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
                val left = if (pass == 0) 0 else bitmap.width / 2
                val top = if (pass == 0) 0 else bitmap.height / 2
                val scale = if (pass == 0) 1 else 2
                val input = if (pass == 0) bitmap else {
                    val crop = Bitmap.createBitmap(bitmap, left, top, bitmap.width - left, bitmap.height - top)
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
