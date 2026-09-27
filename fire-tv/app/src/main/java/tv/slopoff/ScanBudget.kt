package tv.slopoff

/** A bounded burst after a media event, never an idle screenshot timer. */
internal class ScanBudget {
    private var deadline = 0L
    private var attempts = 0
    private var windowStart = -60_000L
    private var windowAttempts = 0
    fun begin(now: Long) { deadline = now + 45_000; attempts = 0 }
    fun stop() { deadline = 0 }
    fun take(now: Long): Boolean {
        if (now - windowStart >= 60_000) { windowStart = now; windowAttempts = 0 }
        if (deadline == 0L || now >= deadline || attempts >= 6 || windowAttempts >= 12) return false
        attempts++; windowAttempts++
        return true
    }
}
