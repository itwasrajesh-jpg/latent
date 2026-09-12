package com.celestial.latent.develop

import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Removes colour noise from a linear image before the film sees it.
 *
 * Film grain is texture and belongs in the picture; the red and green blotches a sensor
 * produces in the dark are not — film never saw them, and the engine's dye couplers push them
 * further apart, so they get worse through the simulation rather than better.
 *
 * The method is the classic one and invents nothing: separate brightness from colour, blur
 * only the colour (colour detail in any photograph is genuinely low-resolution), then put
 * brightness back untouched. Detail and grain are unaffected because brightness is never
 * filtered — which is exactly why this is not a "denoiser" in the AI sense.
 */
object ChromaDenoise {

    /**
     * @param strength 0 = off, 1 = strong. The blur radius scales with this.
     * Works in place on a linear RGB float buffer (width*height*3 floats).
     */
    fun apply(data: ByteBuffer, width: Int, height: Int, strength: Float) {
        if (strength <= 0.001f || width < 8 || height < 8) return
        val t0 = System.nanoTime()
        val f = data.order(ByteOrder.nativeOrder()).asFloatBuffer()
        val n = width * height

        // Brightness, and the two colour differences. Chroma is what gets blurred.
        val y = FloatArray(n)
        val cb = FloatArray(n)
        val cr = FloatArray(n)
        for (i in 0 until n) {
            val r = f.get(i * 3); val g = f.get(i * 3 + 1); val b = f.get(i * 3 + 2)
            val lum = 0.2126f * r + 0.7152f * g + 0.0722f * b
            y[i] = lum
            cb[i] = b - lum
            cr[i] = r - lum
        }

        // Radius grows with strength; three box passes approximate a Gaussian closely enough
        // and stay linear in cost regardless of radius.
        val radius = (1 + (strength * 6f)).toInt().coerceIn(1, 12)
        repeat(3) {
            boxBlur(cb, width, height, radius)
            boxBlur(cr, width, height, radius)
        }

        for (i in 0 until n) {
            val lum = y[i]
            val b = cb[i] + lum
            val r = cr[i] + lum
            // Green is recovered from the others so brightness is preserved exactly.
            val g = (lum - 0.2126f * r - 0.0722f * b) / 0.7152f
            f.put(i * 3, r); f.put(i * 3 + 1, g); f.put(i * 3 + 2, b)
        }
        Log.i("Latent", "chroma denoise: strength ${"%.2f".format(strength)}, radius $radius, ${width}x$height in ${(System.nanoTime() - t0) / 1_000_000} ms")
    }

    /** Separable running-sum box blur: cost does not grow with radius. */
    private fun boxBlur(v: FloatArray, w: Int, h: Int, r: Int) {
        val tmp = FloatArray(v.size)
        val span = 2 * r + 1
        // Horizontal
        for (row in 0 until h) {
            val base = row * w
            var sum = 0f
            for (x in -r..r) sum += v[base + x.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                tmp[base + x] = sum / span
                val out = base + (x - r).coerceIn(0, w - 1)
                val into = base + (x + r + 1).coerceIn(0, w - 1)
                sum += v[into] - v[out]
            }
        }
        // Vertical
        for (x in 0 until w) {
            var sum = 0f
            for (yy in -r..r) sum += tmp[yy.coerceIn(0, h - 1) * w + x]
            for (yy in 0 until h) {
                v[yy * w + x] = sum / span
                val out = (yy - r).coerceIn(0, h - 1) * w + x
                val into = (yy + r + 1).coerceIn(0, h - 1) * w + x
                sum += tmp[into] - tmp[out]
            }
        }
    }

    /**
     * A sensible default for a shot taken at [iso]: nothing in daylight, rising through the
     * ISO range. Colour noise is an artefact of amplification, so the ISO predicts it well.
     */
    fun strengthForIso(iso: Int): Float = when {
        iso <= 200 -> 0f
        iso <= 400 -> 0.2f
        iso <= 800 -> 0.4f
        iso <= 1600 -> 0.6f
        iso <= 3200 -> 0.8f
        else -> 1f
    }
}
