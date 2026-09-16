package com.celestial.latent

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checking for, fetching and installing a new build from the project's own releases.
 *
 * Latent is not on a store, so updating means going to GitHub, finding the newest release and
 * downloading its APK by hand. This does the same thing from inside the app: it asks the
 * releases API what the latest version is, compares it with the running one, downloads the
 * attached APK, and hands it to Android's installer.
 *
 * Nothing is installed silently: Android shows its own confirmation, and the app needs the
 * user's permission to install packages at all the first time.
 */
object Updater {

    private const val API = "https://api.github.com/repos/itwasrajesh-jpg/latent/releases/latest"

    data class Release(val version: String, val notes: String, val apkUrl: String, val sizeBytes: Long)

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data class UpToDate(val version: String) : State
        /** The release exists but its tag cannot be compared with the running version. */
        data class Unclear(val tag: String, val current: String) : State
        data class Available(val release: Release) : State
        data class Downloading(val id: Long) : State
        data class ReadyToInstall(val id: Long) : State
        data class Failed(val reason: String) : State
    }

    /** The version this build reports, as set by the build workflow. */
    fun currentVersion(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"
    }.getOrDefault("0")

    /**
     * Asks the releases API for the newest build. Returns null if there is nothing newer, and
     * throws nothing — a failed check is reported, not fatal.
     */
    fun check(context: Context): State {
        var conn: HttpURLConnection? = null
        return try {
        val c = (URL(API).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "Latent")
        }
        conn = c
        val body = c.inputStream.bufferedReader().use { it.readText() }
        val json = JSONObject(body)
        val tag = json.optString("tag_name").ifBlank { json.optString("name") }
        val version = tag.trimStart('v', 'V')
        val assets = json.optJSONArray("assets")
        var url = ""
        var size = 0L
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                    url = a.optString("browser_download_url")
                    size = a.optLong("size")
                    break
                }
            }
        }
        val current = currentVersion(context)
        Log.i("Latent", "update check: latest release '$tag' (version '$version'), installed '$current'")
        when {
            url.isBlank() -> State.Failed("the newest release has no APK attached")
            isNewer(version, current) -> State.Available(Release(version, json.optString("body"), url, size))
            !comparable(version, current) -> State.Unclear(tag, current)
            else -> State.UpToDate(current)
        }
        } catch (t: Throwable) {
            Log.e("Latent", "update check failed", t)
            State.Failed(t.message ?: "could not reach GitHub")
        } finally {
            // Closed whichever way the check ends, rather than only when it succeeds.
            conn?.disconnect()
        }
    }

    /**
     * Compares two version strings a piece at a time, so 0.1.100 is correctly newer than
     * 0.1.99 — comparing them as text would say otherwise.
     */
    /**
     * The build workflow tags a release with just the build number — "v110" — while the app
     * reports "0.1.110": the same number, last. So a single-number tag is compared with the
     * last part of the version. Tags in full dotted form are compared part by part. Anything
     * else cannot be compared honestly and is reported rather than guessed at.
     */
    fun isNewer(candidate: String, current: String): Boolean {
        val a = parts(candidate)
        val b = parts(current)
        if (a.isEmpty() || b.isEmpty()) return false
        if (a.size == 1 && b.size > 1) return a[0] > b.last()
        if (a.size != b.size) {
            Log.w("Latent", "update: cannot compare '$candidate' with '$current' — different forms")
            return false
        }
        for (i in a.indices) {
            if (a[i] != b[i]) return a[i] > b[i]
        }
        return false
    }

    /** True when the two forms can be compared at all — see [isNewer]. */
    fun comparable(candidate: String, current: String): Boolean {
        val a = parts(candidate); val b = parts(current)
        return a.isNotEmpty() && b.isNotEmpty() && (a.size == b.size || (a.size == 1 && b.size > 1))
    }

    private fun parts(v: String): List<Int> =
        v.trim().trimStart('v', 'V').split('.', '-', '_')
            .mapNotNull { p -> p.takeWhile { it.isDigit() }.toIntOrNull() }

    /**
     * Hands the APK to Android's own download manager, which shows its progress in the
     * notification shade and keeps the file in Downloads.
     *
     * Deliberately not downloaded by this app and installed by this app: doing that needs the
     * permission to install packages, and Play Protect treats any app holding it as a risk —
     * insisting on a scan at every install. Installing from Downloads is the same install,
     * without that permission.
     *
     * @return the download id, or null if it could not be queued.
     */
    fun download(context: Context, release: Release): Long? = try {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val name = "latent-${release.version}.apk"
        val request = DownloadManager.Request(Uri.parse(release.apkUrl))
            .setTitle("Latent ${release.version}")
            .setDescription("Tap when finished to install")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
        dm.enqueue(request)
    } catch (t: Throwable) {
        Log.e("Latent", "could not queue the update download", t); null
    }

    /** Whether a queued download has finished, failed, or is still going. */
    fun downloadStatus(context: Context, id: Long): Int {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        dm.query(DownloadManager.Query().setFilterById(id)).use { c ->
            if (!c.moveToFirst()) return DownloadManager.STATUS_FAILED
            return c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
        }
    }

    /** Opens Android's Downloads, where a tap on the APK brings up the system installer. */
    fun openDownloads(context: Context) {
        context.startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
