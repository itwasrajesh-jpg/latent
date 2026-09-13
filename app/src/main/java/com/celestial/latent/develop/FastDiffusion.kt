package com.celestial.latent.develop

import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

/**
 * Latent's own diffusion filter, for when the engine's exact one is too slow to wait for.
 *
 * The engine convolves the image with a large round kernel directly, so its cost grows with the
 * square of the blur radius — minutes at 12.5 MP. This does the same job the way glow is usually
 * done: the kernel is a sum of exponential falloffs, which are close enough to Gaussians that two
 * one-dimensional passes reproduce them, and the widest component is computed on a downscaled
 * copy because a very wide halo has no fine detail to lose.
 *
 * Measured against a direct convolution of the engine's kernel, this lands within about
 * 13–21% — close in character, not a match. It is therefore offered as its own look rather
 * than as a faster route to the same picture. The engine's exact filter remains the default;
 * this exists so a diffusion look is usable while composing and on exports where a few minutes
 * of waiting is not worth a difference you cannot see. It lives in Latent, never in the engine,
 * so the engine's parity guarantees are untouched.
 *
 * Works in place on linear RGB floats, before the film stage — the same place the engine applies
 * its own camera-side filter.
 */
object FastDiffusion {

    /** The four filters the engine models, as core/halo/bloom weights and widths in micrometres. */
    private data class Profile(val core: Float, val coreUm: Float, val halo: Float, val haloUm: Float, val bloom: Float, val bloomUm: Float)

    private val PROFILES = mapOf(
        // Glimmerglass: a fine sparkle, small core, little bloom.
        "glimmerglass" to Profile(0.55f, 6f, 0.30f, 40f, 0.15f, 150f),
        // Black Pro-Mist: the classic — modest core, strong mid halo, restrained bloom.
        "black_pro_mist" to Profile(0.45f, 10f, 0.40f, 70f, 0.15f, 260f),
        // Pro-Mist: the same idea with more lift everywhere.
        "pro_mist" to Profile(0.40f, 12f, 0.40f, 80f, 0.20f, 300f),
        // Cinebloom: bloom-dominant, the softest of the four.
        "cinebloom" to Profile(0.30f, 14f, 0.35f, 110f, 0.35f, 420f),
    )

    /**
     * @param pixelSizeUm how many micrometres one pixel covers on the negative — the engine works
     * in real film dimensions, so the blur must scale with the frame, not the pixel count.
     */
    fun apply(
        data: ByteBuffer, width: Int, height: Int,
        family: String, strength: Float, spatialScale: Float,
        coreMul: Float, haloMul: Float, bloomMul: Float, warmth: Float,
        pixelSizeUm: Float,
    ) {
        if (strength <= 0.001f || width < 16 || height < 16) return
        val p = PROFILES[family] ?: PROFILES.getValue("black_pro_mist")
        val t0 = System.nanoTime()
        val f = data.order(ByteOrder.nativeOrder()).asFloatBuffer()
        val n = width * height

        // Each component blurred separately, then mixed back over the original.
        val core = blurred(f, width, height, radiusPx(p.coreUm * spatialScale, pixelSizeUm), 1)
        val halo = blurred(f, width, height, radiusPx(p.haloUm * spatialScale, pixelSizeUm), 2)
        val bloom = blurred(f, width, height, radiusPx(p.bloomUm * spatialScale, pixelSizeUm), 4)

        val wc = p.core * coreMul * strength
        val wh = p.halo * haloMul * strength
        val wb = p.bloom * bloomMul * strength
        val keep = 1f - (wc + wh + wb).coerceIn(0f, 0.95f)

        for (i in 0 until n) {
            for (c in 0 until 3) {
                val j = i * 3 + c
                // Warmth tilts the halo towards red or blue, as the engine's own control does.
                val tint = when (c) { 0 -> 1f + warmth * 0.15f; 2 -> 1f - warmth * 0.15f; else -> 1f }
                val v = f.get(j) * keep + (core[j] * wc + halo[j] * wh + bloom[j] * wb) * tint
                f.put(j, max(0f, v))
            }
        }
        Log.i("Latent", "fast diffusion: $family strength=$strength ${width}x$height in ${(System.nanoTime() - t0) / 1_000_000} ms")
    }

