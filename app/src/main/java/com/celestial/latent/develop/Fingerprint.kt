package com.celestial.latent.develop

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A numerical description of a LOOK, made so it survives a change of subject.
 *
 * Comparing two photographs pixel by pixel only works when they are the same photograph. A look
 * builder cannot rely on that: the references are other people's pictures of other things. So
 * what is measured here is deliberately scene-independent — where the tones sit relative to each
 * other, which way the greys lean, how saturation behaves as things get brighter, and what
 * happens to the two hue families a film is judged on. Averaged over a set of images, the scenes
 * cancel and the look remains.
 *
 * Every figure is bounded and unitless so they can be compared and weighted against each other.
 */
data class Fingerprint(
    /** Where the darkest and brightest parts sit, and how the tones spread between them. */
    val black: Float,
    val shadow: Float,
    val mid: Float,
    val highlight: Float,
    val white: Float,
    /** How steeply the tones climb through the middle: the sense of contrast. */
    val contrast: Float,
    /** How gently the brightest tones flatten off — film's shoulder. */
    val shoulderRoll: Float,
    /** Colour cast in the shadows and in the highlights: the crossover that names a film. */
    val shadowWarmth: Float,
    val shadowGreen: Float,
    val highlightWarmth: Float,
    val highlightGreen: Float,
    /** Saturation overall, and whether it rises or falls with brightness. */
    val saturation: Float,
    val saturationSlope: Float,
    /** How the two families a film is judged on come out. */
    val skinHue: Float,
    val skinSaturation: Float,
    val foliageHue: Float,
    val foliageSaturation: Float,
) {

    fun asList() = listOf(
        black, shadow, mid, highlight, white, contrast, shoulderRoll,
        shadowWarmth, shadowGreen, highlightWarmth, highlightGreen,
        saturation, saturationSlope, skinHue, skinSaturation, foliageHue, foliageSaturation,
    )

    /**
     * How far apart two looks are. Weighted, because not every figure matters equally to the
     * eye: the colour crossover and the skin behaviour carry a look more than the exact black
     * point does. 0 is identical; around 1 is "not the same look at all".
     */
    fun distanceTo(other: Fingerprint): Float {
        val a = asList(); val b = other.asList()
        var sum = 0f
        for (i in a.indices) {
            val d = (a[i] - b[i]) * WEIGHTS[i]
            sum += d * d
        }
        return sqrt(sum / WEIGHTS.sumOf { (it * it).toDouble() }.toFloat())
    }

    /** The readable names, in the same order as [asList], for showing the comparison. */
    companion object {
        val LABELS = listOf(
            "black point", "shadows", "midtones", "highlights", "white point",
            "contrast", "highlight roll-off",
            "shadow warmth", "shadow green", "highlight warmth", "highlight green",
            "saturation", "saturation with brightness",
            "skin hue", "skin saturation", "foliage hue", "foliage saturation",
        )

        private val WEIGHTS = floatArrayOf(
            0.6f, 1.0f, 1.0f, 1.0f, 0.6f,     // where the tones sit
            1.4f, 1.2f,                        // contrast and roll-off carry a lot
            1.6f, 1.4f, 1.6f, 1.4f,            // the crossover is the film's signature
            1.2f, 1.2f,                        // saturation behaviour
            1.8f, 1.4f, 1.0f, 1.0f,            // skin most of all, then foliage
        )

        /** Averages a set of fingerprints — how a reference set becomes one target. */
        fun average(list: List<Fingerprint>): Fingerprint {
            require(list.isNotEmpty())
            val n = list.size.toFloat()
            val sums = FloatArray(LABELS.size)
            for (f in list) f.asList().forEachIndexed { i, v -> sums[i] += v }
            val a = FloatArray(LABELS.size) { sums[it] / n }
            return Fingerprint(
                a[0], a[1], a[2], a[3], a[4], a[5], a[6], a[7], a[8], a[9], a[10],
                a[11], a[12], a[13], a[14], a[15], a[16],
            )
        }

        /** How much the images in a set disagree — a scattered set makes a vague target. */
        fun spread(list: List<Fingerprint>): Float {
            if (list.size < 2) return 0f
            val mean = average(list)
            return list.map { it.distanceTo(mean) }.average().toFloat()
        }

        /**
         * Measures a display-referred image. Brightness is normalised away first: a reference
         * that happens to be a dark photograph should not make the look darker, only the
         * relationships between its tones should count.
         */
        fun of(bmp: Bitmap): Fingerprint {
            val step = max(1, max(bmp.width, bmp.height) / 320)
            val n = (bmp.width / step) * (bmp.height / step)
            val lum = FloatArray(n)
            val r = FloatArray(n); val g = FloatArray(n); val b = FloatArray(n)
            var i = 0
            var y = 0
            while (y < bmp.height && i < n) {
                var x = 0
                while (x < bmp.width && i < n) {
                    val p = bmp.getPixel(x, y)
                    val rr = ((p shr 16) and 0xFF) / 255f
                    val gg = ((p shr 8) and 0xFF) / 255f
                    val bb = (p and 0xFF) / 255f
                    r[i] = rr; g[i] = gg; b[i] = bb
                    lum[i] = 0.2126f * rr + 0.7152f * gg + 0.0722f * bb
                    i++
                    x += step
                }
                y += step
            }
            val count = i
            val sorted = lum.copyOf(count).also { it.sort() }
            fun pct(p: Float) = sorted[((count - 1) * p).toInt().coerceIn(0, count - 1)]

            val p01 = pct(0.01f); val p10 = pct(0.10f); val p50 = pct(0.50f)
            val p90 = pct(0.90f); val p99 = pct(0.99f); val p75 = pct(0.75f)

            // Normalise brightness out: the look is the shape of the distribution, not its level.
            val span = max(p99 - p01, 1e-3f)
            fun norm(v: Float) = ((v - p01) / span).coerceIn(0f, 1f)

            // Colour cast, measured where it shows: the darkest and brightest fifths.
            val darkCut = pct(0.20f); val brightCut = pct(0.80f)
            var dr = 0f; var dg = 0f; var db = 0f; var dn = 0
            var hr = 0f; var hg = 0f; var hb = 0f; var hn = 0
            var satSum = 0f; var satDark = 0f; var satBright = 0f
            var skinH = 0f; var skinS = 0f; var skinN = 0
            var folH = 0f; var folS = 0f; var folN = 0
            for (k in 0 until count) {
                val mx = max(r[k], max(g[k], b[k]))
                val mn = min(r[k], min(g[k], b[k]))
                val sat = if (mx > 0.04f) (mx - mn) / mx else 0f
                satSum += sat
                if (lum[k] <= darkCut) { dr += r[k]; dg += g[k]; db += b[k]; dn++; satDark += sat }
                if (lum[k] >= brightCut) { hr += r[k]; hg += g[k]; hb += b[k]; hn++; satBright += sat }
                // Hue in degrees, only where there is enough colour to have one.
                if (sat > 0.12f && mx > 0.08f) {
                    val h = hueOf(r[k], g[k], b[k], mx, mn)
                    if (h in 5f..50f) { skinH += h; skinS += sat; skinN++ }      // skin and warm browns
                    if (h in 60f..170f) { folH += h; folS += sat; folN++ }       // foliage and greens
                }
            }
            fun cast(a: Float, c: Float, m: Int) = if (m == 0) 0f else ((a - c) / m).coerceIn(-0.5f, 0.5f)

            return Fingerprint(
                black = norm(p01),
                shadow = norm(p10),
                mid = norm(p50),
                highlight = norm(p90),
                white = norm(p99),
                // Contrast: how much of the range the middle half occupies.
                contrast = ((p75 - p10) / span).coerceIn(0f, 2f),
                // Roll-off: how much less the top decile climbs than the middle does.
                shoulderRoll = (1f - ((p99 - p90) / max(p90 - p50, 1e-3f))).coerceIn(-2f, 2f),
                shadowWarmth = cast(dr, db, dn),
                shadowGreen = cast(dg, (dr + db) / 2f, dn),
                highlightWarmth = cast(hr, hb, hn),
                highlightGreen = cast(hg, (hr + hb) / 2f, hn),
                saturation = (satSum / count).coerceIn(0f, 1f),
                // Whether colour strengthens or fades as things get brighter.
                saturationSlope = (
                    (if (hn > 0) satBright / hn else 0f) - (if (dn > 0) satDark / dn else 0f)
                    ).coerceIn(-1f, 1f),
                skinHue = if (skinN == 0) 0f else ((skinH / skinN) / 60f).coerceIn(0f, 1f),
                skinSaturation = if (skinN == 0) 0f else (skinS / skinN).coerceIn(0f, 1f),
                foliageHue = if (folN == 0) 0f else (((folH / folN) - 60f) / 110f).coerceIn(0f, 1f),
                foliageSaturation = if (folN == 0) 0f else (folS / folN).coerceIn(0f, 1f),
            )
        }

        private fun hueOf(r: Float, g: Float, b: Float, mx: Float, mn: Float): Float {
            val d = mx - mn
            if (d < 1e-6f) return 0f
            val h = when (mx) {
                r -> 60f * (((g - b) / d) % 6f)
                g -> 60f * (((b - r) / d) + 2f)
                else -> 60f * (((r - g) / d) + 4f)
            }
            return if (h < 0f) h + 360f else h
        }
    }
}
