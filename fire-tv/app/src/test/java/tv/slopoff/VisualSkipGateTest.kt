package tv.slopoff

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class VisualSkipGateTest {
    private val ad = VisualSkipGate.Word("sponsored", 92f, 0, 70, 627, 145, 645)
    private val full = VisualSkipGate.Word("skip", 92f, 0, 1096, 630, 1125, 645)
    private val crop = VisualSkipGate.Word("skip", 93f, 1, 1095, 630, 1125, 645)
    private val evidence = listOf(ad, full, crop)
    private fun target(words: List<VisualSkipGate.Word> = evidence, age: Long = 3500) =
        VisualSkipGate.target(words, 1280, 720, age)

    @Test fun matchingAdAndButtonEvidenceAllowsOneTarget() { assertNotNull(target()) }
    @Test fun skipWithoutAdEvidenceIsRejected() { assertNull(target(listOf(full, crop))) }
    @Test fun onePassOrConflictingPositionsAreRejected() {
        assertNull(target(listOf(ad, full)))
        assertNull(target(listOf(ad, full, crop.copy(left = 1110))))
    }
    @Test fun ambiguousMultipleButtonsAreRejected() { assertNull(target(evidence + full.copy(left = 1160, right = 1190))) }
    @Test fun staleAndInvalidTimestampsAreRejected() {
        assertNull(target(age = 7501)); assertNull(target(age = -1))
    }
    @Test fun lowConfidenceOrNonFiniteEvidenceIsRejected() {
        assertNull(target(listOf(ad, full.copy(confidence = 84f), crop)))
        assertNull(target(listOf(ad.copy(confidence = Float.NaN), full, crop)))
    }
    @Test fun wrongRegionsAndInvalidBoxesAreRejected() {
        assertNull(target(listOf(ad.copy(top = 20, bottom = 40), full, crop)))
        assertNull(target(listOf(ad, full.copy(left = -1), crop)))
        assertNull(target(listOf(ad, full.copy(right = 1090), crop)))
    }
}
