@file:OptIn(ExperimentalFoundationApi::class)

package com.celestial.latent

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.celestial.latent.develop.Develop
import com.celestial.latent.develop.Recipe
import com.celestial.latent.develop.Recipes
import com.celestial.latent.ui.LatentColors
import kotlinx.coroutines.delay

private const val PREVIEW_EDGE = 1400

/**
 * The darkroom: a developed preview of one capture plus the controls that shape it.
 * Simple shows eight; Full opens every group the engine exposes.
 */
@Composable
fun DarkroomScreen(source: Uri, isRaw: Boolean, initial: Recipe, onRecipeChanged: (Recipe) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    var recipe by remember { mutableStateOf(initial) }
    var full by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var original by remember { mutableStateOf<Bitmap?>(null) }
    var comparing by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var rendering by remember { mutableStateOf(false) }
    var pendingAt by remember { mutableStateOf(0L) }
    var open by remember { mutableStateOf("film") }
    var saveName by remember { mutableStateOf("") }

    fun render() {
        if (rendering) { pendingAt = System.currentTimeMillis(); return }
        rendering = true
        val r = recipe
        Thread {
            try {
                val t = System.nanoTime()
                val (bytes, dims) = if (isRaw) Develop.developDngTo(context, source, r, PREVIEW_EDGE)
                                    else Develop.developJpegTo(context, source, r, PREVIEW_EDGE)
                preview = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                status = "preview ${dims.first}×${dims.second} · ${(System.nanoTime() - t) / 1_000_000} ms"
            } catch (t: Throwable) { status = "failed: ${t.message}" }
            rendering = false
            if (pendingAt > 0) { pendingAt = 0; render() }
        }.start()
    }

    LaunchedEffect(Unit) { render() }
    // Coalesce slider movement: re-render shortly after the last change.
    LaunchedEffect(recipe) { delay(350); onRecipeChanged(recipe); Recipes.setCurrent(context, recipe); render() }

    fun set(block: Recipe.() -> Recipe) { recipe = recipe.block() }

    Column(Modifier.fillMaxSize().background(LatentColors.Background).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("‹", color = LatentColors.Text, fontSize = 22.sp, modifier = Modifier.combinedClickable(onClick = onBack).padding(horizontal = 6.dp))
            Text("DARKROOM", color = LatentColors.Text, fontSize = 12.sp, letterSpacing = 4.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(false to "SIMPLE", true to "FULL").forEach { (v, label) ->
                    val on = v == full
                    Text(label, color = if (on) LatentColors.AmberInk else LatentColors.TextDim, fontSize = 10.sp, letterSpacing = 1.5.sp,
                        modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(if (on) LatentColors.Amber else Color.Transparent)
                            .combinedClickable(onClick = { full = v }).padding(horizontal = 9.dp, vertical = 3.dp))
                }
            }
        }

        Box(Modifier.fillMaxWidth().aspectRatio(3f / 4f).background(LatentColors.Surface).pointerInput(Unit) {
            detectTapGestures(onPress = {
                comparing = true
                if (original == null) Thread {
                    original = runCatching { context.contentResolver.loadThumbnail(source, android.util.Size(1200, 1200), null) }.getOrNull()
                }.start()
                tryAwaitRelease(); comparing = false
            })
        }) {
            val shown = if (comparing) (original ?: preview) else preview
            shown?.let { Image(it.asImageBitmap(), contentDescription = "Developed", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
            if (rendering) Text("DEVELOPING…", color = LatentColors.Amber, fontSize = 10.sp, letterSpacing = 2.sp, modifier = Modifier.align(Alignment.TopEnd).padding(10.dp))
            Text(if (comparing) "ORIGINAL" else (Develop.FILMS.firstOrNull { it.first == recipe.film }?.second?.uppercase() ?: recipe.film), color = Color(0xCCFFFFFF), fontSize = 10.sp, letterSpacing = 1.sp,
                modifier = Modifier.align(Alignment.TopStart).padding(10.dp))
            Text("HOLD TO COMPARE", color = Color(0x99FFFFFF), fontSize = 9.sp, letterSpacing = 1.sp, modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp))
        }

        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Develop.FILMS.forEach { (id, label) ->
                val on = id == recipe.film
                Text(label.uppercase(), color = if (on) LatentColors.AmberInk else LatentColors.TextBright, fontSize = 11.sp, letterSpacing = 1.sp,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(if (on) LatentColors.Amber else LatentColors.Surface)
                        .combinedClickable(onClick = { Haptics.tick(context); set { copy(film = id) } }).padding(horizontal = 12.dp, vertical = 7.dp))
            }
        }

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
            if (!full) {
                S("Exposure", recipe.exposureEv, -3f, 3f, "%+.1f EV") { set { copy(exposureEv = it) } }
                S("Push / pull", recipe.pushStops, -2f, 3f, "%+.1f stop") { set { copy(pushStops = it) } }
                S("Contrast", recipe.filmContrast, 0.6f, 1.6f, "%.2f") { set { copy(filmContrast = it) } }
                S("Grain", recipe.grainSizeUm2, 0.05f, 1.2f, "%.2f µm²", recipe.grain) { set { copy(grainSizeUm2 = it) } }
                S("Halation", recipe.halationAmount, 0f, 3f, "%.2f", recipe.halation) { set { copy(halationAmount = it) } }
                S("Diffusion", recipe.diffusionStrength, 0f, 1f, "%.2f", recipe.diffusion) { set { copy(diffusionStrength = it, diffusion = it > 0.01f) } }
                S("Print exposure", recipe.printExposure, 0.4f, 2.2f, "%.2f") { set { copy(printExposure = it) } }
                S("Sharpening", recipe.unsharpAmount, 0f, 2f, "%.2f") { set { copy(unsharpAmount = it) } }
            } else {
                Group("FILM & EXPOSURE", open == "film", { open = if (open == "film") "" else "film" }) {
                    S("Exposure", recipe.exposureEv, -3f, 3f, "%+.1f EV") { set { copy(exposureEv = it) } }
                    S("Push / pull", recipe.pushStops, -2f, 3f, "%+.1f stop") { set { copy(pushStops = it) } }
                    S("Film contrast (density gamma)", recipe.filmContrast, 0.6f, 1.6f, "%.2f") { set { copy(filmContrast = it) } }
                }
                Group("HALATION", open == "hal", { open = if (open == "hal") "" else "hal" }, recipe.halation, { set { copy(halation = it) } }) {
                    S("Amount", recipe.halationAmount, 0f, 3f, "%.2f") { set { copy(halationAmount = it) } }
                    S("Spread", recipe.halationScale, 0.2f, 3f, "%.2f") { set { copy(halationScale = it) } }
                    S("Scatter", recipe.scatterAmount, 0f, 3f, "%.2f") { set { copy(scatterAmount = it) } }
                    S("Highlight boost", recipe.halationBoostEv, -2f, 4f, "%+.1f EV") { set { copy(halationBoostEv = it) } }
                }
                Group("GRAIN", open == "grain", { open = if (open == "grain") "" else "grain" }, recipe.grain, { set { copy(grain = it) } }) {
                    S("Particle size", recipe.grainSizeUm2, 0.05f, 1.2f, "%.2f µm²") { set { copy(grainSizeUm2 = it) } }
                    S("Softness", recipe.grainBlur, 0f, 2f, "%.2f") { set { copy(grainBlur = it) } }
                    Toggle("Sublayers", recipe.grainSublayers) { set { copy(grainSublayers = it) } }
                }
                Group("DIFFUSION FILTER", open == "diff", { open = if (open == "diff") "" else "diff" }, recipe.diffusion, { set { copy(diffusion = it) } }) {
                    Chips(listOf("black_pro_mist", "pro_mist", "glimmerglass", "hollywood_black_magic"), recipe.diffusionFamily) { set { copy(diffusionFamily = it) } }
                    S("Strength", recipe.diffusionStrength, 0f, 1f, "%.2f") { set { copy(diffusionStrength = it) } }
                    S("Halo", recipe.diffusionHalo, 0f, 3f, "%.2f") { set { copy(diffusionHalo = it) } }
                    S("Bloom", recipe.diffusionBloom, 0f, 3f, "%.2f") { set { copy(diffusionBloom = it) } }
                    S("Halo warmth", recipe.diffusionWarmth, -1f, 1f, "%+.2f") { set { copy(diffusionWarmth = it) } }
                }
                Group("ENLARGER & PAPER", open == "print", { open = if (open == "print") "" else "print" }) {
                    S("Print exposure", recipe.printExposure, 0.4f, 2.2f, "%.2f") { set { copy(printExposure = it) } }
                    S("Yellow filter", recipe.yFilterShift, -20f, 20f, "%+.0f") { set { copy(yFilterShift = it) } }
                    S("Magenta filter", recipe.mFilterShift, -20f, 20f, "%+.0f") { set { copy(mFilterShift = it) } }
                    S("Pre-flash", recipe.preflash, 0f, 0.5f, "%.2f") { set { copy(preflash = it) } }
                    S("Paper contrast", recipe.printContrast, 0.6f, 1.6f, "%.2f") { set { copy(printContrast = it) } }
                }
                Group("SCANNER", open == "scan", { open = if (open == "scan") "" else "scan" }) {
                    S("Sharpening amount", recipe.unsharpAmount, 0f, 2f, "%.2f") { set { copy(unsharpAmount = it) } }
                    S("Sharpening radius", recipe.unsharpRadius, 0.2f, 2f, "%.2f") { set { copy(unsharpRadius = it) } }
                    Toggle("White correction", recipe.whiteCorrection) { set { copy(whiteCorrection = it) } }
                    Toggle("Black correction", recipe.blackCorrection) { set { copy(blackCorrection = it) } }
                }
                Group("GLARE", open == "glare", { open = if (open == "glare") "" else "glare" }, recipe.glare, { set { copy(glare = it) } }) {
                    S("Amount", recipe.glarePercent, 0f, 0.2f, "%.3f") { set { copy(glarePercent = it) } }
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(status, color = LatentColors.TextDim, fontSize = 10.sp)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Reset", color = LatentColors.Text, fontSize = 12.sp,
                    modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(LatentColors.Surface)
                        .combinedClickable(onClick = { recipe = Recipe(film = recipe.film, paper = recipe.paper) }).padding(horizontal = 14.dp, vertical = 8.dp))
                Text("Save as recipe", color = LatentColors.Text, fontSize = 12.sp,
                    modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(LatentColors.Surface)
                        .combinedClickable(onClick = {
                            val n = (Develop.FILMS.firstOrNull { it.first == recipe.film }?.second ?: "Recipe") + " " + (Recipes.names(context).size + 1)
                            Recipes.save(context, n, recipe); saveName = n
                        }).padding(horizontal = 14.dp, vertical = 8.dp))
            }
            if (saveName.isNotEmpty()) Text("saved as “$saveName”", color = LatentColors.Amber, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
            Spacer(Modifier.height(16.dp))
        }

        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(if (isRaw) "FROM RAW" else "FILM OVER JPEG", color = LatentColors.Line, fontSize = 9.sp, letterSpacing = 1.5.sp)
            Text("Develop full size", color = LatentColors.AmberInk, fontSize = 12.sp,
                modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(LatentColors.Amber).combinedClickable(onClick = {
                    Haptics.click(context)
                    status = "developing at full size…"
                    val r = recipe
                    Thread {
                        try {
                            val out = if (isRaw) Develop.developDng(context, source, r) else Develop.developJpeg(context, source, r)
                            status = "saved"
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW).apply { setDataAndType(out, "image/jpeg"); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }) }
                        } catch (t: Throwable) { status = "failed: ${t.message}" }
                    }.start()
                }).padding(horizontal = 16.dp, vertical = 9.dp))
        }
    }
}

