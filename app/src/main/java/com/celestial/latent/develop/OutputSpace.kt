package com.celestial.latent.develop

/**
 * Two output spaces the engine does not provide, converted by Latent after it.
 *
 * The engine renders to one of its own six spaces; these take its **sRGB** output and convert
 * it to a display space it does not know about. Both are a change of primaries followed by a
 * change of curve — no tone mapping and no gamut compression beyond a final clip, so a colour
 * that sRGB could already show is unchanged in hue.
 *
 *  - **Display P3** — what this phone's screen actually shows, so an export in it looks its
 *    best on the device. Same curve as sRGB, wider primaries.
 *  - **Rec.709 (2.4)** — the video viewing standard: sRGB's primaries with a pure 2.4 gamma,
 *    meant for a dark room. Pairs with the cine film and print stocks.
 *
 * Matrices are the standard derived values (Bradford-adapted D65→D65, so no white-point change
 * is needed for P3-D65) and are listed to seven digits so they can be checked against any
 * colour-science reference.
 */
object OutputSpace {

    const val ENGINE_SRGB = "SRGB"
    const val DISPLAY_P3 = "DISPLAY_P3"
    const val REC709_24 = "REC709_24"

    /** The ones Latent adds on top of the engine's own list. */
    val OURS = listOf(
        DISPLAY_P3 to "Display P3 — best on this phone's screen",
        REC709_24 to "Rec.709 (gamma 2.4) — video, dark room",
    )

    fun isOurs(name: String) = name == DISPLAY_P3 || name == REC709_24

    /** linear sRGB → linear Display P3 (both D65). */
    private val SRGB_TO_P3 = floatArrayOf(
        0.8224621f, 0.1775380f, 0.0000000f,
        0.0331941f, 0.9668058f, 0.0000000f,
        0.0170827f, 0.0723974f, 0.9105199f,
    )

    /**
     * Converts an sRGB-encoded image in place.
     * @param rgb 8-bit RGB triples as produced by the engine's sRGB output.
     */
    fun convert(rgb: IntArray, target: String) {
        if (!isOurs(target)) return
        val toLinear = FloatArray(256) { i ->
            val c = i / 255f
            if (c <= 0.04045f) c / 12.92f else Math.pow(((c + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
        }
        for (i in rgb.indices) {
            val p = rgb[i]
            val r = toLinear[(p shr 16) and 0xFF]
            val g = toLinear[(p shr 8) and 0xFF]
            val b = toLinear[p and 0xFF]

            val (lr, lg, lb) = when (target) {
                DISPLAY_P3 -> Triple(
                    SRGB_TO_P3[0] * r + SRGB_TO_P3[1] * g + SRGB_TO_P3[2] * b,
                    SRGB_TO_P3[3] * r + SRGB_TO_P3[4] * g + SRGB_TO_P3[5] * b,
                    SRGB_TO_P3[6] * r + SRGB_TO_P3[7] * g + SRGB_TO_P3[8] * b,
                )
                // Rec.709 shares sRGB's primaries: only the curve changes.
                else -> Triple(r, g, b)
            }

            val or_ = encode(lr, target)
            val og = encode(lg, target)
            val ob = encode(lb, target)
            rgb[i] = (0xFF shl 24) or (or_ shl 16) or (og shl 8) or ob
        }
    }

    /**
     * The same conversion for a baked look table, so the viewfinder shows what the export will
     * be rather than the engine's sRGB. Values are 0..1 triples, changed in place.
     */
    fun convertTable(rgb: FloatArray, target: String) {
        if (!isOurs(target)) return
        fun toLinear(c: Float) = if (c <= 0.04045f) c / 12.92f else Math.pow(((c + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
        var i = 0
        while (i + 2 < rgb.size) {
            val r = toLinear(rgb[i].coerceIn(0f, 1f))
            val g = toLinear(rgb[i + 1].coerceIn(0f, 1f))
            val b = toLinear(rgb[i + 2].coerceIn(0f, 1f))
            val lr: Float; val lg: Float; val lb: Float
            if (target == DISPLAY_P3) {
                lr = SRGB_TO_P3[0] * r + SRGB_TO_P3[1] * g + SRGB_TO_P3[2] * b
                lg = SRGB_TO_P3[3] * r + SRGB_TO_P3[4] * g + SRGB_TO_P3[5] * b
                lb = SRGB_TO_P3[6] * r + SRGB_TO_P3[7] * g + SRGB_TO_P3[8] * b
            } else { lr = r; lg = g; lb = b }
            rgb[i] = encodeFloat(lr, target)
            rgb[i + 1] = encodeFloat(lg, target)
            rgb[i + 2] = encodeFloat(lb, target)
            i += 3
        }
    }

    private fun encodeFloat(v: Float, target: String): Float {
        val c = v.coerceIn(0f, 1f)
        return when (target) {
            DISPLAY_P3 -> if (c <= 0.0031308f) c * 12.92f else 1.055f * Math.pow(c.toDouble(), 1.0 / 2.4).toFloat() - 0.055f
            else -> Math.pow(c.toDouble(), 1.0 / 2.4).toFloat()
        }
    }

    private fun encode(v: Float, target: String): Int {
        val c = v.coerceIn(0f, 1f)
        val e = when (target) {
            // Display P3 uses the sRGB transfer curve.
            DISPLAY_P3 -> if (c <= 0.0031308f) c * 12.92f else 1.055f * Math.pow(c.toDouble(), 1.0 / 2.4).toFloat() - 0.055f
            // Rec.709 for viewing: a pure 2.4 gamma, as a grading monitor expects.
            else -> Math.pow(c.toDouble(), 1.0 / 2.4).toFloat()
        }
        return (e * 255f + 0.5f).toInt().coerceIn(0, 255)
    }

    /**
     * The colour space to tag the saved file with, so viewers decode it as we encoded it.
     *
     * Display P3 is a standard entry. Rec.709 at gamma 2.4 is not: Android's BT709 uses the
     * broadcast transfer curve, which is not the same as a pure 2.4, so tagging with it would
     * have viewers decode slightly differently from how we wrote the file. The space is
     * therefore described exactly — Rec.709 primaries, D65, gamma 2.4.
     */
    fun androidSpace(target: String): android.graphics.ColorSpace? = when (target) {
        DISPLAY_P3 -> android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.DISPLAY_P3)
        REC709_24 -> runCatching {
            android.graphics.ColorSpace.Rgb(
                "Rec.709 (2.4)",
                floatArrayOf(0.640f, 0.330f, 0.300f, 0.600f, 0.150f, 0.060f),
                floatArrayOf(0.3127f, 0.3290f),
                2.4,
            )
        }.getOrNull()
        else -> null
    }
}
