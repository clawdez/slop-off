package tv.slopoff

import org.junit.Assert.*
import org.junit.Test

class ScanBudgetTest {
    @Test fun noFramesWithoutTriggerOrAfterStop() {
        val b = ScanBudget(); assertFalse(b.take(100))
        b.begin(100); assertTrue(b.take(100)); b.stop(); assertFalse(b.take(101))
    }
    @Test fun burstExpiresAndLimitsSixAttempts() {
        val b = ScanBudget(); b.begin(100)
        repeat(6) { assertTrue(b.take(100L + it)) }; assertFalse(b.take(107))
        b.begin(200); assertFalse(b.take(45_200))
    }
    @Test fun repeatedMediaEventsCannotBypassGlobalCap() {
        val b = ScanBudget()
        repeat(12) { b.begin(it.toLong()); assertTrue(b.take(it.toLong())) }
        b.begin(20); assertFalse(b.take(20))
        b.begin(60_001); assertTrue(b.take(60_001))
    }
}
