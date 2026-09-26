package tv.slopoff

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

// Explicit shell-only requests; DUMP is a privileged permission ordinary apps lack.
class DiagnosticReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "tv.slopoff.DUMP") DiagnosticService.instance?.requestDump()
    }
}
