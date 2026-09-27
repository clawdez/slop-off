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
        VisualSkipGate.target(frame(words, 0), age)
    private fun frame(words: List<VisualSkipGate.Word>, at: Long) =
        VisualSkipGate.Frame(words, 1280, 720, at, 10, 20, 30)
    private val borderline = listOf(ad, full, crop.copy(confidence = 82.34507f))

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
    @Test fun borderlineCloseupNeedsASeparateMatchingFrame() {
        val current = frame(borderline, 8000)
        assertNull(VisualSkipGate.target(current, 11_000))
        assertNotNull(VisualSkipGate.target(current, 11_000, frame(borderline, 1000)))
        assertNull(VisualSkipGate.target(current, 11_000, current))
    }
    @Test fun previousFrameCannotCrossWindowEpisodeOrSizeChanges() {
        val current = frame(borderline, 8000)
        val prior = frame(borderline, 1000)
        for (changed in listOf(prior.copy(window = 11), prior.copy(windowEpoch = 21),
            prior.copy(episode = 31), prior.copy(width = 1920))) {
            assertNull(VisualSkipGate.target(current, 11_000, changed))
        }
    }
    @Test fun stalePreviousOrShiftedTargetCannotConfirmBorderlineReading() {
        val current = frame(borderline, 8000)
        assertNull(VisualSkipGate.target(current, 12_001, frame(borderline, 0)))
        val shifted = borderline.map { if (it.label == "skip") it.copy(left = it.left + 4, right = it.right + 4) else it }
        assertNull(VisualSkipGate.target(current, 11_000, frame(shifted, 1000)))
    }
    @Test fun twoFramesStillRequireStrongAdAndMainLabelAndMinimumCloseup() {
        for (bad in listOf(listOf(ad.copy(confidence = 89f), full, crop),
            listOf(ad, full.copy(confidence = 89f), crop), listOf(ad, full, crop.copy(confidence = 79f)))) {
            assertNull(VisualSkipGate.target(frame(bad, 8000), 11_000, frame(bad, 1000)))
        }
        assertNull(VisualSkipGate.target(frame(borderline, 8000), 11_000, frame(listOf(full, crop), 1000)))
    }
    @Test fun regionalSkipStillNeedsFullFrameAdEvidenceAndIndependentRefinement() {
        assertNotNull(target(listOf(ad, full.copy(pass = 2), crop)))
        assertNotNull(target(listOf(ad, full.copy(pass = 3), crop)))
        assertNull(target(listOf(ad.copy(pass = 2), full.copy(pass = 2), crop)))
        assertNull(target(listOf(ad, full.copy(pass = 2))))
    }
    @Test fun regionalDiscoveryCannotResolveConflictingStrongTargetsByChoosingOne() {
        assertNull(target(evidence + full.copy(pass = 2, left = 1160, right = 1190)))
        assertNull(target(listOf(ad, full.copy(pass = 2), full.copy(pass = 3), crop)))
    }
    @Test fun borderlineRegionalSkipStillRequiresTwoRecentFrames() {
        val regional = listOf(ad, full.copy(pass = 2), crop.copy(confidence = 82f))
        assertNull(VisualSkipGate.target(frame(regional, 8000), 11_000))
        assertNotNull(VisualSkipGate.target(frame(regional, 8000), 11_000, frame(regional, 1000)))
        assertNull(target(listOf(ad, full.copy(pass = 2, confidence = 89f), crop)))
    }
    @Test fun contextualConfirmationCanReplaceAWeakTightCropWithoutLoweringThresholds() {
        val weak = crop.copy(confidence = 51.61788f)
        assertNull(target(listOf(ad, full, weak)))
        assertNotNull(target(listOf(ad, full, weak, crop.copy(pass = 4))))
        assertNull(target(listOf(ad, full, weak, crop.copy(pass = 4, confidence = 79f))))
    }
    @Test fun conflictingOrAmbiguousContextualReadingsAreRejected() {
        assertNull(target(evidence + crop.copy(pass = 4, left = 1160, right = 1190)))
        assertNull(target(listOf(ad, full, crop.copy(pass = 4), crop.copy(pass = 4))))
    }
    @Test fun contextualBorderlineStillNeedsSeparateRecentEvidence() {
        val words = listOf(ad, full, crop.copy(pass = 4, confidence = 82f))
        assertNull(VisualSkipGate.target(frame(words, 8000), 11_000))
        assertNotNull(VisualSkipGate.target(frame(words, 8000), 11_000, frame(words, 1000)))
    }
}