@Composable
private fun S(label: String, value: Float, min: Float, max: Float, fmt: String, enabled: Boolean = true, onChange: (Float) -> Unit) {
    val context = LocalContext.current
    var last by remember { mutableStateOf(value) }
    Column(Modifier.padding(vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = if (enabled) LatentColors.Text else LatentColors.Line, fontSize = 11.sp)
            Text(String.format(fmt, value), color = if (enabled) LatentColors.TextBright else LatentColors.Line, fontSize = 11.sp)
        }
        Slider(
            value = value.coerceIn(min, max), onValueChange = { v ->
                if (Math.abs(v - last) > (max - min) / 40f) { Haptics.tick(context); last = v }
                onChange(v)
            },
            valueRange = min..max, enabled = enabled,
            colors = SliderDefaults.colors(thumbColor = LatentColors.Amber, activeTrackColor = LatentColors.Amber, inactiveTrackColor = LatentColors.Line),
        )
    }
}

@Composable
private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth().combinedClickable(onClick = { Haptics.tick(context); onChange(!value) }).padding(vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = LatentColors.Text, fontSize = 11.sp)
        Text(if (value) "ON" else "OFF", color = if (value) LatentColors.Amber else LatentColors.Line, fontSize = 10.sp, letterSpacing = 1.sp)
    }
}

