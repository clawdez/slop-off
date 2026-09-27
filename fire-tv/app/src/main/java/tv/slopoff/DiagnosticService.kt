package tv.slopoff

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.Point
import android.graphics.Rect
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.WindowManager
import org.json.JSONObject
import java.util.Locale

class DiagnosticService : AccessibilityService() {
    companion object {
        @Volatile var instance: DiagnosticService? = null
        @Volatile var foreground = "UNKNOWN"
        @Volatile var lastEvent = "Waiting for accessibility"
        @Volatile var uiRefresh: (() -> Unit)? = null
        @Volatile var windowEpoch = 0L
        // Verify the installed official package on the physical Fire TV before testing.
        val youtubePackages = setOf("com.amazon.firetv.youtube", "com.google.android.youtube.tv")
    }
    private val thread = HandlerThread("slopoff-inspection")
    private lateinit var worker: Handler
    @Volatile private var enabled = true
    @Volatile private var dumpUntil = 0L
    private var previousPackage = ""
    private var lastSummary = 0L
    private val contentEvents = java.util.concurrent.atomic.AtomicInteger()
    private val windowEvents = java.util.concurrent.atomic.AtomicInteger()
    private var lastDump = -5000L
    private var lastTestTap = -10_000L
    private val recent = linkedMapOf<String, Long>()
    private val queued = java.util.concurrent.atomic.AtomicBoolean(false)
    private val scan = Runnable { queued.set(false); inspect(false) }

