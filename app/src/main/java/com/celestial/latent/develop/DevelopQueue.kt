package com.celestial.latent.develop

import android.content.Context
import android.net.Uri
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Develops captures in the background, one at a time, so a shot never blocks the shutter.
 * Single shots only — a 16-frame burst is not auto-developed.
 */
object DevelopQueue {

    data class Job(val source: Uri, val recipe: Recipe, val isRaw: Boolean)

    /**
     * Full-resolution develop, run on the same single thread as auto-develop so two heavy
     * engine runs can never overlap — overlapping them makes both crawl.
     */
    fun submitFull(context: Context, source: Uri, isRaw: Boolean, recipe: Recipe,
                   onStatus: (String) -> Unit, onDone: (Uri?) -> Unit) {
        pending.incrementAndGet(); onChanged()
        onStatus("queued")
        pool.execute {
            val app = context.applicationContext
            var out: Uri? = null
            try {
                onStatus("starting")
                out = Develop.developFull(app, source, isRaw, recipe) { m -> onStatus(m) }
            } catch (t: Throwable) {
                Log.e("Latent", "full develop failed", t); onStatus("failed: ${t.message}")
            } finally {
                pending.decrementAndGet(); onChanged(); onDone(out)
            }
        }
    }

    private val pool = Executors.newSingleThreadExecutor { r -> Thread(r, "latent-develop").apply { priority = Thread.MIN_PRIORITY } }

    /**
     * Only one engine may run at a time: each instance loads the film data and allocates a
     * full-resolution buffer, so two at once compete for memory and stall. The darkroom takes
     * this while it renders a preview; background developing waits its turn.
     */
    val engineLane = java.util.concurrent.Semaphore(1, true)

    /**
     * Waits for the lane, but never forever: a lost permit must not stop developing for good.
     * Returns true if it was acquired (and so must be released).
     */
    fun acquireLane(waitSeconds: Long = 45): Boolean = try {
        val got = engineLane.tryAcquire(waitSeconds, java.util.concurrent.TimeUnit.SECONDS)
        if (!got) android.util.Log.w("Latent", "engine lane not free after ${waitSeconds}s; running anyway")
        got
    } catch (t: InterruptedException) { false }
    private val pending = AtomicInteger(0)

    /** Number of photos waiting or being developed right now. */
    val queued: Int get() = pending.get()

    @Volatile var onChanged: () -> Unit = {}
    @Volatile var onDeveloped: (Uri) -> Unit = {}

    fun submit(context: Context, job: Job) {
        pending.incrementAndGet(); onChanged()
        pool.execute {
            // Same single thread as full-size work, so the lane is only about the darkroom preview.
            val holdsLane = acquireLane(120)
            try {
                val out = if (job.isRaw) Develop.developDng(context.applicationContext, job.source, job.recipe)
                          else Develop.developJpeg(context.applicationContext, job.source, job.recipe)
                onDeveloped(out)
            } catch (t: Throwable) {
                Log.e("Latent", "develop failed for ${job.source}", t)
            } finally {
                if (holdsLane) engineLane.release()
                pending.decrementAndGet(); onChanged()
            }
        }
    }
}
