package com.celestial.latent.develop

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import com.spectrafilm.engine.LinearImage
import com.spectrafilm.engine.SpektraEngine
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

    /**
     * A decoded image held in memory while the darkroom is open, so each edit re-renders
     * without decoding the RAW again. close() frees the native buffer.
     */
    class Source(val image: LinearImage, val width: Int, val height: Int) : AutoCloseable {
        /** Colour noise is cleaned once per decode, not once per render. */
        @Volatile var denoised = false
        override fun close() = image.close()
    }

    /**
     * Keeps the last couple of decoded images so returning to a photo is instant. Keyed by
     * source and cap; evicted oldest-first, and every evicted buffer is freed.
     */
    object Cache {
        private const val MAX = 2
        private val entries = LinkedHashMap<String, Source>()

        @Synchronized fun get(key: String): Source? = entries[key]

        @Synchronized fun put(key: String, src: Source) {
            entries[key] = src
            while (entries.size > MAX) {
                val oldest = entries.keys.first()
                entries.remove(oldest)?.close()
                Log.i("Latent", "source cache: evicted $oldest")
            }
        }

        @Synchronized fun clear() { entries.values.forEach { it.close() }; entries.clear() }
    }

    /**
     * The ISO a capture was taken at, or 0 when unknown. Read straight from the file's TIFF
     * header (tag 34855) rather than pulling in an EXIF library for one number.
     */
    fun isoOf(context: Context, uri: Uri): Int = try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val head = ByteArray(64 * 1024)
            val n = input.read(head)
            if (n < 16) 0 else readIsoTag(head, n)
        } ?: 0
    } catch (t: Throwable) { 0 }

    private fun readIsoTag(b: ByteArray, len: Int): Int {
        val little = b[0] == 'I'.code.toByte() && b[1] == 'I'.code.toByte()
        val big = b[0] == 'M'.code.toByte() && b[1] == 'M'.code.toByte()
        if (!little && !big) return 0
        fun u16(o: Int) = if (o + 1 >= len) 0 else
            if (little) (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
            else ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)
        fun u32(o: Int) = if (o + 3 >= len) 0 else
            if (little) (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
                ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)
            else ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
                ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)

        // Walk the first IFD, then the Exif IFD it points to, looking for tag 34855 (ISO).
        fun scan(off: Int, depth: Int): Int {
            if (off <= 0 || off + 2 > len || depth > 2) return 0
            val count = u16(off)
            var exifOff = 0
            for (i in 0 until count) {
                val e = off + 2 + i * 12
                if (e + 12 > len) break
                when (u16(e)) {
                    34855 -> return u16(e + 8)          // ISO, SHORT in place
                    34665 -> exifOff = u32(e + 8)       // Exif IFD pointer
                }
            }
            return if (exifOff > 0) scan(exifOff, depth + 1) else 0
        }
        return scan(u32(4), 0)
    }

    /** A cached decode: the same photo at the same size is decoded only once. */
    fun openCached(context: Context, source: Uri, isRaw: Boolean, maxEdge: Int, log: (String) -> Unit = {}): Source {
        val key = "$source@$maxEdge"
        Cache.get(key)?.let { log("using the decoded copy"); return it }
        val src = if (isRaw) openRaw(context, source, maxEdge, log) else openImage(context, source, maxEdge)
        Cache.put(key, src)
        return src
    }

    /**
     * Decode a RAW once, capped to [maxEdge] (0 = full size). Some DNGs ignore the decoder's
     * own cap, so the result is box-downsampled here when it comes back too large — otherwise
     * every later render silently does full-resolution work.
     */
    fun openRaw(context: Context, dng: Uri, maxEdge: Int = 0, log: (String) -> Unit = {}): Source {
        val t0 = System.nanoTime()
        val decoded = context.contentResolver.openFileDescriptor(dng, "r")?.use {
            RawDecoder.decodeToLinear(it.fd, RawDecoder.Settings(maxLongEdge = maxEdge))
        } ?: error("could not open $dng")
        val w = decoded.width; val h = decoded.height
        Log.i("Latent", "decode: ${w}x$h in ${(System.nanoTime() - t0) / 1_000_000} ms (asked for max $maxEdge)")
        log("decoded ${w}×$h")
        val longest = maxOf(w, h)
        if (maxEdge <= 0 || longest <= maxEdge) {
            return Source(LinearImage(decoded.data, w, h, colorSpace = decoded.colorSpace, onClose = { RawDecoder.freeOffHeap(it) }), w, h)
        }
        // The cap was ignored: shrink it ourselves, then free the big native buffer.
        var step = 1
        while (longest / step > maxEdge) step++
        val outW = (w + step - 1) / step; val outH = (h + step - 1) / step
        Log.i("Latent", "decoder ignored the cap; downsampling 1/$step to ${outW}x$outH")
        log("downsampling to ${outW}×$outH")
        val src = decoded.data.order(ByteOrder.nativeOrder()).asFloatBuffer()
        val out = ByteBuffer.allocateDirect(outW * outH * 3 * 4).order(ByteOrder.nativeOrder())
        val of = out.asFloatBuffer()
        val acc = FloatArray(3)
        for (y in 0 until outH) {
            for (x in 0 until outW) {
                acc[0] = 0f; acc[1] = 0f; acc[2] = 0f
                var n = 0
                for (dy in 0 until step) {
                    val sy = y * step + dy
                    if (sy >= h) break
                    for (dx in 0 until step) {
                        val sx = x * step + dx
                        if (sx >= w) break
                        val i = (sy * w + sx) * 3
                        acc[0] += src.get(i); acc[1] += src.get(i + 1); acc[2] += src.get(i + 2); n++
                    }
                }
                val inv = if (n > 0) 1f / n else 0f
                of.put(acc[0] * inv); of.put(acc[1] * inv); of.put(acc[2] * inv)
            }
        }
        RawDecoder.freeOffHeap(decoded.data)
        return Source(LinearImage(out, outW, outH, colorSpace = decoded.colorSpace), outW, outH)
    }

    /** Decode an already-processed image once, linearised for the engine. */
    fun openImage(context: Context, image: Uri, maxEdge: Int = 0): Source {
        val src = context.contentResolver.openInputStream(image)?.use { android.graphics.BitmapFactory.decodeStream(it) }
            ?: error("could not open $image")
        val scale = if (maxEdge > 0) minOf(1f, maxEdge.toFloat() / maxOf(src.width, src.height)) else 1f
        val bmp = if (scale < 1f) android.graphics.Bitmap.createScaledBitmap(src, (src.width * scale).toInt(), (src.height * scale).toInt(), true) else src
        val w = bmp.width; val h = bmp.height
        val buf = ByteBuffer.allocateDirect(w * h * 3 * 4).order(ByteOrder.nativeOrder())
        val f = buf.asFloatBuffer()
        val row = IntArray(w)
        // sRGB's curve removed, then sRGB primaries → ProPhoto primaries. The engine always
        // reads incoming pixels as linear ProPhoto regardless of the label, so this conversion
        // has to happen here; without it a JPEG source develops muted and slightly off-hue.
        val lut = FloatArray(256) { i ->
            val c = i / 255f
            if (c <= 0.04045f) c / 12.92f else Math.pow(((c + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
        }
        for (y in 0 until h) {
            bmp.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                val p = row[x]
                val r = lut[(p shr 16) and 0xFF]; val g = lut[(p shr 8) and 0xFF]; val b = lut[p and 0xFF]
                f.put(0.5294f * r + 0.3300f * g + 0.1406f * b)
                f.put(0.0983f * r + 0.8735f * g + 0.0282f * b)
                f.put(0.0168f * r + 0.1178f * g + 0.8654f * b)
            }
        }
        if (bmp !== src) bmp.recycle()
        src.recycle()
        return Source(LinearImage(buf, w, h, colorSpace = "ProPhoto RGB"), w, h)
    }

    /**
     * Render an already-decoded source. [preview] uses the engine's own downscaled fast path,
     * which is also the only path that honours the GPU preview flag.
     */
    @Volatile var cancelRequested = false

    /**
     * The per-pixel spatial stages: the costly ones. Dropped for the quick pass while a control
     * is moving, unless that control is one of them.
     */
    fun withoutSpatial(r: Recipe, keep: String?): Recipe = r.copy(
        grain = if (keep == "grain") r.grain else false,
        halation = if (keep == "halation") r.halation else false,
        diffusion = if (keep == "diffusion") r.diffusion else false,
        printDiffusion = if (keep == "diffusion") r.printDiffusion else false,
        glare = if (keep == "glare") r.glare else false,
    )

    /**
     * Cleans colour noise in place before a render. Applied once per decoded source: the film
     * should never see sensor blotches, because its dye couplers make them worse.
     */
    fun denoiseSource(source: Source, recipe: Recipe, iso: Int, log: (String) -> Unit = {}) {
        if (source.denoised) return
        val strength = if (recipe.chromaDenoise >= 0f) recipe.chromaDenoise else ChromaDenoise.strengthForIso(iso)
        if (strength > 0.001f) {
            log("cleaning colour noise")
            ChromaDenoise.apply(source.image.data, source.width, source.height, strength)
        }
        source.denoised = true
    }

    fun render(context: Context, source: Source, recipe: Recipe, preview: Boolean, log: (String) -> Unit = {}): Pair<ByteArray, Pair<Int, Int>> {
        val t = System.nanoTime()
        Log.i("Latent", "render start: source ${source.width}x${source.height}, preview=$preview, cap=${recipe.previewMaxSize}, film=${recipe.film}, gpuPreview=${preview && recipe.gpuPreview}")
        var dims = 0 to 0
        // GPU is preview-only: a full render always goes through the CPU engine.
        val params = sanitised(if (preview) recipe else recipe.copy(gpuPreview = false)).toParams()
        val jpeg = SpektraEngine.fromAssets(context.assets).use { engine ->
            val result = if (preview) engine.simulatePreview(source.image, params) else engine.simulate(source.image, params)
            result.use { r -> dims = r.width to r.height; toJpeg(r.data, r.width, r.height, r.colorSpace) }
        }
        Log.i("Latent", "render done: ${dims.first}x${dims.second} in ${(System.nanoTime() - t) / 1_000_000} ms")
        log((if (preview) "preview" else "full") + " ${dims.first}×${dims.second} in ${(System.nanoTime() - t) / 1_000_000} ms" +
            (if (preview && recipe.gpuPreview) " · GPU preview requested" else ""))
        return jpeg to dims
    }

    /**
     * Every FILMING profile the engine bundles. A printing profile (2383, Endura…) in the film
     * slot makes the engine fail with "internal error": each profile declares its stage.
     */
    val FILMS = listOf(
        "kodak_portra_400" to "Portra 400",
        "kodak_portra_160" to "Portra 160",
        "kodak_portra_800" to "Portra 800",
        "kodak_portra_800_push1" to "Portra 800 +1",
        "kodak_portra_800_push2" to "Portra 800 +2",
        "kodak_gold_200" to "Gold 200",
        "kodak_ultramax_400" to "Ultramax 400",
        "kodak_ektar_100" to "Ektar 100",
        "kodak_ektachrome_100" to "Ektachrome 100",
        "kodak_kodachrome_64" to "Kodachrome 64",
        "fujifilm_c200" to "C200",
        "fujifilm_xtra_400" to "X-Tra 400",
        "fujifilm_pro_400h" to "Pro 400H",
        "fujifilm_provia_100f" to "Provia 100F",
        "fujifilm_velvia_100" to "Velvia 100",
        "kodak_vision3_50d" to "Vision3 50D",
        "kodak_vision3_250d" to "Vision3 250D",
        "kodak_vision3_200t" to "Vision3 200T",
        "kodak_vision3_500t" to "Vision3 500T",
        "kodak_verita_200d" to "Verita 200D",
    )

    /** Every PRINTING profile: papers for stills, print stocks for the cine films. */
    val PAPERS = listOf(
        "kodak_portra_endura" to "Portra Endura",
        "kodak_supra_endura" to "Supra Endura",
        "kodak_ultra_endura" to "Ultra Endura",
        "kodak_endura_premier" to "Endura Premier",
        "kodak_ektacolor_edge" to "Ektacolor Edge",
        "fujifilm_crystal_archive_typeii" to "Crystal Archive II",
        "kodak_2383" to "Vision 2383 (cine print)",
        "kodak_2393" to "Vision Premier 2393",
    )

    const val DEFAULT_PAPER = "kodak_portra_endura"

    /**
     * The print stock each film was designed for, taken from the profiles' own `target_print`.
     * Slide films (null) have no print stage at all — they are scanned as positives.
     */
    private val TARGET_PRINT = mapOf(
        "kodak_portra_160" to "kodak_portra_endura",
        "kodak_portra_400" to "kodak_portra_endura",
        "kodak_portra_800" to "kodak_portra_endura",
        "kodak_portra_800_push1" to "kodak_portra_endura",
        "kodak_portra_800_push2" to "kodak_portra_endura",
        "kodak_gold_200" to "kodak_portra_endura",
        "kodak_ultramax_400" to "kodak_portra_endura",
        "kodak_ektar_100" to "kodak_portra_endura",
        "fujifilm_c200" to "fujifilm_crystal_archive_typeii",
        "fujifilm_pro_400h" to "fujifilm_crystal_archive_typeii",
        "fujifilm_xtra_400" to "fujifilm_crystal_archive_typeii",
        "kodak_vision3_50d" to "kodak_2383",
        "kodak_vision3_250d" to "kodak_2383",
        "kodak_vision3_200t" to "kodak_2383",
        "kodak_vision3_500t" to "kodak_2383",
        "kodak_verita_200d" to "kodak_2383",
        // Slide films: no print. Scanned directly as positives.
        "fujifilm_provia_100f" to null,
        "fujifilm_velvia_100" to null,
        "kodak_ektachrome_100" to null,
        "kodak_kodachrome_64" to null,
    )

    /** True for a slide film: there is no print stage, so the negative is scanned directly. */
    fun isSlideFilm(film: String) = TARGET_PRINT.containsKey(film) && TARGET_PRINT[film] == null

    /** The paper this film was meant to be printed on. */
    fun targetPrint(film: String): String? = TARGET_PRINT[film]

    /**
     * Sets the paper (and the direct-scan switch) to what the chosen film was designed for.
     * Called when the film changes, so the default pairing is always the authentic one.
     */
    fun pairedWithFilm(r: Recipe): Recipe {
        val target = TARGET_PRINT[r.film]
        return if (target == null && TARGET_PRINT.containsKey(r.film)) r.copy(scanFilm = true)
               else r.copy(paper = target ?: r.paper, scanFilm = false)
    }

    /** Guard against a saved recipe pointing at a profile in the wrong slot. */
    fun sanitised(r: Recipe): Recipe {
        val film = if (FILMS.any { it.first == r.film }) r.film else FILMS.first().first
        val paper = if (PAPERS.any { it.first == r.paper }) r.paper else DEFAULT_PAPER
        val fixed = if (film == r.film && paper == r.paper) r else r.copy(film = film, paper = paper)
        // A slide film has no print stage; a negative must not be scanned directly by accident.
        return if (isSlideFilm(fixed.film) && !fixed.scanFilm) fixed.copy(scanFilm = true) else fixed
    }

    fun availableProfiles(context: Context): List<String> =
        SpektraEngine.fromAssets(context.assets).use { it.listProfiles() }

    /**
     * Develop a RAW file.
     * @param maxEdge longest edge to decode; a small value for a quick look, 0 for full size.
     * Returns the saved image's uri. A new file is written every time; nothing is replaced.
     */
    /**
     * Develop a RAW file and save the result. One path for every caller — auto-develop, the
     * roll, and the darkroom's full-size button — so a fix here reaches all of them.
     */
    fun developDng(context: Context, dng: Uri, recipe: Recipe, maxEdge: Int = 0, log: (String) -> Unit = {}): Uri =
        developFull(context, dng, isRaw = true, recipe = recipe, maxEdge = maxEdge, log = log)

    /**
     * Develop an already-processed image (a JPEG from the Xiaomi modes, or an imported photo).
     * The engine expects linear light, so the file is decoded and linearised first. This is film
     * applied over someone else's rendering — a look rather than a simulation.
     */
    /** Film over an already-processed image (the Xiaomi modes, or an import). */
    fun developJpeg(context: Context, image: Uri, recipe: Recipe, maxEdge: Int = 0, log: (String) -> Unit = {}): Uri =
        developFull(context, image, isRaw = false, recipe = recipe, maxEdge = maxEdge, log = log)

    private fun baseNameOf(context: Context, uri: Uri): String {
        val name = context.contentResolver.query(uri, arrayOf(MediaStore.Images.Media.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "LATENT"
        return name.substringBeforeLast('.')
    }

    /** Developing again never replaces an earlier result: _2, _3 … are appended as needed. */
    private fun uniqueName(context: Context, name: String): String {
        val stem = name.substringBeforeLast('.'); val ext = name.substringAfterLast('.')
        var candidate = name; var n = 1
        while (exists(context, candidate)) { n++; candidate = "${stem}_$n.$ext" }
        return candidate
    }

    private fun exists(context: Context, name: String): Boolean =
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media._ID),
            "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ? AND ${MediaStore.Images.Media.DISPLAY_NAME} = ?",
            arrayOf("DCIM/Latent%", name), null,
        )?.use { it.count > 0 } ?: false

    /** Full-resolution develop with progress, logging and a new file each time. */
    fun developFull(context: Context, source: Uri, isRaw: Boolean, recipe: Recipe, maxEdge: Int = 0, log: (String) -> Unit = {}): Uri {
        log(if (maxEdge > 0) "decoding…" else "decoding at full size…")
        val src = if (isRaw) openRaw(context, source, maxEdge, log) else openImage(context, source, maxEdge)
        return src.use { s ->
            denoiseSource(s, recipe, isoOf(context, source), log)
            log("developing ${s.width}×${s.height}…")
            val (bytes, dims) = render(context, s, recipe, preview = false, log = log)
            log("saving ${dims.first}×${dims.second}, ${bytes.size / 1024} KB")
            saveDeveloped(context, bytes, source, recipe.film)
        }
    }

    /** A centre crop of the source, for showing the middle of the frame first. */
    fun centreCrop(src: Source, fraction: Float): Source {
        val f = fraction.coerceIn(0.2f, 1f)
        if (f >= 0.999f) return src
        val cw = (src.width * f).toInt().coerceAtLeast(8)
        val ch = (src.height * f).toInt().coerceAtLeast(8)
        val x0 = (src.width - cw) / 2
        val y0 = (src.height - ch) / 2
        val inBuf = src.image.data.order(ByteOrder.nativeOrder()).asFloatBuffer()
        val out = ByteBuffer.allocateDirect(cw * ch * 3 * 4).order(ByteOrder.nativeOrder())
        val of = out.asFloatBuffer()
        for (y in 0 until ch) {
            var i = ((y0 + y) * src.width + x0) * 3
            for (x in 0 until cw * 3) { of.put(inBuf.get(i)); i++ }
        }
        return Source(LinearImage(out, cw, ch, colorSpace = src.image.colorSpace), cw, ch)
    }

    /** The most recent developed JPEG for a capture, if there is one. */
    fun developedFor(context: Context, source: Uri): Uri? {
        val stem = baseNameOf(context, source)
        return context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME),
            "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ? AND ${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?",
            arrayOf("DCIM/Latent%", "$stem!_%.jpg"),
            "${MediaStore.Images.Media.DATE_ADDED} DESC",
        )?.use { c -> if (c.moveToFirst()) android.content.ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0)) else null }
    }

    fun saveDeveloped(context: Context, bytes: ByteArray, source: Uri, film: String): Uri =
        save(context, bytes, baseNameOf(context, source) + "_" + film.substringAfterLast('_') + ".jpg")

    private fun save(context: Context, bytes: ByteArray, name: String): Uri {
        val unique = uniqueName(context, name)
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, unique)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/Latent")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
        resolver.openOutputStream(uri)!!.use { it.write(bytes) }
        values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        Log.i("Latent", "developed file saved: $unique")
        return uri
    }
}