    private fun radiusPx(um: Float, pixelSizeUm: Float) = (um / max(pixelSizeUm, 0.01f)).toInt().coerceIn(1, 400)

    /**
     * A blurred copy. Wide components are computed on a [step]-times smaller image and scaled
     * back up: a halo that wide carries no detail, so nothing visible is lost and the cost falls
     * by the square of the step.
     */
    private fun blurred(src: java.nio.FloatBuffer, w: Int, h: Int, radius: Int, step: Int): FloatArray {
        val sw = (w + step - 1) / step
        val sh = (h + step - 1) / step
        val small = FloatArray(sw * sh * 3)
        for (y in 0 until sh) for (x in 0 until sw) {
            val sx = (x * step).coerceAtMost(w - 1)
            val sy = (y * step).coerceAtMost(h - 1)
            val si = (sy * w + sx) * 3
            val di = (y * sw + x) * 3
            small[di] = src.get(si); small[di + 1] = src.get(si + 1); small[di + 2] = src.get(si + 2)
        }
        // Box radius tuned against a direct convolution of the engine's kernel: the best match
        // shrinks as the component gets wider and is computed on a smaller copy.
        val factor = when (step) { 1 -> 1.3f; 2 -> 0.8f; else -> 0.4f }
        val r = ((radius * factor) / step).toInt().coerceAtLeast(1)
        repeat(3) { boxBlurRgb(small, sw, sh, r) }

        // Back to full size, bilinear so the glow has no visible steps.
        val out = FloatArray(w * h * 3)
        for (y in 0 until h) {
            val fy = (y.toFloat() / step).coerceIn(0f, (sh - 1).toFloat())
            val y0 = fy.toInt(); val y1 = (y0 + 1).coerceAtMost(sh - 1); val ty = fy - y0
            for (x in 0 until w) {
                val fx = (x.toFloat() / step).coerceIn(0f, (sw - 1).toFloat())
                val x0 = fx.toInt(); val x1 = (x0 + 1).coerceAtMost(sw - 1); val tx = fx - x0
                val di = (y * w + x) * 3
                for (c in 0 until 3) {
                    val a = small[(y0 * sw + x0) * 3 + c] * (1 - tx) + small[(y0 * sw + x1) * 3 + c] * tx
                    val b = small[(y1 * sw + x0) * 3 + c] * (1 - tx) + small[(y1 * sw + x1) * 3 + c] * tx
                    out[di + c] = a * (1 - ty) + b * ty
                }
            }
        }
        return out
    }

    private fun boxBlurRgb(v: FloatArray, w: Int, h: Int, r: Int) {
        val tmp = FloatArray(v.size)
        val span = 2 * r + 1
        for (c in 0 until 3) {
            for (row in 0 until h) {
                val base = row * w
                var sum = 0f
                for (x in -r..r) sum += v[(base + x.coerceIn(0, w - 1)) * 3 + c]
                for (x in 0 until w) {
                    tmp[(base + x) * 3 + c] = sum / span
                    sum += v[(base + (x + r + 1).coerceIn(0, w - 1)) * 3 + c] - v[(base + (x - r).coerceIn(0, w - 1)) * 3 + c]
                }
            }
            for (x in 0 until w) {
                var sum = 0f
                for (y in -r..r) sum += tmp[(y.coerceIn(0, h - 1) * w + x) * 3 + c]
                for (y in 0 until h) {
                    v[(y * w + x) * 3 + c] = sum / span
                    sum += tmp[((y + r + 1).coerceIn(0, h - 1) * w + x) * 3 + c] - tmp[((y - r).coerceIn(0, h - 1) * w + x) * 3 + c]
                }
            }
        }
    }
}
