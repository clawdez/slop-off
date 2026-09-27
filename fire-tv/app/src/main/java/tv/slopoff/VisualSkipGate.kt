package tv.slopoff

import kotlin.math.abs

/** Conservative gate for a manually requested, one-shot experiment, not an ad detector. */
internal object VisualSkipGate {
    data class Word(val label: String, val confidence: Float, val pass: Int,
        val left: Int, val top: Int, val right: Int, val bottom: Int)
    data class Point(val x: Float, val y: Float)

    fun target(words: List<Word>, width: Int, height: Int, ageMs: Long): Point? {
        if (width <= 0 || height <= 0 || ageMs !in 0..7_500) return null
        fun valid(w: Word) = w.confidence.isFinite() && w.left >= 0 && w.top >= 0 &&
            w.right <= width && w.bottom <= height && w.right > w.left && w.bottom > w.top
        val adMarker = words.any { valid(it) && it.pass == 0 && it.label == "sponsored" &&
            it.confidence >= 75f && it.right < width * .5f && it.top > height * .75f }
        if (!adMarker) return null
        val skips = words.filter { valid(it) && it.label in setOf("skip", "skipad", "skipads") &&
            it.confidence >= 85f && it.left > width * .78f && it.top > height * .75f &&
            it.bottom < height * .96f && it.right - it.left < width * .12f &&
            it.bottom - it.top < height * .08f }
        val full = skips.singleOrNull { it.pass == 0 } ?: return null
        val crop = skips.singleOrNull { it.pass == 1 } ?: return null
        if (abs(full.left - crop.left) > 8 || abs(full.top - crop.top) > 8 ||
            abs(full.right - crop.right) > 8 || abs(full.bottom - crop.bottom) > 8) return null
        return Point((full.left + full.right) / 2f, (full.top + full.bottom) / 2f)
    }
}
