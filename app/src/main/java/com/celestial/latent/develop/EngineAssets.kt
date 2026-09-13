package com.celestial.latent.develop

import android.content.Context
import android.util.Log
import java.io.File

/**
 * A copy of the engine's assets in the app's own storage, so Latent can add profiles of its own.
 *
 * The engine can start from the app's bundled assets or from a directory on disk. Bundled assets
 * are read-only, so a film we generate could never live among them. Copied once (about 13 MB)
 * into private storage, the same files become a folder we can add to — and a generated profile is
 * then loaded exactly like a measured one, in the camera, the darkroom and everywhere else.
 *
 * The copy is byte-for-byte, so nothing about developing changes when it is in use.
 */
object EngineAssets {

    private const val ROOT = "spektra"

    /** Where the copy lives, or null if it has not been made yet. */
    @Volatile var directory: String? = null
        private set

    /**
     * Picks up an existing copy without making one.
     *
     * The directory is only known once something has asked, and nothing asked at startup — so on
     * a fresh launch the films Latent had built were invisible in the strip, and developing with
     * one would quietly fall back to a bundled stock. Cheap: one check for a marker file.
     */
    fun attach(context: Context): String? {
        directory?.let { return it }
        val dir = File(context.filesDir, ROOT)
        if (File(dir, ".complete").exists()) {
            directory = dir.absolutePath
            Log.i("Latent", "engine assets already present at ${dir.absolutePath}")
        }
        return directory
    }

    fun isReady(context: Context): Boolean {
        directory?.let { return true }
        val dir = File(context.filesDir, ROOT)
        val marker = File(dir, ".complete")
        if (marker.exists()) { directory = dir.absolutePath; return true }
        return false
    }

    /**
     * Copies the assets if they are not there already. Slow the first time (a few seconds) and
     * instant afterwards. Safe to call repeatedly; call it off the main thread.
     */
    fun prepare(context: Context, onProgress: (String) -> Unit = {}): String? {
        if (isReady(context)) return directory
        val dir = File(context.filesDir, ROOT)
        val marker = File(dir, ".complete")
        return try {
            if (dir.exists()) dir.deleteRecursively()
            dir.mkdirs()
            var files = 0
            fun copy(assetPath: String, into: File) {
                val children = context.assets.list(assetPath).orEmpty()
                if (children.isEmpty()) {
                    // A leaf: copy the file.
                    into.parentFile?.mkdirs()
                    context.assets.open(assetPath).use { input ->
                        into.outputStream().use { output -> input.copyTo(output) }
                    }
                    files++
                    if (files % 25 == 0) onProgress("copying the engine's films ($files)")
                } else {
                    children.forEach { child -> copy("$assetPath/$child", File(into, child)) }
                }
            }
            onProgress("copying the engine's films")
            copy(ROOT, dir)
            marker.writeText("ok")
            directory = dir.absolutePath
            Log.i("Latent", "engine assets copied: $files files to ${dir.absolutePath}")
            onProgress("ready")
            directory
        } catch (t: Throwable) {
            Log.e("Latent", "could not copy the engine's assets", t)
            runCatching { dir.deleteRecursively() }
            onProgress("could not prepare the engine")
            null
        }
    }

    /** Where a generated profile is written so the engine can find it. */
    fun profileFile(stockId: String): File? =
        directory?.let { File(File(it, "profiles"), "$stockId.json") }

    /** The stocks Latent has made, newest first. */
    fun ourStocks(): List<String> {
        val dir = directory?.let { File(it, "profiles") } ?: return emptyList()
        return dir.listFiles { f -> f.name.startsWith("celestial_") && f.name.endsWith(".json") }
            .orEmpty()
            .sortedByDescending { it.lastModified() }
            .map { it.name.removeSuffix(".json") }
    }
}