@Composable
private fun Chips(options: List<String>, selected: String, onSelect: (String) -> Unit) {
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { o ->
            val on = o == selected
            Text(o.replace('_', ' '), color = if (on) LatentColors.AmberInk else LatentColors.Text, fontSize = 10.sp,
                modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(if (on) LatentColors.Amber else LatentColors.Surface)
                    .combinedClickable(onClick = { Haptics.tick(context); onSelect(o) }).padding(horizontal = 10.dp, vertical = 5.dp))
        }
    }
}

@Composable
private fun Group(title: String, expanded: Boolean, onToggle: () -> Unit, active: Boolean? = null, onActive: ((Boolean) -> Unit)? = null, content: @Composable () -> Unit) {
    val context = LocalContext.current
    Column {
        Row(Modifier.fillMaxWidth().combinedClickable(onClick = { Haptics.tick(context); onToggle() }).padding(vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = LatentColors.TextBright, fontSize = 12.sp, letterSpacing = 1.5.sp)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (active != null && onActive != null) Text(if (active) "ON" else "OFF", color = if (active) LatentColors.Amber else LatentColors.Line, fontSize = 10.sp,
                    modifier = Modifier.combinedClickable(onClick = { Haptics.tick(context); onActive(!active) }))
                Text(if (expanded) "︿" else "﹀", color = LatentColors.TextDim, fontSize = 14.sp)
            }
        }
        if (expanded) content()
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(LatentColors.Surface))
    }
}
