package tv.slopoff

import android.app.*
import android.content.ComponentName
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.MediaMetadata
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.*
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/** API-28 session: retains consent, but creates frame delivery only for bounded requests. */
class CaptureSessionService : Service() {
    companion object {
        var instance: CaptureSessionService? = null
            private set
        var status = "Stopped"
            private set
    }
    private val main = Handler(Looper.getMainLooper())
    private val thread = HandlerThread("slopoff-local-text")
    private lateinit var worker: Handler
    private lateinit var recognizer: LocalTextReader
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    @Volatile private var stopped = false
    @Volatile private var generation = 0L
    private var busy = false
    private var keepAlive = false
    private var allowManualInput = false
    private var automatic = false
    private var candidate = false
    private var wasYoutube = false
    private var evidenceEpisode = 0L
    private var previousEvidence: VisualSkipGate.Frame? = null
    private val budget = ScanBudget()
    private var controller: MediaController? = null
    private var mediaRegistered = false
    private val mediaManager by lazy { getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager }
    private val component by lazy { ComponentName(this, MediaAccessService::class.java) }
    private val scanTask = Runnable {
        if (automatic && candidate && youtubeWindow() != null && budget.take(SystemClock.elapsedRealtime())) {
            requestFrame(true)
        } else if (!busy && automatic) update("Waiting for a media event")
    }
    private val timeout = Runnable { shutdown("Session stopped: frame or text timeout") }
    private val sessionListener = MediaSessionManager.OnActiveSessionsChangedListener { bindMedia(it.orEmpty()) }
    private val mediaCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) { playback(state, false) }
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            // Only the occurrence of the event matters; no title, ID, duration, or history is read.
            playback(controller?.playbackState, true)
        }
        override fun onSessionDestroyed() { bindMedia(emptyList()) }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        thread.start(); worker = Handler(thread.looper)
        recognizer = LocalTextReader(applicationContext)
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel("protection", "Slop Off session", NotificationManager.IMPORTANCE_LOW))
        startForeground(1, notification("Starting session"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP" || intent == null) { shutdown("Stopped"); return START_NOT_STICKY }
        if (projection != null) return START_NOT_STICKY
        val data = intent.getParcelableExtra<Intent>("grant")
        if (data == null) { shutdown("Screen permission needed"); return START_NOT_STICKY }
        keepAlive = intent.getBooleanExtra("session", false)
        allowManualInput = intent.getBooleanExtra("test_skip", false) && intent.getBooleanExtra("armed", false)
        automatic = keepAlive && intent.getBooleanExtra("automatic", false)
        try {
            projection = (getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager)
                .getMediaProjection(Activity.RESULT_OK, data)
            projection!!.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { shutdown("Screen permission ended; start a new session") }
            }, main)
            update(if (automatic) "Waiting for media access" else "Ready; no frames running")
            connectMedia()
            main.postDelayed({ shutdown("Session expired") }, if (keepAlive) 8 * 60 * 60_000L else 600_000L)
            if (!intent.getBooleanExtra("armed", false)) main.postDelayed({ requestFrame(false) }, 1500)
        } catch (e: Exception) { shutdown("Session error: ${e.javaClass.simpleName}") }
        return START_NOT_STICKY
    }

    fun enableAutomatic() {
        if (stopped || !keepAlive) return
        automatic = true
        connectMedia()
        if (mediaRegistered) playback(controller?.playbackState, true)
    }

    fun connectMedia() {
        main.post {
            if (stopped) return@post
            try {
                if (!mediaRegistered) {
                    mediaManager.addOnActiveSessionsChangedListener(sessionListener, component, main)
                    mediaRegistered = true
                }
                bindMedia(mediaManager.getActiveSessions(component))
                if (!automatic) update("Ready; no frames running")
            } catch (_: SecurityException) {
                disconnectMedia()
                if (automatic) update("Media access needed; automatic checks paused")
            }
        }
    }

    fun disconnectMedia() {
        main.post {
            controller?.unregisterCallback(mediaCallback); controller = null
            if (mediaRegistered) {
                try { mediaManager.removeOnActiveSessionsChangedListener(sessionListener) } catch (_: Exception) { }
            }
            mediaRegistered = false
            candidate = false; cancelBurst()
            if (automatic && !stopped) update("Media access disconnected; automatic checks paused")
        }
    }

    private fun bindMedia(controllers: List<MediaController>) {
        if (stopped) return
        val next = controllers.firstOrNull { it.packageName in DiagnosticService.youtubePackages }
        if (next?.sessionToken == controller?.sessionToken && next != null) return
        controller?.unregisterCallback(mediaCallback)
        controller = next
        candidate = false; cancelBurst()
        next?.registerCallback(mediaCallback, main)
        playback(next?.playbackState, false)
    }

    private fun playback(state: PlaybackState?, changedItem: Boolean) {
        if (stopped) return
        // Only the observed ad-like transport states are candidates. They never authorize input alone.
        val next = state != null && state.state in setOf(PlaybackState.STATE_PLAYING, PlaybackState.STATE_PAUSED) &&
            state.actions in setOf(53L, 55L)
        val starts = next && (!candidate || changedItem)
        candidate = next
        if (!next) {
            cancelBurst()
            if (automatic) update("Watching for ads; no frames running")
        } else if (starts && automatic && youtubeWindow() != null) startBurst()
    }

    fun foregroundSignal() {
        main.post {
            if (stopped) return@post
            val youtube = youtubeWindow() != null
            if (!youtube) cancelBurst()
            else if (!wasYoutube && automatic && candidate) startBurst()
            wasYoutube = youtube
        }
    }

    private fun startBurst() {
        evidenceEpisode++; previousEvidence = null
        budget.begin(SystemClock.elapsedRealtime())
        main.removeCallbacks(scanTask)
        main.postDelayed(scanTask, 600)
    }

    private fun cancelBurst() {
        evidenceEpisode++; previousEvidence = null
        budget.stop(); main.removeCallbacks(scanTask)
        generation++ // In-flight evidence becomes invalid.
    }

    private fun youtubeWindow(): Int? {
        if (!(getSystemService(POWER_SERVICE) as PowerManager).isInteractive) return null
        val service = DiagnosticService.instance ?: return null
        if (!service.diagnosticsEnabled()) return null
        val root = service.rootInActiveWindow ?: return null
        return try { if (root.packageName?.toString() in DiagnosticService.youtubePackages) root.windowId else null }
            finally { root.recycle() }
    }

    fun requestFrame(fromAutomatic: Boolean = false) {
        if (stopped || busy || projection == null) return
        val window = youtubeWindow() ?: run { update("Waiting for YouTube"); return }
        if (fromAutomatic && (!automatic || !candidate || !mediaRegistered)) return
        busy = true
        val token = ++generation
        val epoch = DiagnosticService.windowEpoch
        val episode = evidenceEpisode
        val inputAllowed = fromAutomatic || allowManualInput
        update("Checking ad controls locally")
        log("check_trigger", if (fromAutomatic) "automatic" else "manual")
        main.postDelayed(timeout, 30_000)
        try {
            val source = ImageReader.newInstance(1280, 720, PixelFormat.RGBA_8888, 2)
            reader = source
            source.setOnImageAvailableListener({ images ->
                if (stopped || reader !== images) return@setOnImageAvailableListener
                val frame = images.acquireLatestImage() ?: return@setOnImageAvailableListener
                val capturedAt = SystemClock.elapsedRealtime()
                val bitmap = try {
                    if (generation != token || youtubeWindow() != window || DiagnosticService.windowEpoch != epoch) null
                    else LocalTextReader.bitmap(frame)
                } catch (e: Exception) { log("frame_error", e.javaClass.simpleName); null }
                finally { frame.close() }
                releaseFrames()
                if (bitmap == null) { finishFrame("Frame discarded", fromAutomatic); return@setOnImageAvailableListener }
                log("frame_received", "copyMs=${SystemClock.elapsedRealtime() - capturedAt}")
                worker.post {
                    try {
                        val result = recognizer.read(bitmap) { !stopped && generation == token }
                        main.post {
                            if (stopped) return@post
                            val age = SystemClock.elapsedRealtime() - capturedAt
                            val words = JSONArray()
                            result.words.forEach { w -> words.put(JSONObject().put("label", w.label)
                                .put("pass", w.pass).put("confidence", w.confidence)
                                .put("bounds", JSONArray(listOf(w.left, w.top, w.right, w.bottom)))) }
                            Log.i("SLOPOFF_TEXT", JSONObject().put("result", "recognized").put("ageMs", age)
                                .put("wordsInspected", result.inspected).put("candidates", words).toString())
                            if (generation != token || evidenceEpisode != episode || youtubeWindow() != window || DiagnosticService.windowEpoch != epoch ||
                                (fromAutomatic && (!automatic || !candidate || !mediaRegistered))) {
                                previousEvidence = null
                                finishFrame("Screen or playback changed; no press", fromAutomatic)
                            } else if (inputAllowed) {
                                val evidence = VisualSkipGate.Frame(result.words.toList(), 1280, 720, capturedAt, window, epoch, episode)
                                val previous = previousEvidence
                                previousEvidence = evidence
                                val service = DiagnosticService.instance
                                if (service == null) finishFrame("Accessibility disconnected", fromAutomatic)
                                else service.testVisualSkip(evidence, previous) { outcome ->
                                    if (outcome == "gesture_completed_unverified") {
                                        budget.stop(); previousEvidence = null
                                    }
                                    val confirmSoon = outcome == "visual_gate_rejected" &&
                                        VisualSkipGate.needsSecondFrame(evidence, SystemClock.elapsedRealtime())
                                    finishFrame(outcome, fromAutomatic, if (confirmSoon) 1_000 else 4_000)
                                }
                            } else finishFrame("Observation complete; no press", false)
                        }
                    } catch (e: Exception) { main.post { finishFrame("Text error: ${e.javaClass.simpleName}", fromAutomatic) } }
                    catch (e: LinkageError) { main.post { shutdown("Text engine unavailable") } }
                    finally { bitmap.recycle() }
                }
            }, main)
            display = projection!!.createVirtualDisplay("SlopOff-requested-frame", 1280, 720,
                resources.displayMetrics.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, source.surface, null, main)
        } catch (e: Exception) { releaseFrames(); finishFrame("Capture error: ${e.javaClass.simpleName}", fromAutomatic) }
    }

    private fun finishFrame(outcome: String, fromAutomatic: Boolean, nextDelayMs: Long = 4_000) {
        if (stopped) return
        main.removeCallbacks(timeout)
        busy = false
        log("frame_complete", outcome)
        update(outcome)
        if (!keepAlive) shutdown(outcome)
        else if (fromAutomatic && automatic && candidate) {
            main.removeCallbacks(scanTask)
            main.postDelayed(scanTask, nextDelayMs)
        }
    }

    private fun releaseFrames() {
        reader?.setOnImageAvailableListener(null, null)
        display?.release(); display = null
        reader?.close(); reader = null
    }

    private fun log(event: String, detail: String = "") {
        Log.i("SLOPOFF_SESSION", JSONObject().put("event", event).put("detail", detail)
            .put("elapsedMs", SystemClock.elapsedRealtime()).toString())
    }

    private fun update(message: String) {
        if (status == message) return
        status = message; DiagnosticService.lastEvent = message; DiagnosticService.uiRefresh?.invoke()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(1, notification(message))
        log("state", message)
    }

    private fun notification(message: String): Notification {
        val stop = PendingIntent.getService(this, 0, Intent(this, CaptureSessionService::class.java).setAction("STOP"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "protection").setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Slop Off TV — experimental session").setContentText(message).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build()).build()
    }

    fun shutdown(reason: String = "Stopped") {
        if (stopped) return
        stopped = true; generation++
        previousEvidence = null
        main.removeCallbacksAndMessages(null)
        recognizer.cancel(); releaseFrames()
        controller?.unregisterCallback(mediaCallback); controller = null
        if (mediaRegistered) try { mediaManager.removeOnActiveSessionsChangedListener(sessionListener) } catch (_: Exception) { }
        mediaRegistered = false
        val old = projection; projection = null; old?.stop()
        status = reason; DiagnosticService.uiRefresh?.invoke(); log("stopped", reason)
        thread.quitSafely()
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }
    override fun onDestroy() { shutdown(); if (instance === this) instance = null; super.onDestroy() }
    override fun onBind(intent: Intent?) = null
}
