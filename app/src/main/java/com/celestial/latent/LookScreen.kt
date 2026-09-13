package com.celestial.latent

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.celestial.latent.develop.Develop
import com.celestial.latent.develop.Fingerprint
import com.celestial.latent.ui.LatentColors
import kotlin.math.abs

/**
 * The look builder's first half: measuring.
 *
 * Before anything can be searched for, the measurement has to agree with the eye. This screen
 * shows a look's fingerprint for a set of reference images and, beside it, the same figures for
 * the app's own films — developed from one of your photos. If the films separate here the way
 * they separate on screen, the search is worth building. If they do not, no amount of searching
 * would help, and better to know now.
 */
/** The emulsion every build starts from: a neutral, well-behaved colour negative. */
private const val BASE_STOCK = "kodak_portra_400"

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LookScreen(settings: AppSettings, onBack: () -> Unit) {
    val context = LocalContext.current
    var references by remember { mutableStateOf<List<Pair<Uri, Fingerprint>>>(emptyList()) }
    var textures by remember { mutableStateOf<List<com.celestial.latent.develop.Texture>>(emptyList()) }
    var thumbs by remember { mutableStateOf<Map<Uri, Bitmap>>(emptyMap()) }
    var testShot by remember { mutableStateOf<Uri?>(null) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    fun thumbnailOf(uri: Uri): Bitmap? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
            BitmapFactory.decodeStream(input, null, opts)
        }
    }.getOrNull()

    /**
     * A piece of the reference at its original resolution. Grain is fine detail and does not
     * survive the downscaling used for everything else, so it has to be measured here.
     */
    fun grainCropOf(uri: Uri): Bitmap? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val decoder = android.graphics.BitmapRegionDecoder.newInstance(input, false)
            val side = minOf(decoder.width, decoder.height, 512)
            val left = (decoder.width - side) / 2
            val top = (decoder.height - side) / 2
            decoder.decodeRegion(android.graphics.Rect(left, top, left + side, top + side), null)
                .also { decoder.recycle() }
        }
    }.getOrNull()

    val pickRefs = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNullOrEmpty()) return@rememberLauncherForActivityResult
        busy = true
        Thread {
            val added = ArrayList<Pair<Uri, Fingerprint>>()
            val addedTextures = ArrayList<com.celestial.latent.develop.Texture>()
            val maps = HashMap<Uri, Bitmap>()
            uris.forEach { uri ->
                runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                thumbnailOf(uri)?.let { bmp ->
                    added += uri to Fingerprint.of(bmp)
                    // Grain, halation, bloom and glare are read off the picture rather than
                    // searched for: they leave signatures a measurement can find directly.
                    val crop = grainCropOf(uri)
                    addedTextures += com.celestial.latent.develop.Texture.of(bmp, crop)
                    crop?.recycle()
                    maps[uri] = bmp
                }
            }
            references = references + added
            textures = textures + addedTextures
            thumbs = thumbs + maps
            status = "${references.size} references"
            busy = false
        }.start()
    }

    var testIsRaw by remember { mutableStateOf(true) }
    val pickTest = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        // A JPEG works as a test shot too; it just has to be decoded differently.
        val name = (runCatching {
            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull() ?: uri.lastPathSegment).orEmpty()
        testIsRaw = name.endsWith(".dng", true) || name.endsWith(".raw", true) || name.endsWith(".arw", true)
        testShot = uri
        status = if (testIsRaw) "test shot chosen (RAW)" else "test shot chosen (JPEG)"
    }

    var progress by remember { mutableStateOf<com.celestial.latent.develop.Reconstruct.Progress?>(null) }
    var result by remember { mutableStateOf<com.celestial.latent.develop.Reconstruct.Attempt?>(null) }
    var resultBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var saved by remember { mutableStateOf("") }

    /** Builds an emulsion to match the references, on the test shot. */
    fun reconstruct(target: Fingerprint) {
        val src = testShot ?: return
        busy = true
        result = null
        resultBitmap = null
        saved = ""
        Thread {
            val best = com.celestial.latent.develop.Reconstruct.run(
                context = context,
                target = target,
                testShot = src,
                isRaw = testIsRaw,
                baseStock = BASE_STOCK,
            ) { p ->
                progress = p
                p.best?.jpeg?.let { bytes ->
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { resultBitmap = it }
                }
            }
            result = best
            busy = false
        }.start()
    }

    val target = if (references.isEmpty()) null else Fingerprint.average(references.map { it.second })
    val spread = if (references.size > 1) Fingerprint.spread(references.map { it.second }) else 0f

    Column(
        Modifier.fillMaxSize().background(LatentColors.Background)
            .statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("LOOK BUILDER", color = LatentColors.Text, fontSize = 12.sp, letterSpacing = 3.sp)
            Text("‹", color = LatentColors.Text, fontSize = 20.sp, modifier = Modifier.combinedClickable(onClick = onBack).padding(horizontal = 8.dp))
        }

        Section("1 · REFERENCES")
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            references.take(3).forEach { (uri, _) ->
                thumbs[uri]?.let { bmp ->
                    Image(
                        bmp.asImageBitmap(), null, contentScale = ContentScale.Crop,
                        modifier = Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(4.dp)),
                    )
                }
            }
            repeat((3 - references.size).coerceAtLeast(0)) { Box(Modifier.weight(1f).aspectRatio(1f)) }
            Box(
                Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(4.dp)).background(LatentColors.Surface)
                    .combinedClickable(onClick = { Haptics.tick(context); pickRefs.launch(arrayOf("image/*")) }),
                contentAlignment = Alignment.Center,
            ) { Text("+", color = LatentColors.TextDim, fontSize = 18.sp) }
        }
        Text(
            if (references.isEmpty()) "images in the look you want — a dozen is plenty"
            else "${references.size} images · they disagree by ${"%.2f".format(spread)}" +
                (if (spread > 0.25f) " — quite a scattered set" else ""),
            color = LatentColors.TextDim, fontSize = 11.sp, modifier = Modifier.padding(bottom = 18.dp),
        )

        val texture = if (textures.isEmpty()) null else com.celestial.latent.develop.Texture.average(textures)
        texture?.let { t ->
            Section("READ FROM THEM")
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(LatentColors.Surface).padding(13.dp)) {
                Reading("grain", (if (t.grainFineness > 0.55f) "fine · " else "coarse · ") + "%.2f".format(t.grainAmount))
                Reading("halation", (if (t.halationAmount > 0.8f) "strong · " else "gentle · ") + "%.2f".format(t.halationAmount))
                Reading("bloom", t.bloomFamily.replace('_', ' ') + " · " + "%.2f".format(t.bloomAmount))
                Reading("veiling glare", "%.1f%%".format(t.glarePercent))
                Text(
                    "measured from the references, not guessed — the fit below changes only the emulsion's colour and tone",
                    color = LatentColors.Line, fontSize = 10.sp, modifier = Modifier.padding(top = 8.dp),
                )
            }
            Spacer(Modifier.height(20.dp))
        }

        Section("2 · TEST SHOT")
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill(if (testShot == null) "choose a photo of yours" else "chosen") { pickTest.launch(arrayOf("image/*", "image/x-adobe-dng", "application/octet-stream")) }
        }
        Text("RAW or JPEG — a blank sheet to try each emulsion on. Nothing is written to it.", color = LatentColors.TextDim, fontSize = 11.sp, modifier = Modifier.padding(bottom = 18.dp))

        if (status.isNotEmpty()) Text(status, color = LatentColors.Amber, fontSize = 11.sp, modifier = Modifier.padding(bottom = 14.dp))

        val canRun = target != null && testShot != null && !busy
        Row(Modifier.fillMaxWidth().padding(bottom = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (busy) {
                Pill("stop") { com.celestial.latent.develop.Reconstruct.cancelled = true }
            } else {
                Pill(if (result == null) "build the film" else "build again", accent = canRun) {
                    if (canRun) reconstruct(target!!)
                }
            }
        }

        progress?.let { p ->
            Section("3 · BUILDING")
            Box(Modifier.fillMaxWidth().height(2.dp).background(LatentColors.Surface)) {
                Box(
                    Modifier.fillMaxWidth(if (p.total == 0) 0f else (p.tried.toFloat() / p.total).coerceIn(0f, 1f))
                        .height(2.dp).background(LatentColors.Amber),
                )
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(p.note, color = LatentColors.TextDim, fontSize = 11.sp)
                Text("${p.tried} of ${p.total}", color = LatentColors.Amber, fontSize = 11.sp)
            }

            // Reference beside the closest attempt, so the eye can judge as it goes.
            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val ref = references.firstOrNull()?.let { thumbs[it.first] }
                if (ref != null) {
                    Image(ref.asImageBitmap(), null, contentScale = ContentScale.Crop,
                        modifier = Modifier.weight(1f).aspectRatio(3f / 4f).clip(RoundedCornerShape(6.dp)))
                } else Box(Modifier.weight(1f).aspectRatio(3f / 4f))
                val made = resultBitmap
                if (made != null) {
                    Image(made.asImageBitmap(), null, contentScale = ContentScale.Crop,
                        modifier = Modifier.weight(1f).aspectRatio(3f / 4f).clip(RoundedCornerShape(6.dp)))
                } else Box(Modifier.weight(1f).aspectRatio(3f / 4f).clip(RoundedCornerShape(6.dp)).background(LatentColors.Surface))
            }
            Row(Modifier.fillMaxWidth().padding(bottom = 18.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("REFERENCE", color = LatentColors.TextDim, fontSize = 10.sp, letterSpacing = 1.sp)
                Text(
                    "CLOSEST · " + com.celestial.latent.develop.Reconstruct.percent(p.best?.distance),
                    color = LatentColors.TextDim, fontSize = 10.sp, letterSpacing = 1.sp,
                )
            }
        }

        result?.let { best ->
            Section("THE EMULSION")
            val s = best.shape
            listOf(
                "layer speed" to s.speed,
                "where each layer responds" to s.centre,
                "density built" to s.height,
                "how gradually" to s.width,
                "spectral shift (nm)" to s.spectralShift,
            ).forEach { (label, v) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(label, color = LatentColors.TextDim, fontSize = 11.sp)
                    Text(
                        v.joinToString("  ") { "%+.2f".format(it) },
                        color = LatentColors.Text, fontSize = 11.sp,
                    )
                }
            }
            Text(
                "red  green  blue — a film that did not exist until now, built from " + BASE_STOCK.replace('_', ' ') + "'s form",
                color = LatentColors.TextDim, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp, bottom = 14.dp),
            )

            Row(Modifier.fillMaxWidth().padding(bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("save as a stock", accent = true) {
                    val name = "Celestial " + (references.size * 7 % 90 + 10)
                    val id = com.celestial.latent.develop.Reconstruct.save(context, best.shape, BASE_STOCK, name)
                    saved = if (id == null) "could not save" else {
                        // The stock carries what was read from the references: its own grain,
                        // halation, bloom and glare, not the defaults.
                        var recipe = com.celestial.latent.develop.Recipe(film = id)
                        texture?.let { recipe = it.applyTo(recipe) }
                        com.celestial.latent.develop.Recipes.save(context, name, recipe)
                        "saved as $name — it is in the film strip"
                    }
                }
            }
            if (saved.isNotEmpty()) Text(saved, color = LatentColors.Amber, fontSize = 11.sp, modifier = Modifier.padding(bottom = 20.dp))
        }
    }
}

@Composable
private fun Reading(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = LatentColors.TextDim, fontSize = 11.sp)
        Text(value, color = LatentColors.Text, fontSize = 11.sp)
    }
}

@Composable
private fun Section(title: String) {
    Text(title, color = LatentColors.TextDim, fontSize = 10.sp, letterSpacing = 2.sp, modifier = Modifier.padding(bottom = 8.dp))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Pill(label: String, accent: Boolean = false, onClick: () -> Unit) {
    val context = LocalContext.current
    Text(
        label,
        color = if (accent) LatentColors.AmberInk else LatentColors.Text, fontSize = 12.sp,
        modifier = Modifier.clip(RoundedCornerShape(999.dp))
            .background(if (accent) LatentColors.Amber else LatentColors.Surface)
            .combinedClickable(onClick = { Haptics.tick(context); onClick() })
            .padding(horizontal = 14.dp, vertical = 9.dp),
    )
}
