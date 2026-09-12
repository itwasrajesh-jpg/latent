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

    private val pool = Executors.newSingleThreadExecutor { r -> Thread(r, "latent-develop").apply { priority = Thread.MIN_PRIORITY } }
    private val pending = AtomicInteger(0)

    /** Number of photos waiting or being developed right now. */
    val queued: Int get() = pending.get()

    @Volatile var onChanged: () -> Unit = {}
    @Volatile var onDeveloped: (Uri) -> Unit = {}

    fun submit(context: Context, job: Job) {
        pending.incrementAndGet(); onChanged()
        pool.execute {
            try {
                val out = if (job.isRaw) Develop.developDng(context.applicationContext, job.source, job.recipe)
                          else Develop.developJpeg(context.applicationContext, job.source, job.recipe)
                onDeveloped(out)
            } catch (t: Throwable) {
                Log.e("Latent", "develop failed for ${job.source}", t)
            } finally {
                pending.decrementAndGet(); onChanged()
            }
        }
    }
}
