package tv.slopoff

import android.app.Activity
import android.content.Intent
import android.graphics.PixelFormat
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
        fun requestArmedFrame() { armedInstance?.get()?.beginArmedFrame() }
        fun stopArmedProbe() { armedInstance?.get()?.complete("stopped_by_shell") }
    }
    private val handler = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var finished = false
    private var armedRequestStarted = false
    private var deadline = 0L

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(TextView(this).apply {
            text = "SLOP OFF — CAPTURE FEASIBILITY TEST\n\nOne frame in memory. No image saved or uploaded.\nNo clicking or audio changes. Returns to YouTube after consent."
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
                override fun onStop() { complete("projection_stopped") }
            }, handler)
            val armed = intent.getBooleanExtra("armed", false)
            val lifetime = if (armed) 180_000L else 12_000L
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
            val imageReader = ImageReader.newInstance(640, 360, PixelFormat.RGBA_8888, 2)
            reader = imageReader
            imageReader.setOnImageAvailableListener({ source ->
                if (!finished) inspectFrame(source)
            }, handler)
            display = projection?.createVirtualDisplay("SlopOff-one-frame-probe", 640, 360,
                resources.displayMetrics.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.surface, null, handler)
            if (display == null) complete("display_unavailable")
        } catch (e: Exception) { complete("capture_error", e.javaClass.simpleName) }
    }

    private fun inspectFrame(source: ImageReader) {
        try {
            val frame = source.acquireLatestImage() ?: return
            try {
                // Fail closed if the user switched apps while the frame was arriving.
                if (!youtubeIsActive()) { complete("foreground_changed"); return }
                val plane = frame.planes[0]
                val bytes = plane.buffer
                var samples = 0
                var nonBlack = 0
                var minLuma = 255
                var maxLuma = 0
                for (y in 0 until frame.height step 8) for (x in 0 until frame.width step 8) {
                    val offset = y * plane.rowStride + x * plane.pixelStride
                    val r = bytes.get(offset).toInt() and 255
                    val g = bytes.get(offset + 1).toInt() and 255
                    val b = bytes.get(offset + 2).toInt() and 255
                    val luma = (r + g + b) / 3
                    minLuma = minOf(minLuma, luma)
                    maxLuma = maxOf(maxLuma, luma)
                    if (luma > 8) nonBlack++
                    samples++
                }
                Log.i("SLOPOFF_CAPTURE", JSONObject().put("result", "frame_received")
                    .put("width", frame.width).put("height", frame.height).put("samples", samples)
                    .put("nonBlackSamples", nonBlack).put("minLuma", minLuma).put("maxLuma", maxLuma)
                    .put("imageSaved", false).put("elapsedMs", SystemClock.elapsedRealtime()).toString())
            } finally { frame.close() }
            complete("complete")
        } catch (e: Exception) { complete("frame_error", e.javaClass.simpleName) }
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
