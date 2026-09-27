package tv.slopoff

import kotlin.math.abs

/** Visual evidence gate; media events only schedule checks and never authorize input. */
internal object VisualSkipGate {
    data class Word(val label: String, val confidence: Float, val pass: Int,
        val left: Int, val top: Int, val right: Int, val bottom: Int)
    data class Point(val x: Float, val y: Float)
    data class Frame(val words: List<Word>, val width: Int, val height: Int,
        val capturedAt: Long, val window: Int, val windowEpoch: Long, val episode: Long)

    private fun matching(a: Word, b: Word, tolerance: Int) = a.label == b.label &&
        abs(a.left - b.left) <= tolerance && abs(a.top - b.top) <= tolerance &&
        abs(a.right - b.right) <= tolerance && abs(a.bottom - b.bottom) <= tolerance

    private fun readings(frame: Frame): Pair<Word, Word>? {
        val (words, width, height) = frame
        if (width !in 1..8192 || height !in 1..8192) return null
        fun valid(w: Word) = w.confidence.isFinite() && w.left >= 0 && w.top >= 0 &&
            w.confidence <= 100f && w.right <= width && w.bottom <= height && w.right > w.left && w.bottom > w.top
        val adMarker = words.any { valid(it) && it.pass == 0 && it.label == "sponsored" &&
            it.confidence >= 90f && it.right < width * .5f && it.top > height * .75f }
        if (!adMarker) return null
        val skips = words.filter { valid(it) && it.label in setOf("skip", "skipad", "skipads") &&
            it.confidence >= 80f && it.left > width * .78f && it.top > height * .75f &&
            it.bottom < height * .96f && it.right - it.left < width * .12f &&
            it.bottom - it.top < height * .08f }
        val full = skips.singleOrNull { it.pass == 0 } ?: return null
        val crop = skips.singleOrNull { it.pass == 1 } ?: return null
        if (full.confidence < 90f || !matching(full, crop, 8)) return null
        return full to crop
    }

    fun needsSecondFrame(frame: Frame, now: Long): Boolean {
        if (now - frame.capturedAt !in 0..7_500) return false
        return readings(frame)?.second?.confidence?.let { it < 85f } ?: false
    }

    fun target(current: Frame, now: Long, previous: Frame? = null): Point? {
        if (now - current.capturedAt !in 0..7_500) return null
        val (full, crop) = readings(current) ?: return null
        // A borderline close-up needs a separate recent capture of the same target.
        // Main-frame Skip and Sponsored confidence must be >=90 in BOTH captures.
        if (crop.confidence < 85f) {
            val prior = previous ?: return null
            if (prior.width != current.width || prior.height != current.height ||
                prior.window != current.window || prior.windowEpoch != current.windowEpoch ||
                prior.episode != current.episode || now - prior.capturedAt !in 0..12_000 ||
                current.capturedAt - prior.capturedAt < 500) return null
            val (oldFull, oldCrop) = readings(prior) ?: return null
            if (!matching(full, oldFull, 2) || !matching(crop, oldCrop, 2)) return null
        }
        return Point((full.left + full.right) / 2f, (full.top + full.bottom) / 2f)
    }
}
