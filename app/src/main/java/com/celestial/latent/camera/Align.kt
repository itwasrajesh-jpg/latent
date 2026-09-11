package com.celestial.latent.camera

import kotlin.math.abs

/**
 * Burst alignment for Bayer frames, pure Kotlin.
 *  - Global shift: coarse-to-fine block matching on grey pyramids built from 2x2 CFA cells,
 *    so the final shift is in steps of 2 sensor pixels (keeps the colour pattern intact).
 *  - Tile rejection: after shifting, 64x64 tiles that still disagree with the reference
 *    (subject moved) are left out of the average for that frame.
 */
object Align {

    class Pyramid(val levels: List<Level>)
    class Level(val w: Int, val h: Int, val g: FloatArray)

    /** Grey pyramid from a CFA frame: level 0 = one value per 2x2 cell (w/2 x h/2), then halved repeatedly. */
    fun pyramid(pix: ShortArray, w: Int, h: Int, depth: Int = 4): Pyramid {
        val w0 = w / 2; val h0 = h / 2
        val g0 = FloatArray(w0 * h0)
        for (y in 0 until h0) {
            val r0 = (2 * y) * w; val r1 = r0 + w
            for (x in 0 until w0) {
                val c = 2 * x
                g0[y * w0 + x] = ((pix[r0 + c].toInt() and 0xFFFF) + (pix[r0 + c + 1].toInt() and 0xFFFF) + (pix[r1 + c].toInt() and 0xFFFF) + (pix[r1 + c + 1].toInt() and 0xFFFF)) * 0.25f
            }
        }
        val levels = ArrayList<Level>(); levels += Level(w0, h0, g0)
        var cur = levels[0]
        repeat(depth - 1) {
            val nw = cur.w / 2; val nh = cur.h / 2
            val ng = FloatArray(nw * nh)
            for (y in 0 until nh) for (x in 0 until nw) {
                val i = (2 * y) * cur.w + 2 * x
                ng[y * nw + x] = (cur.g[i] + cur.g[i + 1] + cur.g[i + cur.w] + cur.g[i + cur.w + 1]) * 0.25f
            }
            cur = Level(nw, nh, ng); levels += cur
        }
        return Pyramid(levels)
    }

    private fun sad(a: Level, b: Level, dx: Int, dy: Int): Float {
        // Compare a (reference) with b shifted by (dx,dy); sample every 2nd pixel for speed, central 80% only.
        val x0 = a.w / 10; val x1 = a.w - a.w / 10; val y0 = a.h / 10; val y1 = a.h - a.h / 10
        var s = 0f; var n = 0
        var y = y0
        while (y < y1) {
            val by = y + dy
            if (by in 0 until b.h) {
                var x = x0
                while (x < x1) {
                    val bx = x + dx
                    if (bx in 0 until b.w) { s += abs(a.g[y * a.w + x] - b.g[by * b.w + bx]); n++ }
                    x += 2
                }
            }
            y += 2
        }
        return if (n == 0) Float.MAX_VALUE else s / n
    }

    /** Shift (in level-0 = 2-pixel units) that best maps frame onto reference. */
    fun findShift(ref: Pyramid, frame: Pyramid, coarseRange: Int = 8): Pair<Int, Int> {
        var dx = 0; var dy = 0
        val top = ref.levels.size - 1
        for (lvl in top downTo 0) {
            val a = ref.levels[lvl]; val b = frame.levels[lvl]
            val range = if (lvl == top) coarseRange else 1
            var best = Float.MAX_VALUE; var bx = dx; var by = dy
            for (ty in dy - range..dy + range) for (tx in dx - range..dx + range) {
                val v = sad(a, b, tx, ty)
                if (v < best) { best = v; bx = tx; by = ty }
            }
            dx = bx; dy = by
            if (lvl > 0) { dx *= 2; dy *= 2 }
        }
        return dx to dy
    }

    /**
     * Accumulate `frame` into `sum`/`count` after shifting by (sx, sy) sensor pixels (even numbers),
     * skipping 64x64 tiles whose mean abs difference to the reference exceeds the robust threshold.
     * Returns (tilesAccepted, tilesTotal).
     */
    fun accumulate(ref: ShortArray, frame: ShortArray, w: Int, h: Int, sx: Int, sy: Int, sum: IntArray, count: ShortArray, tile: Int = 64, rejectFactor: Float = 2.2f): Pair<Int, Int> {
        val tx = w / tile; val ty = h / tile
        val mad = FloatArray(tx * ty) { Float.MAX_VALUE }
        // Pass 1: per-tile mean abs difference (subsampled).
        for (j in 0 until ty) for (i in 0 until tx) {
            val x0 = i * tile; val y0 = j * tile
            if (x0 + sx < 0 || y0 + sy < 0 || x0 + tile + sx > w || y0 + tile + sy > h) continue
            var s = 0f; var n = 0
            var y = y0
            while (y < y0 + tile) {
                val ra = y * w; val rb = (y + sy) * w + sx
                var x = x0
                while (x < x0 + tile) { s += abs((ref[ra + x].toInt() and 0xFFFF) - (frame[rb + x].toInt() and 0xFFFF)); n++; x += 4 }
                y += 4
            }
            mad[j * tx + i] = s / n
        }
        val valid = mad.filter { it != Float.MAX_VALUE }.sorted()
        if (valid.isEmpty()) return 0 to tx * ty
        val median = valid[valid.size / 2]
        val threshold = median * rejectFactor + 2f
        // Pass 2: accumulate accepted tiles.
        var accepted = 0
        for (j in 0 until ty) for (i in 0 until tx) {
            val m = mad[j * tx + i]
            if (m == Float.MAX_VALUE || m > threshold) continue
            accepted++
            val x0 = i * tile; val y0 = j * tile
            for (y in y0 until y0 + tile) {
                val ra = y * w; val rb = (y + sy) * w + sx
                for (x in x0 until x0 + tile) sum[ra + x] += frame[rb + x].toInt() and 0xFFFF
            }
            count[j * tx + i]++
        }
        return accepted to tx * ty
    }

    /** Final average scaled x16; per-tile counts; tiles never accepted fall back to the reference. */
    fun finish(ref: ShortArray, sum: IntArray, count: ShortArray, w: Int, h: Int, tile: Int = 64): ShortArray {
        val tx = w / tile; val ty = h / tile
        val out = ShortArray(w * h)
        for (y in 0 until h) {
            val j = y / tile
            for (x in 0 until w) {
                val i = x / tile
                val n = if (i < tx && j < ty) count[j * tx + i].toInt() else 0
                val v = if (n > 0) (sum[y * w + x].toLong() * 16 + n / 2) / n else (ref[y * w + x].toInt() and 0xFFFF).toLong() * 16
                out[y * w + x] = v.coerceAtMost(65535).toInt().toShort()
            }
        }
        return out
    }
}
