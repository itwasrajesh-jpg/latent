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
                engine.simulate(img, params).use { result -> toJpeg(result.data, result.width, result.height, result.colorSpace) }
            }
        }
        val devMs = (System.nanoTime() - t1) / 1_000_000
        log("developed in ${devMs} ms · saving ${jpeg.size / 1024} KB")
        return save(context, jpeg, baseNameOf(context, dng) + "_" + film.substringAfterLast('_') + ".jpg")
    }

    /**
     * The engine returns display-referred FLOAT RGB (three floats per pixel, 0..1) in its output
     * colour space — not bytes. Clamp, quantise to 8 bit, and tag the bitmap with that space so
     * the system colour-manages it and embeds the right profile on export.
     */
    private fun toJpeg(data: ByteBuffer, w: Int, h: Int, colorSpace: com.spectrafilm.engine.ColorSpace): ByteArray {
        val f = data.order(ByteOrder.nativeOrder()).asFloatBuffer()
        val bmp = taggedBitmap(w, h, colorSpace)
        val bandRows = (1024 * 1024 / w).coerceIn(1, h)
        val strip = IntArray(w * bandRows)
        var y = 0
        while (y < h) {
            val rows = minOf(bandRows, h - y)
            var k = 0
            var i = y * w * 3
            repeat(w * rows) {
                val r = (f.get(i).coerceIn(0f, 1f) * 255f + 0.5f).toInt()
                val g = (f.get(i + 1).coerceIn(0f, 1f) * 255f + 0.5f).toInt()
                val b = (f.get(i + 2).coerceIn(0f, 1f) * 255f + 0.5f).toInt()
                strip[k++] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                i += 3
            }
            bmp.setPixels(strip, 0, w, 0, y, w, rows)
            y += rows
        }
        val out = ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, out)
        bmp.recycle()
        return out.toByteArray()
    }

    /** Bitmap tagged with the engine's output colour space, falling back to sRGB. */
    private fun taggedBitmap(w: Int, h: Int, cs: com.spectrafilm.engine.ColorSpace): android.graphics.Bitmap {
        val named = when (cs) {
            com.spectrafilm.engine.ColorSpace.SRGB -> android.graphics.ColorSpace.Named.SRGB
            com.spectrafilm.engine.ColorSpace.ADOBE_RGB -> android.graphics.ColorSpace.Named.ADOBE_RGB
            com.spectrafilm.engine.ColorSpace.PROPHOTO -> android.graphics.ColorSpace.Named.PRO_PHOTO_RGB
            com.spectrafilm.engine.ColorSpace.REC2020 -> android.graphics.ColorSpace.Named.BT2020
            com.spectrafilm.engine.ColorSpace.LINEAR_SRGB -> android.graphics.ColorSpace.Named.LINEAR_SRGB
            else -> android.graphics.ColorSpace.Named.SRGB
        }
        return runCatching {
            android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888, false, android.graphics.ColorSpace.get(named))
        }.getOrElse { android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888) }
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
