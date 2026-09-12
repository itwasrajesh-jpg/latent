package com.celestial.latent.develop

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import com.spectrafilm.engine.CameraParams
import com.spectrafilm.engine.LinearImage
import com.spectrafilm.engine.SpektraEngine
import com.spectrafilm.engine.SpektraParams
import com.spectrafilm.libraw.RawDecoder
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Develops a captured DNG through the spektrafilm engine and saves a JPEG beside it.
 *
 * Engine and RAW decoder are GPLv3 / LGPL modules fetched at build time from the pinned
 * mirror — see NOTICE.md. This file is only the glue: decode → simulate → encode → save.
 */
object Develop {

    /** Films worth putting in front of a person first; the engine bundles many more. */
    val FILMS = listOf(
        "kodak_portra_400" to "Portra 400",
        "kodak_portra_160" to "Portra 160",
        "kodak_portra_800" to "Portra 800",
        "kodak_gold_200" to "Gold 200",
        "kodak_ektar_100" to "Ektar 100",
        "fujifilm_pro_400h" to "Pro 400H",
        "fujifilm_c200" to "C200",
        "kodak_2383" to "Kodak 2383 (cine print)",
    )
    const val DEFAULT_PAPER = "kodak_portra_endura"

    fun availableProfiles(context: Context): List<String> =
        SpektraEngine.fromAssets(context.assets).use { it.listProfiles() }

    /**
     * @param maxEdge longest edge to decode; use a small value for a quick look, 0 for full size.
     * Returns the saved image's uri.
     */
    fun developDng(context: Context, dng: Uri, film: String, paper: String = DEFAULT_PAPER, maxEdge: Int = 0, log: (String) -> Unit = {}): Uri {
        val t0 = System.nanoTime()
        log("decoding RAW…")
        val settings = RawDecoder.Settings(maxLongEdge = maxEdge)
        val decoded = context.contentResolver.openFileDescriptor(dng, "r")?.use {
            RawDecoder.decodeToLinear(it.fd, settings)
        } ?: error("could not open $dng")
        val decodeMs = (System.nanoTime() - t0) / 1_000_000
        log("decoded ${decoded.width}x${decoded.height} in ${decodeMs} ms · developing…")

        val image = LinearImage(decoded.data, decoded.width, decoded.height, colorSpace = decoded.colorSpace,
            onClose = { RawDecoder.freeOffHeap(it) })
        val t1 = System.nanoTime()
        val jpeg = image.use { img ->
            SpektraEngine.fromAssets(context.assets).use { engine ->
                val params = SpektraParams(filmProfile = film, printProfile = paper, camera = CameraParams(autoExposure = true))
                engine.simulate(img, params).use { result -> toJpeg(result.data, result.width, result.height) }
            }
        }
        val devMs = (System.nanoTime() - t1) / 1_000_000
        log("developed in ${devMs} ms · saving ${jpeg.size / 1024} KB")
        return save(context, jpeg, baseNameOf(context, dng) + "_" + film.substringAfterLast('_') + ".jpg")
    }

    /** Engine output is 8-bit RGB in the chosen output space; wrap it as a Bitmap and encode. */
    private fun toJpeg(data: ByteBuffer, w: Int, h: Int): ByteArray {
        val buf = data.order(ByteOrder.nativeOrder())
        val pixels = IntArray(w * h)
        buf.rewind()
        for (i in 0 until w * h) {
            val r = buf.get().toInt() and 0xFF
            val g = buf.get().toInt() and 0xFF
            val b = buf.get().toInt() and 0xFF
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        val bmp = android.graphics.Bitmap.createBitmap(pixels, w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val out = ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, out)
        bmp.recycle()
        return out.toByteArray()
    }

    private fun baseNameOf(context: Context, uri: Uri): String {
        val name = context.contentResolver.query(uri, arrayOf(MediaStore.Images.Media.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "LATENT"
        return name.substringBeforeLast('.')
    }

    private fun save(context: Context, bytes: ByteArray, name: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/Latent")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
        resolver.openOutputStream(uri)!!.use { it.write(bytes) }
        values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        Log.i("Latent", "developed file saved: $name")
        return uri
    }
}
