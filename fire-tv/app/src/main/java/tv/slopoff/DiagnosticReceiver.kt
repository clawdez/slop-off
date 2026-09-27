package tv.slopoff

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

// Explicit shell-only requests; DUMP is a privileged permission ordinary apps lack.
class DiagnosticReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            "tv.slopoff.DUMP" -> DiagnosticService.instance?.requestDump()
            "tv.slopoff.CAPTURE_ONCE" -> CaptureProbeActivity.requestArmedFrame()
            "tv.slopoff.STOP_CAPTURE" -> CaptureProbeActivity.stopArmedProbe()
            "tv.slopoff.ENABLE_AUTOMATIC" -> CaptureSessionService.instance?.enableAutomatic()
        }
    }
}
