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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.celestial.latent.develop.Presets
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
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LookScreen(settings: AppSettings, onBack: () -> Unit) {
    val context = LocalContext.current
    var references by remember { mutableStateOf<List<Pair<Uri, Fingerprint>>>(emptyList()) }
    var thumbs by remember { mutableStateOf<Map<Uri, Bitmap>>(emptyMap()) }
    var testShot by remember { mutableStateOf<Uri?>(null) }
    var films by remember { mutableStateOf<List<Pair<String, Fingerprint>>>(emptyList()) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    fun thumbnailOf(uri: Uri): Bitmap? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
            BitmapFactory.decodeStream(input, null, opts)
        }
    }.getOrNull()

    val pickRefs = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNullOrEmpty()) return@rememberLauncherForActivityResult
        busy = true
        Thread {
            val added = ArrayList<Pair<Uri, Fingerprint>>()
            val maps = HashMap<Uri, Bitmap>()
            uris.forEach { uri ->
                runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                thumbnailOf(uri)?.let { bmp ->
                    added += uri to Fingerprint.of(bmp)
                    maps[uri] = bmp
                }
            }
            references = references + added
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

    /** Develops the test shot with each look and measures the result. */
    fun measureFilms() {
        val src = testShot ?: return
        busy = true
        Thread {
            val out = ArrayList<Pair<String, Fingerprint>>()
            val looks = Presets.all(context).take(8)
            val isRaw = testIsRaw
            looks.forEachIndexed { index, preset ->
                status = "developing ${preset.name.substringBefore(" — ")} (${index + 1}/${looks.size})"
                runCatching {
                    val recipe = preset.recipe.copy(previewMaxSize = 420, grain = false, halation = false, glare = false, diffusion = false)
                    val source = Develop.openCached(context, src, isRaw, 640, recipe, Develop.isoOf(context, src))
                    val (bytes, _) = Develop.render(context, source, recipe, preview = true)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { bmp ->
                        out += preset.name.substringBefore(" — ") to Fingerprint.of(bmp)
                    }
                }.onFailure { status = "could not develop with ${preset.name}" }
            }
            films = out
            status = "measured ${out.size} films"
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

        Section("2 · TEST SHOT")
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill(if (testShot == null) "choose one of your RAWs" else "chosen") { pickTest.launch(arrayOf("image/*", "image/x-adobe-dng", "application/octet-stream")) }
            if (testShot != null && !busy) Pill("measure the films", accent = true) { measureFilms() }
        }
        Text("your own photo — the films are developed from it to be measured", color = LatentColors.TextDim, fontSize = 11.sp, modifier = Modifier.padding(bottom = 18.dp))

        if (status.isNotEmpty()) Text(status, color = LatentColors.Amber, fontSize = 11.sp, modifier = Modifier.padding(bottom = 14.dp))

        if (films.isNotEmpty()) {
            Section("3 · DO THE NUMBERS SEPARATE THE FILMS?")
            films.forEach { (name, fp) ->
                val d = target?.let { fp.distanceTo(it) }
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(name, color = LatentColors.Text, fontSize = 12.sp)
                    Text(
                        if (d == null) "—" else "${((1f - d) * 100).toInt().coerceIn(0, 100)}%",
                        color = if (d != null && d < 0.25f) LatentColors.Amber else LatentColors.TextDim, fontSize = 12.sp,
                    )
                }
            }
            Text(
                if (target == null) "add references to see how close each film is"
                else "how close each film comes to the references",
                color = LatentColors.TextDim, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp, bottom = 18.dp),
            )

            Section("THE FIGURES")
            val first = films.firstOrNull()?.second
            val last = films.lastOrNull()?.second
            if (first != null && last != null) {
                Fingerprint.LABELS.forEachIndexed { i, label ->
                    val a = first.asList()[i]; val b = last.asList()[i]
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(label, color = LatentColors.TextDim, fontSize = 11.sp)
                        Text(
                            "${"%+.2f".format(a)}   ${"%+.2f".format(b)}",
                            color = if (abs(a - b) > 0.08f) LatentColors.Text else LatentColors.Line,
                            fontSize = 11.sp,
                        )
                    }
                }
                Text(
                    "${films.first().first} against ${films.last().first} — lit figures are where they differ",
                    color = LatentColors.TextDim, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
                )
            }
        }
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
