package tv.slopoff

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.Log
import android.widget.TextView

/** Obtains system consent once; capture lifetime belongs to a visible foreground service. */
class CaptureProbeActivity : Activity() {
    companion object {
        fun requestArmedFrame() {
            val session = CaptureSessionService.instance
            if (session != null) session.requestFrame()
            else Log.i("SLOPOFF_SESSION", "{\"event\":\"no_session\"}")
        }
        fun stopArmedProbe() { CaptureSessionService.instance?.shutdown() }
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if (CaptureSessionService.instance != null) {
            if (intent.getBooleanExtra("automatic", false)) CaptureSessionService.instance?.enableAutomatic()
            moveTaskToBack(true); finish(); return
        }
        setContentView(TextView(this).apply {
            text = "SLOP OFF TV\n\nAllow local screen reading for this session.\nImages stay on this TV and are discarded.\nSkip presses require matching ad and button checks.\nStop the session from Slop Off TV at any time."
            textSize = 24f; setPadding(48, 40, 48, 40)
        })
        if (state != null) { finish(); return }
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(manager.createScreenCaptureIntent(), 42)
    }
    @Deprecated("API 28 consent API")
    override fun onActivityResult(request: Int, result: Int, data: Intent?) {
        super.onActivityResult(request, result, data)
        if (request != 42) return
        if (result == RESULT_OK && data != null) {
            startForegroundService(Intent(this, CaptureSessionService::class.java).apply {
                putExtra("grant", data)
                for (key in listOf("session", "automatic", "armed", "test_skip"))
                    putExtra(key, intent.getBooleanExtra(key, false))
            })
        }
        moveTaskToBack(true); finish()
    }
}
