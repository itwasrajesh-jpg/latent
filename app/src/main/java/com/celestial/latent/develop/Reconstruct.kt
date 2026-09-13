package com.celestial.latent.develop

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import com.spectrafilm.engine.SpektraEngine
import kotlin.math.abs
import kotlin.random.Random

/**
 * Builds an emulsion to match a set of reference images.
 *
 * Nothing here chooses between the bundled films. It moves the numbers that define a film —
 * where each layer's response sits, how much density it builds, how gradually, how sensitive it
 * is across the spectrum — writes them out as a profile, develops the test photo through the
 * engine with it, measures the result, and keeps what gets closer.
 *
 * The search is a simple hill climb with restarts: at this size each attempt costs a fraction of
 * a second, so hundreds are affordable, and a simple method that can be watched and stopped is
 * better than a clever one that cannot.
 */
object Reconstruct {

    data class Attempt(val shape: Emulsion.Shape, val distance: Float, val jpeg: ByteArray?)

    data class Progress(val tried: Int, val total: Int, val best: Attempt?, val note: String)

    @Volatile var cancelled = false

    /**
     * @param target what the references averaged to.
     * @param testShot one of the user's own photos: the blank sheet every attempt is developed on.
     */
    fun run(
        context: Context,
        target: Fingerprint,
        testShot: Uri,
        isRaw: Boolean,
        baseStock: String,
        rounds: Int = 240,
        onProgress: (Progress) -> Unit,
    ): Attempt? {
        cancelled = false
        val dir = EngineAssets.prepare(context) { onProgress(Progress(0, rounds, null, it)) } ?: return null
        val base = Emulsion.baseProfile(context, baseStock) ?: return null
        val stockId = "celestial_working"

        // The photo is decoded once and reused: only the film changes between attempts.
        val source = runCatching {
            if (isRaw) Develop.openRaw(context, testShot, 320) else Develop.openImage(context, testShot, 320)
        }.getOrNull() ?: return null

        val holdsLaneEarly = DevelopQueue.engineLane.tryAcquire()
        var best: Attempt? = null
        var current = Emulsion.Shape()
        var currentDistance = Float.MAX_VALUE
        var temperature = 1f
        val random = Random(1)

        fun evaluate(shape: Emulsion.Shape, keepImage: Boolean): Attempt? {
            Emulsion.write(base, shape, stockId, "Working") ?: return null
            return runCatching {
                val recipe = Recipe(
                    film = stockId,
                    scanFilm = true,          // no print stage: the emulsion itself is what is being judged
                    grain = false, halation = false, glare = false, diffusion = false,
                    previewMaxSize = 320,
                    autoExposure = true,      // each attempt is levelled, so brightness is not what is matched
                )
                // A fresh engine each attempt: the profile changes between them, and an engine
                // that had already read the old one would keep using it. Creating one reads the
                // profile folder, which is why attempts are capped and the work stays small.
                SpektraEngine(dir).use { engine ->
                    val (bytes, _) = Develop.renderWith(engine, context, source, recipe, preview = true)
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@runCatching null
                    val fp = Fingerprint.of(bmp)
                    Attempt(shape, fp.distanceTo(target), if (keepImage) bytes else null)
                }
            }.getOrNull()
        }

        val start = evaluate(current, keepImage = true)
        if (start == null) {
            // If the very first attempt fails, every other one will fail the same way: stop and
            // say so rather than repeating the same error hundreds of times.
            onProgress(Progress(0, rounds, null, "could not build a film from ${baseStock.replace('_', ' ')}"))
            if (holdsLaneEarly) DevelopQueue.engineLane.release()
            source.close()
            return null
        }
        best = start; currentDistance = start.distance
        onProgress(Progress(0, rounds, best, "starting from ${baseStock.replace('_', ' ')}"))

        var tried = 0
        val holdsLane = holdsLaneEarly
        while (tried < rounds && !cancelled) {
            // One number at a time, by a step that shrinks as the search settles.
            val v = current.asArray().copyOf()
            val which = random.nextInt(Emulsion.Shape.COUNT)
            val step = Emulsion.Shape.STEP[which] * temperature
            v[which] += if (random.nextBoolean()) step else -step
            val candidate = Emulsion.Shape.from(v)

            val attempt = evaluate(candidate, keepImage = false)
            tried++
            if (attempt != null && attempt.distance < currentDistance) {
                current = candidate
                currentDistance = attempt.distance
                if (best == null || attempt.distance < best!!.distance) {
                    // Re-run the winner keeping its picture, so the screen can show it.
                    best = evaluate(candidate, keepImage = true) ?: attempt
                }
            }
            // Settle gradually; a few larger jumps early, finer adjustments later.
            temperature = (1f - tried.toFloat() / rounds).coerceAtLeast(0.15f)
            if (tried % 8 == 0) {
                onProgress(Progress(tried, rounds, best, "closest so far: ${percent(best?.distance)}"))
            }
        }
        if (holdsLane) DevelopQueue.engineLane.release()
        source.close()
        // The working profile is scratch: saving writes its own file under a chosen name.
        runCatching { EngineAssets.profileFile(stockId)?.delete() }
        onProgress(Progress(tried, rounds, best, if (cancelled) "stopped" else "finished"))
        Log.i("Latent", "reconstruction finished after $tried attempts, distance ${best?.distance}")
        return best
    }

    fun percent(distance: Float?): String =
        if (distance == null) "—" else "${((1f - distance) * 100).toInt().coerceIn(0, 100)}%"

    /** Saves the working emulsion under a name of the user's choosing. */
    fun save(context: Context, shape: Emulsion.Shape, baseStock: String, name: String): String? {
        val base = Emulsion.baseProfile(context, baseStock) ?: return null
        val id = "celestial_" + name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
            .ifBlank { "stock_" + System.currentTimeMillis() / 1000 }
        return Emulsion.write(base, shape, id, name)
    }
}