    override fun onServiceConnected() {
        if (!thread.isAlive) thread.start()
        worker = Handler(thread.looper)
        instance = this
        refreshEnabled()
        report("SLOPOFF_SERVICE", JSONObject().put("connected", true))
        worker.post(scan)
    }
    fun refreshEnabled() {
        enabled = getSharedPreferences("diagnostics", Context.MODE_PRIVATE).getBoolean("enabled", true)
        if (!enabled) { queued.set(false); dumpUntil = 0; if (::worker.isInitialized) worker.removeCallbacksAndMessages(null) }
        else if (::worker.isInitialized) schedule()
    }
    private fun schedule() {
        // One queued scan, no repeating timer and no unbounded event backlog.
        if (queued.compareAndSet(false, true)) worker.postDelayed(scan, 150)
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!enabled || event == null || !::worker.isInitialized) return
        val type = event.eventType
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) windowEpoch++
        if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && event.packageName?.toString() !in youtubePackages) return
        if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) contentEvents.incrementAndGet() else windowEvents.incrementAndGet()
        schedule()
    }
    fun armDump() {
        if (!enabled) return
        dumpUntil = SystemClock.elapsedRealtime() + 30_000
        lastEvent = "Tree dump armed for next YouTube event (30s)"
        uiRefresh?.invoke()
        schedule()
    }
    fun requestDump() { if (enabled && ::worker.isInitialized) worker.post { inspect(true) } }

    // Called only by the shell-protected one-shot probe after its visual gate passes.
    // No timer, event handler, or persistent setting can invoke input on its own.
    internal fun testVisualSkip(words: List<VisualSkipGate.Word>, width: Int, height: Int,
        capturedAt: Long, expectedWindow: Int, expectedEpoch: Long, done: (String) -> Unit) {
        val now = SystemClock.elapsedRealtime()
        if (!enabled || windowEpoch != expectedEpoch || now - lastTestTap < 10_000) {
            done("gesture_guard_rejected"); return
        }
        val point = VisualSkipGate.target(words, width, height, now - capturedAt)
        if (point == null) { done("visual_gate_rejected"); return }
        try {
            val size = Point()
            (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealSize(size)
            if (size.x <= size.y || kotlin.math.abs(size.x.toFloat() / size.y - width.toFloat() / height) > .01f) {
                done("display_geometry_changed"); return
            }
            val root = rootInActiveWindow
            if (root == null) { done("foreground_unavailable"); return }
            val allowed = try { root.packageName?.toString() in youtubePackages && root.windowId == expectedWindow }
                finally { root.recycle() }
            if (!allowed || windowEpoch != expectedEpoch || SystemClock.elapsedRealtime() - capturedAt > 7_500) {
                done("foreground_or_frame_changed"); return
            }
            val path = Path().apply { moveTo(point.x * size.x / width, point.y * size.y / height) }
            val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 50)).build()
            lastTestTap = now // Debounce attempts too; no retry after cancellation.
            val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription) { done("gesture_completed_unverified") }
                override fun onCancelled(gestureDescription: GestureDescription) { done("gesture_cancelled") }
            }, null)
            report("SLOPOFF_INPUT", JSONObject().put("testRequested", true).put("accepted", accepted))
            if (!accepted) done("gesture_dispatch_rejected")
        } catch (e: Exception) { done("gesture_error_${e.javaClass.simpleName}") }
    }
    private fun report(tag: String, data: JSONObject) {
        Log.i(tag, data.put("elapsedMs", SystemClock.elapsedRealtime()).toString())
    }
    private fun node(n: AccessibilityNodeInfo): JSONObject {
        val bounds = Rect().also { n.getBoundsInScreen(it) }
        return JSONObject().put("text", n.text?.toString()?.take(160) ?: "")
            .put("description", n.contentDescription?.toString()?.take(160) ?: "")
            .put("viewId", n.viewIdResourceName ?: "").put("class", n.className?.toString() ?: "")
            .put("clickable", n.isClickable).put("enabled", n.isEnabled)
            .put("visible", n.isVisibleToUser).put("bounds", bounds.toShortString())
            .put("children", n.childCount).put("supportsClick", n.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK })
    }
    private fun kind(n: AccessibilityNodeInfo): String? {
        val values = listOf(n.text, n.contentDescription).map { it?.toString()?.trim()?.lowercase(Locale.ROOT).orEmpty() }
        if (values.any { it in setOf("skip", "skip ad", "skip ads", "skip advertisement") }) return "SKIP"
        if (values.any { it in setOf("ad", "ads", "advertisement", "sponsored") || it.matches(Regex("ad \\d+ of \\d+")) }) return "AD"
        return null
    }
    private fun context(n: AccessibilityNodeInfo): JSONObject {
        val result = node(n)
        var parent = n.parent
        val parents = org.json.JSONArray()
        var depth = 0
        while (parent != null && depth++ < 4) {
            val current = parent
            parents.put(node(current))
            parent = current.parent
            current.recycle()
        }
        parent?.recycle()
        val children = org.json.JSONArray()
        for (i in 0 until minOf(n.childCount, 4)) n.getChild(i)?.let { children.put(node(it)); it.recycle() }
        return result.put("parents", parents).put("childDetails", children)
    }
    private fun inspect(requestedDump: Boolean) {
        if (!enabled) return
        try {
            val root = rootInActiveWindow
            if (root == null) {
                foreground = "UNKNOWN (no active root)"
                if (SystemClock.elapsedRealtime() - lastSummary > 5000) {
                    report("SLOPOFF_YOUTUBE", JSONObject().put("rootAvailable", false))
                    lastSummary = SystemClock.elapsedRealtime()
                }
                uiRefresh?.invoke()
                return
            }
            val pkg = root.packageName?.toString().orEmpty()
            val isYoutube = pkg in youtubePackages
            foreground = if (isYoutube) "DETECTED" else "NOT FOREGROUND"
            if (pkg != previousPackage) {
                report("SLOPOFF_FOREGROUND", JSONObject().put("package", pkg).put("windowId", root.windowId))
                previousPackage = pkg
                recent.clear()
            }
            if (!isYoutube) { root.recycle(); uiRefresh?.invoke(); return }
            val now = SystemClock.elapsedRealtime()
            val dump = (requestedDump || dumpUntil > now) && now - lastDump >= 5000
            if (dump) { dumpUntil = 0; lastDump = now; report("SLOPOFF_TREE", JSONObject().put("begin", true)) }
            val pending = java.util.ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
            pending.add(root to 0)
            var count = 0
            var candidates = 0
            var truncated = false
            val start = SystemClock.elapsedRealtime()
            try {
                while (enabled && pending.isNotEmpty() && count < 250 && SystemClock.elapsedRealtime() - start < 250) {
                    val (n, depth) = pending.removeFirst()
                    try {
                        count++
                        if (n.packageName?.toString() !in youtubePackages) continue
                        val candidate = kind(n)
                        if (dump) report("SLOPOFF_TREE", node(n).put("depth", depth).put("index", count))
                        if (candidate != null) {
                            candidates++
                            val key = "$candidate|${n.viewIdResourceName}|${n.text}|${n.contentDescription}"
                            if (now - (recent[key] ?: -10_000L) >= 5000) {
                                report("SLOPOFF_${candidate}_CANDIDATE", context(n))
                                recent[key] = now
                                if (recent.size > 64) recent.remove(recent.keys.first())
                                lastEvent = "$candidate candidate observed (unverified)"
                            }
                        }
                        val childLimit = if (depth < 20) minOf(n.childCount, 250 - count - pending.size).coerceAtLeast(0) else 0
                        if (childLimit < n.childCount) truncated = true
                        for (i in 0 until childLimit) {
                            n.getChild(i)?.let { pending.add(it to depth + 1) }
                        }
                    } finally { n.recycle() }
                }
                if (dump || now - lastSummary >= 5000) {
                    report("SLOPOFF_YOUTUBE", JSONObject().put("package", pkg).put("nodes", count)
                        .put("candidates", candidates).put("truncated", truncated || pending.isNotEmpty() || count >= 250)
                        .put("manualDump", dump).put("contentEvents", contentEvents.getAndSet(0)).put("windowEvents", windowEvents.getAndSet(0)))
                    lastSummary = now
                }
                if (dump) report("SLOPOFF_TREE", JSONObject().put("end", true))
            } finally { while (pending.isNotEmpty()) pending.removeFirst().first.recycle() }
            uiRefresh?.invoke()
        } catch (e: Exception) {
            report("SLOPOFF_SERVICE", JSONObject().put("inspectionError", e.javaClass.simpleName))
        }
    }
    override fun onInterrupt() { lastEvent = "Service interrupted"; uiRefresh?.invoke() }
    override fun onDestroy() {
        enabled = false
        if (::worker.isInitialized) worker.removeCallbacksAndMessages(null)
        thread.quitSafely()
        instance = null
        foreground = "UNKNOWN"
        lastEvent = "Service disconnected"
        uiRefresh?.invoke()
        super.onDestroy()
    }
}
