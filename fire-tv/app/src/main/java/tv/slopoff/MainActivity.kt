package tv.slopoff

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var status: TextView
    private val refresh = Runnable { render() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
        }
        layout.addView(TextView(this).apply { text = "SLOP OFF TV"; textSize = 30f })
        status = TextView(this).apply { textSize = 19f }
        layout.addView(status)
        fun button(label: String, action: () -> Unit) {
            layout.addView(Button(this).apply { text = label; setOnClickListener { action() } })
        }
        button("Start protection session (experimental)") {
            startActivity(Intent(this, CaptureProbeActivity::class.java).apply {
                putExtra("session", true); putExtra("armed", true); putExtra("automatic", true)
            })
        }
        button("Stop protection session") { CaptureSessionService.instance?.shutdown(); render() }
        button("Set up media access") {
            try { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
            catch (_: Exception) { status.text = "Fire OS hides this setup screen. Media access needs developer setup." }
        }
        button("Enable / disable diagnostics") {
            val prefs = getSharedPreferences("diagnostics", MODE_PRIVATE)
            prefs.edit().putBoolean("enabled", !prefs.getBoolean("enabled", true)).apply()
            DiagnosticService.instance?.refreshEnabled()
            render()
        }
        button("Open accessibility setup") {
            try { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            catch (_: Exception) { status.text = "Fire OS did not expose this settings screen. Use Settings > Accessibility; report which options are visible." }
        }
        button("Dump next YouTube tree (within 30 seconds)") {
            DiagnosticService.instance?.armDump()
            render()
        }
        layout.addView(TextView(this).apply {
            text = "Experimental protection requires Accessibility, media access, and one screen permission per session.\nEverything stays on this TV. Audio stays unchanged."
            textSize = 16f
        })
        setContentView(ScrollView(this).apply { addView(layout) })
        layout.getChildAt(2).requestFocus()
    }
    private fun render() {
        status.text = "Session: ${CaptureSessionService.status}\nDiagnostics: ${if (getSharedPreferences("diagnostics", MODE_PRIVATE).getBoolean("enabled", true)) "ENABLED" else "DISABLED"}\nAccessibility: ${if (DiagnosticService.instance != null) "CONNECTED" else "NOT CONNECTED"}\nYouTube: ${DiagnosticService.foreground}\nLast event: ${DiagnosticService.lastEvent}\nVersion: ${BuildConfig.VERSION_NAME}"
    }
    override fun onResume() { super.onResume(); DiagnosticService.uiRefresh = { runOnUiThread(refresh) }; render() }
    override fun onPause() { DiagnosticService.uiRefresh = null; super.onPause() }
}
