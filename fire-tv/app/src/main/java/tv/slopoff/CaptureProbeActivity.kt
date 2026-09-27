package tv.slopoff

import android.app.Activity
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Bitmap
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File

import java.util.Locale
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.TextView
import org.json.JSONObject

/** API-28 feasibility probe only: explicit consent, one in-memory frame, hard timeout. */
class CaptureProbeActivity : Activity() {
    companion object {
        // Only set after explicit system consent; cleared on every exit.
        private var armedInstance: java.lang.ref.WeakReference<CaptureProbeActivity>? = null
        fun requestArmedFrame() {
            val active = armedInstance?.get()
            if (active != null) active.beginArmedFrame()
            else Log.i("SLOPOFF_CAPTURE", "{\"result\":\"not_armed\"}")
        }
        fun stopArmedProbe() { armedInstance?.get()?.complete("stopped_by_shell") }
    }
    private val handler = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    @Volatile private var finished = false
    private val engineLock = Any()
    private var textEngine: TessBaseAPI? = null
    private var processingText = false
    private var armedRequestStarted = false
    private var deadline = 0L

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(TextView(this).apply {
            text = "SLOP OFF — CAPTURE FEASIBILITY TEST\n\nOne frame, local text recognition. No image or screen text saved or uploaded.\nNo clicking or audio changes. Returns to YouTube after consent."
            textSize = 24f
            setPadding(48, 40, 48, 40)
        })
        // Do not reuse a projection grant across activity recreation.
        if (state != null) { finish(); return }
        try {
            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(manager.createScreenCaptureIntent(), 42)
        } catch (e: Exception) { complete("permission_unavailable", e.javaClass.simpleName) }
    }

    @Deprecated("API 28 probe uses platform Activity result API")
    override fun onActivityResult(request: Int, result: Int, data: Intent?) {
        super.onActivityResult(request, result, data)
        if (request != 42) return
        if (result != RESULT_OK || data == null) { complete("permission_denied"); return }
        try {
            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(result, data)
            projection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { if (!processingText) complete("projection_stopped") }
            }, handler)
            val armed = intent.getBooleanExtra("armed", false)
            val lifetime = if (armed) 600_000L else 12_000L
            deadline = SystemClock.elapsedRealtime() + lifetime
            handler.postDelayed({ complete("timeout") }, lifetime)
            moveTaskToBack(true)
            if (armed) {
                armedInstance = java.lang.ref.WeakReference(this)
                Log.i("SLOPOFF_CAPTURE", JSONObject().put("result", "armed_no_frames")
                    .put("expiresMs", deadline).put("imageSaved", false).toString())
            } else handler.postDelayed({ waitForYouTube() }, 1500)
        } catch (e: Exception) { complete("projection_error", e.javaClass.simpleName) }
    }

    private fun beginArmedFrame() {
        if (finished || armedRequestStarted || display != null || SystemClock.elapsedRealtime() >= deadline) return
        armedRequestStarted = true
        handler.removeCallbacksAndMessages(null)
        deadline = SystemClock.elapsedRealtime() + 12_000
        handler.postDelayed({ complete("timeout") }, 12_000)
        waitForYouTube()
    }

    private fun youtubeIsActive(): Boolean {
        val root = DiagnosticService.instance?.rootInActiveWindow ?: return false
        return try { root.packageName?.toString() in DiagnosticService.youtubePackages }
        finally { root.recycle() }
    }

    private fun waitForYouTube() {
        if (finished) return
        try {
            if (!youtubeIsActive()) {
                if (SystemClock.elapsedRealtime() < deadline) handler.postDelayed({ waitForYouTube() }, 300)
                return
            }
            val imageReader = ImageReader.newInstance(1280, 720, PixelFormat.RGBA_8888, 2)
            reader = imageReader
            imageReader.setOnImageAvailableListener({ source ->
                if (!finished) inspectFrame(source)
            }, handler)
            display = projection?.createVirtualDisplay("SlopOff-one-frame-probe", 1280, 720,
                resources.displayMetrics.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.surface, null, handler)
            if (display == null) complete("display_unavailable")
        } catch (e: Exception) { complete("capture_error", e.javaClass.simpleName) }
    }

    private fun inspectFrame(source: ImageReader) {
        if (processingText || finished) return
        var bitmap: Bitmap? = null
        try {
            val frame = source.acquireLatestImage() ?: return
            try {
                if (!youtubeIsActive()) { complete("foreground_changed"); return }
                val plane = frame.planes[0]
                val bytes = plane.buffer
                val pixels = IntArray(frame.width * frame.height)
                var sampled = 0
                var nonBlack = 0
                for (y in 0 until frame.height) for (x in 0 until frame.width) {
                    val offset = y * plane.rowStride + x * plane.pixelStride
                    val r = bytes.get(offset).toInt() and 255
                    val g = bytes.get(offset + 1).toInt() and 255
                    val b = bytes.get(offset + 2).toInt() and 255
                    pixels[y * frame.width + x] = (255 shl 24) or (r shl 16) or (g shl 8) or b
                    if (x % 8 == 0 && y % 8 == 0) {
                        sampled++
                        if ((r + g + b) / 3 > 8) nonBlack++
                    }
                }
                bitmap = Bitmap.createBitmap(pixels, frame.width, frame.height, Bitmap.Config.ARGB_8888)
                Log.i("SLOPOFF_CAPTURE", JSONObject().put("result", "frame_received")
                    .put("width", frame.width).put("height", frame.height).put("samples", sampled)
                    .put("nonBlackSamples", nonBlack).put("imageSaved", false)
                    .put("elapsedMs", SystemClock.elapsedRealtime()).toString())
            } finally { frame.close() }
            processingText = true
            // Stop frame delivery and release the projection BEFORE asynchronous OCR.
            release()
            handler.postDelayed({ complete("text_timeout") }, 30_000)
            recognize(bitmap!!)
        } catch (e: Exception) {
            bitmap?.recycle()
            complete("frame_error", e.javaClass.simpleName)
        }
    }

    private fun recognize(bitmap: Bitmap) {
        val appContext = applicationContext
        Thread({
            val started = SystemClock.elapsedRealtime()
            var engine: TessBaseAPI? = null
            try {
                val dataRoot = File(appContext.filesDir, "ocr-model")
                val model = File(dataRoot, "tessdata/eng.traineddata")
                if (!model.exists()) {
                    check(model.parentFile!!.mkdirs() || model.parentFile!!.isDirectory)
                    appContext.assets.open("tessdata/eng.traineddata").use { input ->
                        model.outputStream().use { output -> input.copyTo(output) }
                    }
                }
                engine = TessBaseAPI()
                check(engine.init(dataRoot.absolutePath, "eng", TessBaseAPI.OEM_LSTM_ONLY))
                synchronized(engineLock) { if (!finished) textEngine = engine }
                if (finished) return@Thread
                engine.setPageSegMode(TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT)
                val candidates = org.json.JSONArray()
                var words = 0
                // Two bounded passes over ONE frame. Word matching avoids requiring
                // nearby countdown text or button icons to form an exact whole line.
                for (pass in 0..1) {
                    if (finished) break
                    val left = if (pass == 0) 0 else bitmap.width / 2
                    val top = if (pass == 0) 0 else bitmap.height / 2
                    val scale = if (pass == 0) 1 else 2
                    val input = if (pass == 0) bitmap else {
                        val crop = Bitmap.createBitmap(bitmap, left, top,
                            bitmap.width - left, bitmap.height - top)
                        try { Bitmap.createScaledBitmap(crop, crop.width * scale, crop.height * scale, true) }
                        finally { crop.recycle() }
                    }
                    try {
                        engine.setImage(input)
                        engine.getUTF8Text() // Arbitrary recognized text is never logged.
                        val level = TessBaseAPI.PageIteratorLevel.RIL_WORD
                        val iterator = engine.resultIterator
                        if (iterator != null) {
                            iterator.begin()
                            do {
                                if (finished) break
                                words++
                                val normalized = iterator.getUTF8Text(level)?.trim()
                                    ?.lowercase(Locale.ROOT)?.trim { !it.isLetter() }.orEmpty()
                                if (normalized in setOf("skip", "skipad", "skipads")) {
                                    val box = iterator.getBoundingBox(level)
                                    val mapped = listOf(box[0] / scale + left, box[1] / scale + top,
                                        box[2] / scale + left, box[3] / scale + top)
                                    candidates.put(JSONObject().put("label", normalized)
                                        .put("pass", pass).put("confidence", iterator.confidence(level))
                                        .put("bounds", org.json.JSONArray(mapped)))
                                }
                            } while (iterator.next(level))
                        }
                    } finally { if (input !== bitmap) input.recycle() }
                }
                val result = JSONObject().put("result", "recognized").put("frameWidth", bitmap.width)
                    .put("frameHeight", bitmap.height).put("wordsInspected", words)
                    .put("candidates", candidates).put("durationMs", SystemClock.elapsedRealtime() - started)
                    .put("elapsedMs", SystemClock.elapsedRealtime())
                handler.post {
                    if (!finished) {
                        if (youtubeIsActive()) {
                            Log.i("SLOPOFF_TEXT", result.toString())
                            complete("complete")
                        } else complete("foreground_changed")
                    }
                }
            } catch (e: Exception) {
                handler.post { complete("text_error", e.javaClass.simpleName) }
            } catch (e: LinkageError) {
                handler.post { complete("text_native_error", e.javaClass.simpleName) }
            } finally {
                synchronized(engineLock) {
                    textEngine = null
                    engine?.recycle()
                }
                bitmap.recycle()
            }
        }, "slopoff-single-frame-text").start()
    }

    private fun complete(result: String, error: String? = null) {
        if (finished) return
        finished = true
        Log.i("SLOPOFF_CAPTURE", JSONObject().put("result", result).put("error", error ?: "")
            .put("elapsedMs", SystemClock.elapsedRealtime()).toString())
        release()
        finish()
    }

    private fun release() {
        if (finished) synchronized(engineLock) { textEngine?.stop() }
        if (armedInstance?.get() === this) armedInstance = null
        handler.removeCallbacksAndMessages(null)
        display?.release(); display = null
        reader?.close(); reader = null
        val activeProjection = projection
        projection = null
        activeProjection?.stop()
    }

    override fun onDestroy() {
        finished = true
        release()
        super.onDestroy()
    }
}
