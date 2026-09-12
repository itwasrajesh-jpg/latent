@file:OptIn(ExperimentalFoundationApi::class)

package com.celestial.latent

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.DisposableEffect
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

private const val COARSE_EDGE = 420     // while a control is moving
private const val FINE_EDGE = 800       // once it settles
private const val DECODE_EDGE = 1200    // the RAW is decoded once at this size for the darkroom

private val TABS = listOf(
    "film" to "FILM", "halation" to "HALATION", "grain" to "GRAIN", "diffusion" to "DIFFUSION",
    "camera" to "CAMERA", "enlarger" to "ENLARGER", "scanner" to "SCANNER", "glare" to "GLARE",
    "colour" to "COLOUR", "engine" to "ENGINE",
)

/** The four optical diffusion filters the engine models. */
private val DIFFUSION_FAMILIES = listOf("glimmerglass", "black_pro_mist", "pro_mist", "cinebloom")

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
    var src by remember { mutableStateOf<Develop.Source?>(null) }
    var coarse by remember { mutableStateOf(false) }
    var fullRunning by remember { mutableStateOf(false) }
    var previewIsPartial by remember { mutableStateOf(false) }
    var gpuTest by remember { mutableStateOf("") }
    var tab by remember { mutableStateOf("film") }
    var sheet by remember { mutableStateOf(0) }   // 0 peek, 1 half, 2 full
    var saveName by remember { mutableStateOf("") }

    fun render(fast: Boolean) {
        if (rendering) { pendingAt = System.currentTimeMillis(); return }
        rendering = true
        // While a control moves: smaller, centred, and without the costly spatial stages —
        // except the one being edited, which has to stay visible.
        val base = recipe.copy(previewMaxSize = if (fast) COARSE_EDGE else FINE_EDGE)
        val r = if (fast) Develop.withoutSpatial(base, keep = tab) else base
        val cropFraction = if (fast) 0.7f else 1f
        Thread {
            val q = com.celestial.latent.develop.DevelopQueue
            val holdsLane = if (q.engineLane.tryAcquire()) true else {
                status = "waiting for the background develop…"
                q.acquireLane(20)
            }
            var cropped: Develop.Source? = null
            try {
                if (src == null) {
                    status = "decoding…"
                    src = Develop.openCached(context, source, isRaw, DECODE_EDGE) { m -> status = m }
                    status = "decoded ${src!!.width}×${src!!.height}"
                }
                // Middle of the frame first on the quick pass: it appears sooner and reads the same.
                val target = if (cropFraction < 1f) Develop.centreCrop(src!!, cropFraction).also { cropped = it } else src!!
                val (bytes, _) = Develop.render(context, target, r, preview = true) { m -> status = m }
                preview = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                previewIsPartial = cropFraction < 1f
            } catch (t: Throwable) { status = "failed: ${t.message}" }
            finally {
                if (cropped !== null && cropped !== src) cropped?.close()
                if (holdsLane) q.engineLane.release()
            }
            rendering = false
            if (pendingAt > 0) { pendingAt = 0; render(fast) }
        }.start()
    }

    // First render, then re-render shortly after the last control change.
    // If this capture was already developed, show that straight away instead of a blank wait.
    LaunchedEffect(source) {
        if (preview == null) {
            val existing = runCatching { Develop.developedFor(context, source) }.getOrNull()
            if (existing != null) {
                val b = runCatching { context.contentResolver.loadThumbnail(existing, android.util.Size(1200, 1200), null) }.getOrNull()
                if (b != null && preview == null) { preview = b; status = "already developed · change anything to re-render" }
            }
        }
    }

    // A quick coarse pass while a control is moving, then a fine one when it settles.
    LaunchedEffect(recipe) {
        coarse = true
        render(fast = true)
        delay(450)
        coarse = false
        onRecipeChanged(recipe); Recipes.setCurrent(context, recipe)
        render(fast = false)
    }
    // The decoded copy is kept by Develop.Cache so coming back is instant; nothing to free here.

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

        // Photo shrinks as the sheet is dragged up; it never disappears entirely.
        // The sheet always keeps room; dragging shifts how much.
        val photoWeight = when (sheet) { 0 -> 0.58f; 1 -> 0.38f; else -> 0.18f }
        Box(Modifier.fillMaxWidth().weight(photoWeight).background(LatentColors.Surface).pointerInput(Unit) {
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
            if (previewIsPartial && !rendering) Text("QUICK PASS · CENTRE ONLY", color = Color(0x99FFFFFF), fontSize = 9.sp, letterSpacing = 1.sp, modifier = Modifier.align(Alignment.BottomStart).padding(10.dp))
            if (!rendering && preview == null) Text(if (status.isEmpty()) "no preview yet" else status, color = LatentColors.Text, fontSize = 11.sp, modifier = Modifier.align(Alignment.Center).padding(24.dp))
            Text(if (comparing) "ORIGINAL" else (Develop.FILMS.firstOrNull { it.first == recipe.film }?.second?.uppercase() ?: recipe.film),
                color = Color(0xCCFFFFFF), fontSize = 10.sp, letterSpacing = 1.sp, modifier = Modifier.align(Alignment.TopStart).padding(10.dp))
            if (sheet == 0) Text("HOLD TO COMPARE", color = Color(0x99FFFFFF), fontSize = 9.sp, letterSpacing = 1.sp, modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp))
        }

        // The sheet: drag the handle to give the controls more room.
        Column(
            Modifier.fillMaxWidth().weight(1f - photoWeight).clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)).background(Color(0xFF1D1D1B))
                .pointerInput(Unit) {
                    detectVerticalDragGestures { _, dy ->
                        if (dy < -12f && sheet < 2) { Haptics.tick(context); sheet++ }
                        if (dy > 12f && sheet > 0) { Haptics.tick(context); sheet-- }
                    }
                },
        ) {
            Box(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 6.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(34.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(if (sheet > 0) LatentColors.Amber else LatentColors.Line))
            }
            // Film chips: always reachable, whatever tab is open.
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Develop.FILMS.forEach { (id, label) ->
                    val on = id == recipe.film
                    Text(label.uppercase(), color = if (on) LatentColors.AmberInk else LatentColors.TextBright, fontSize = 10.sp, letterSpacing = 1.sp,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(if (on) LatentColors.Amber else LatentColors.Surface)
                            .combinedClickable(onClick = { Haptics.tick(context); set { copy(film = id) } }).padding(horizontal = 11.dp, vertical = 7.dp))
                }
            }
            // Tabs
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TABS.forEach { (id, label) ->
                    val on = id == tab
                    Text(label, color = if (on) LatentColors.AmberInk else LatentColors.Text, fontSize = 9.sp, letterSpacing = 1.sp,
                        modifier = Modifier.clip(RoundedCornerShape(999.dp))
                            .background(if (on) LatentColors.Amber else Color.Transparent)
                            .then(if (on) Modifier else Modifier.border(0.5.dp, LatentColors.Line, RoundedCornerShape(999.dp)))
                            .combinedClickable(onClick = { Haptics.tick(context); tab = id }).padding(horizontal = 9.dp, vertical = 5.dp))
                }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
                when (tab) {
                    "film" -> {
                        S("Exposure", recipe.exposureEv, -3f, 3f, "%+.1f EV") { set { copy(exposureEv = it) } }
                        S("Push / pull", recipe.pushStops, -2f, 3f, "%+.1f stop") { set { copy(pushStops = it) } }
                        S("Film contrast", recipe.filmContrast, 0.6f, 1.6f, "%.2f") { set { copy(filmContrast = it) } }
                        Head("DIR COUPLERS", recipe.dir) { set { copy(dir = it) } }
                        S("Amount", recipe.dirAmount, 0f, 2f, "%.2f", recipe.dir) { set { copy(dirAmount = it) } }
                        S("Same-layer inhibition", recipe.dirSameLayer, 0f, 2f, "%.2f", recipe.dir) { set { copy(dirSameLayer = it) } }
                        S("Inter-layer inhibition", recipe.dirInterLayer, 0f, 2f, "%.2f", recipe.dir) { set { copy(dirInterLayer = it) } }
                        S("Diffusion size", recipe.dirDiffusionUm, 2f, 80f, "%.0f µm", recipe.dir) { set { copy(dirDiffusionUm = it) } }
                    }
                    "halation" -> {
                        Head("HALATION", recipe.halation) { set { copy(halation = it) } }
                        S("Amount", recipe.halationAmount, 0f, 3f, "%.2f", recipe.halation) { set { copy(halationAmount = it) } }
                        S("Spread", recipe.halationScale, 0.2f, 3f, "%.2f", recipe.halation) { set { copy(halationScale = it) } }
                        S("Scatter", recipe.scatterAmount, 0f, 3f, "%.2f", recipe.halation) { set { copy(scatterAmount = it) } }
                        S("Highlight boost", recipe.halationBoostEv, -2f, 4f, "%+.1f EV", recipe.halation) { set { copy(halationBoostEv = it) } }
                        S("Protect highlights", recipe.halationProtectEv, 0f, 8f, "%.1f EV", recipe.halation) { set { copy(halationProtectEv = it) } }
                        S("Bounces", recipe.halationBounces.toFloat(), 1f, 6f, "%.0f", recipe.halation) { set { copy(halationBounces = Math.round(it)) } }
                        S("Bounce decay", recipe.halationDecay, 0.1f, 0.9f, "%.2f", recipe.halation) { set { copy(halationDecay = it) } }
                    }
                    "grain" -> {
                        Head("GRAIN", recipe.grain) { set { copy(grain = it) } }
                        S("Particle size", recipe.grainSizeUm2, 0.05f, 1.2f, "%.2f µm²", recipe.grain) { set { copy(grainSizeUm2 = it) } }
                        S("Softness", recipe.grainBlur, 0f, 2f, "%.2f", recipe.grain) { set { copy(grainBlur = it) } }
                        S("Dye-cloud blur", recipe.grainDyeCloudUm, 0f, 4f, "%.2f µm", recipe.grain) { set { copy(grainDyeCloudUm = it) } }
                        S("Micro-structure", recipe.grainMicroAmount, 0f, 1f, "%.2f", recipe.grain) { set { copy(grainMicroAmount = it) } }
                        S("Micro scale", recipe.grainMicroScale, 5f, 80f, "%.0f", recipe.grain) { set { copy(grainMicroScale = it) } }
                        Toggle("Sublayers", recipe.grainSublayers) { set { copy(grainSublayers = it) } }
                        S("Sublayer count", recipe.grainSublayerCount.toFloat(), 1f, 4f, "%.0f", recipe.grainSublayers) { set { copy(grainSublayerCount = Math.round(it)) } }
                    }
                    "diffusion" -> {
                        Head("LENS FILTER", recipe.diffusion) { set { copy(diffusion = it) } }
                        Chips(DIFFUSION_FAMILIES, recipe.diffusionFamily) { set { copy(diffusionFamily = it) } }
                        S("Strength", recipe.diffusionStrength, 0f, 1f, "%.2f", recipe.diffusion) { set { copy(diffusionStrength = it) } }
                        S("Spatial scale", recipe.diffusionScale, 0.2f, 3f, "%.2f", recipe.diffusion) { set { copy(diffusionScale = it) } }
                        S("Core intensity", recipe.diffusionCore, 0f, 3f, "%.2f", recipe.diffusion) { set { copy(diffusionCore = it) } }
                        S("Core size", recipe.diffusionCoreSize, 0.2f, 3f, "%.2f", recipe.diffusion) { set { copy(diffusionCoreSize = it) } }
                        S("Halo intensity", recipe.diffusionHalo, 0f, 3f, "%.2f", recipe.diffusion) { set { copy(diffusionHalo = it) } }
                        S("Halo size", recipe.diffusionHaloSize, 0.2f, 3f, "%.2f", recipe.diffusion) { set { copy(diffusionHaloSize = it) } }
                        S("Bloom intensity", recipe.diffusionBloom, 0f, 3f, "%.2f", recipe.diffusion) { set { copy(diffusionBloom = it) } }
                        S("Bloom size", recipe.diffusionBloomSize, 0.2f, 3f, "%.2f", recipe.diffusion) { set { copy(diffusionBloomSize = it) } }
                        S("Halo warmth", recipe.diffusionWarmth, -1f, 1f, "%+.2f", recipe.diffusion) { set { copy(diffusionWarmth = it) } }
                        Head("ENLARGER FILTER", recipe.printDiffusion) { set { copy(printDiffusion = it) } }
                        Chips(DIFFUSION_FAMILIES, recipe.printDiffusionFamily) { set { copy(printDiffusionFamily = it) } }
                        S("Strength", recipe.printDiffusionStrength, 0f, 1f, "%.2f", recipe.printDiffusion) { set { copy(printDiffusionStrength = it) } }
                    }
                    "camera" -> {
                        S("Lens blur", recipe.lensBlurUm, 0f, 40f, "%.0f µm") { set { copy(lensBlurUm = it) } }
                        Head("FILM FORMAT", null) {}
                        Chips(listOf("35" to "35 mm", "60" to "120 / 6×6", "100" to "Large format").map { it.first }, recipe.filmFormatMm.toInt().toString()) { set { copy(filmFormatMm = it.toFloat()) } }
                        Note("Format changes how big grain and halation look, because they are measured in micrometres on the negative.")
                    }
                    "enlarger" -> {
                        Head("PAPER / PRINT STOCK", null) {}
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Develop.PAPERS.forEach { (id, label) ->
                                val on = id == recipe.paper
                                Text(label, color = if (on) LatentColors.AmberInk else LatentColors.Text, fontSize = 10.sp,
                                    modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(if (on) LatentColors.Amber else LatentColors.Surface)
                                        .combinedClickable(onClick = { Haptics.tick(context); set { copy(paper = id) } }).padding(horizontal = 10.dp, vertical = 5.dp))
                            }
                        }
                        S("Print exposure", recipe.printExposure, 0.4f, 2.2f, "%.2f") { set { copy(printExposure = it) } }
                        S("Paper contrast", recipe.printContrast, 0.6f, 1.6f, "%.2f") { set { copy(printContrast = it) } }
                        S("Yellow filter", recipe.yFilterShift, -20f, 20f, "%+.0f") { set { copy(yFilterShift = it) } }
                        S("Magenta filter", recipe.mFilterShift, -20f, 20f, "%+.0f") { set { copy(mFilterShift = it) } }
                        S("Yellow neutral", recipe.yFilterNeutral, 0f, 120f, "%.0f") { set { copy(yFilterNeutral = it) } }
                        S("Magenta neutral", recipe.mFilterNeutral, 0f, 120f, "%.0f") { set { copy(mFilterNeutral = it) } }
                        S("Pre-flash", recipe.preflash, 0f, 0.5f, "%.2f") { set { copy(preflash = it) } }
                        S("Enlarger lens blur", recipe.enlargerLensBlur, 0f, 3f, "%.2f") { set { copy(enlargerLensBlur = it) } }
                    }
                    "scanner" -> {
                        Toggle("Scan the negative (skip the print)", recipe.scanFilm) { set { copy(scanFilm = it) } }
                        S("Sharpening amount", recipe.unsharpAmount, 0f, 2f, "%.2f") { set { copy(unsharpAmount = it) } }
                        S("Sharpening radius", recipe.unsharpRadius, 0.2f, 2f, "%.2f") { set { copy(unsharpRadius = it) } }
                        S("Scanner lens blur", recipe.scannerLensBlur, 0f, 3f, "%.2f") { set { copy(scannerLensBlur = it) } }
                        Toggle("White correction", recipe.whiteCorrection) { set { copy(whiteCorrection = it) } }
                        S("White level", recipe.scannerWhiteLevel, 0.8f, 1f, "%.3f", recipe.whiteCorrection) { set { copy(scannerWhiteLevel = it) } }
                        Toggle("Black correction", recipe.blackCorrection) { set { copy(blackCorrection = it) } }
                        S("Black level", recipe.scannerBlackLevel, 0f, 0.1f, "%.3f", recipe.blackCorrection) { set { copy(scannerBlackLevel = it) } }
                    }
                    "glare" -> {
                        Head("GLARE", recipe.glare) { set { copy(glare = it) } }
                        S("Amount", recipe.glarePercent, 0f, 0.2f, "%.3f", recipe.glare) { set { copy(glarePercent = it) } }
                        S("Roughness", recipe.glareRoughness, 0f, 1f, "%.2f", recipe.glare) { set { copy(glareRoughness = it) } }
                        S("Blur", recipe.glareBlur, 0f, 2f, "%.2f", recipe.glare) { set { copy(glareBlur = it) } }
                    }
                    "colour" -> {
                        Head("OUTPUT COLOUR SPACE", null) {}
                        Chips(listOf("SRGB", "ADOBE_RGB", "PROPHOTO", "REC2020", "ACES2065_1", "LINEAR_SRGB"), recipe.outputColorSpace) { set { copy(outputColorSpace = it) } }
                        Head("OUT-OF-GAMUT COLOURS", null) {}
                        Chips(listOf("LEGACY_CLIP", "OFF", "ACES_RGC", "OKLCH", "OKLRAB"), recipe.outputGamutCompress) { set { copy(outputGamutCompress = it) } }
                        Head("FILMING-SIDE COMPRESSION", null) {}
                        Chips(listOf("OFF", "XY"), recipe.inputGamutCompress) { set { copy(inputGamutCompress = it) } }
                        Note("Gamut compression decides what happens to colours too saturated for the output space — clipping them, or folding them in gently.")
                    }
                    "engine" -> {
                        Head("RGB → SPECTRUM", null) {}
                        Chips(listOf("HANATOS2025", "MALLETT2019"), recipe.rgbToRaw) { set { copy(rgbToRaw = it) } }
                        Note("How a colour is turned into a light spectrum before the film sees it. Hanatos 2025 is the engine's default.")
                        S("Spectral blur", recipe.spectralBlur, 0f, 20f, "%.1f") { set { copy(spectralBlur = it) } }
                        S("Preview size", recipe.previewMaxSize.toFloat(), 300f, 1600f, "%.0f px") { set { copy(previewMaxSize = Math.round(it)) } }
                        Toggle("GPU preview (experimental)", recipe.gpuPreview) { set { copy(gpuPreview = it) } }
                        Toggle("GPU export (experimental)", recipe.gpuExport) { set { copy(gpuExport = it) } }
                        Note("GPU speeds up the scan stage only; the spectral work stays on the CPU. Both paths self-check against the CPU engine on this device and fall back if they disagree.")
                        Text(if (gpuTest.isEmpty()) "Measure GPU vs CPU" else gpuTest, color = LatentColors.AmberInk, fontSize = 11.sp,
                            modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(LatentColors.Amber).combinedClickable(onClick = {
                                if (!rendering && !fullRunning) {
                                    Haptics.tick(context); gpuTest = "measuring…"
                                    val r = recipe.copy(previewMaxSize = 800)
                                    Thread {
                                        val q = com.celestial.latent.develop.DevelopQueue
                                        val holds = q.acquireLane(30)
                                        try {
                                            val s0 = src ?: Develop.openCached(context, source, isRaw, DECODE_EDGE)
                                            var cpu = 0L; var gpu = 0L
                                            run { val t = System.nanoTime(); Develop.render(context, s0, r.copy(gpuPreview = false, gpuExport = false), preview = true); cpu = (System.nanoTime() - t) / 1_000_000 }
                                            run { val t = System.nanoTime(); Develop.render(context, s0, r.copy(gpuPreview = true, gpuExport = true), preview = true); gpu = (System.nanoTime() - t) / 1_000_000 }
                                            gpuTest = "CPU ${cpu} ms · GPU ${gpu} ms" + if (gpu < cpu * 0.9) " — GPU is faster" else if (gpu > cpu * 1.1) " — GPU is slower" else " — no difference"
                                            android.util.Log.i("Latent", "gpu comparison: cpu=${cpu}ms gpu=${gpu}ms")
                                        } catch (t: Throwable) { gpuTest = "failed: ${t.message}" }
                                        finally { if (holds) q.engineLane.release() }
                                    }.start()
                                }
                            }).padding(horizontal = 12.dp, vertical = 7.dp))
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(status, color = LatentColors.TextDim, fontSize = 10.sp)
                Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Reset", color = LatentColors.Text, fontSize = 11.sp,
                        modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(LatentColors.Surface)
                            .combinedClickable(onClick = { recipe = Recipe(film = recipe.film, paper = recipe.paper) }).padding(horizontal = 12.dp, vertical = 7.dp))
                    Text("Save recipe", color = LatentColors.Text, fontSize = 11.sp,
                        modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(LatentColors.Surface)
                            .combinedClickable(onClick = {
                                val n = (Develop.FILMS.firstOrNull { it.first == recipe.film }?.second ?: "Recipe") + " " + (Recipes.names(context).size + 1)
                                Recipes.save(context, n, recipe); saveName = n
                            }).padding(horizontal = 12.dp, vertical = 7.dp))
                    if (saveName.isNotEmpty()) Text("saved “$saveName”", color = LatentColors.Amber, fontSize = 10.sp, modifier = Modifier.padding(top = 6.dp))
                }
                Spacer(Modifier.height(8.dp))
            }
        }

        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(if (isRaw) "FROM RAW" else "FILM OVER JPEG", color = LatentColors.Line, fontSize = 9.sp, letterSpacing = 1.5.sp)
            Text(if (fullRunning) "Developing…" else "Develop full size", color = LatentColors.AmberInk, fontSize = 12.sp,
                modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(LatentColors.Amber).combinedClickable(onClick = {
                    if (fullRunning) return@combinedClickable
                    Haptics.click(context)
                    fullRunning = true
                    status = "full size: starting…"
                    val r = recipe
                    Thread {
                        val q = com.celestial.latent.develop.DevelopQueue
                        val holds = q.acquireLane(60)
                        try {
                            val out = Develop.developFull(context, source, isRaw, r) { m -> status = "full size: $m" }
                            status = "saved to DCIM/Latent"
                            val b = runCatching { context.contentResolver.loadThumbnail(out, android.util.Size(1600, 1600), null) }.getOrNull()
                            if (b != null) preview = b
                        } catch (t: Throwable) { status = "full size failed: ${t.message}" }
                        finally { if (holds) q.engineLane.release(); fullRunning = false }
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
private fun Head(title: String, active: Boolean?, onActive: (Boolean) -> Unit) {
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 2.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = LatentColors.TextBright, fontSize = 10.sp, letterSpacing = 1.5.sp)
        if (active != null) Text(if (active) "ON" else "OFF", color = if (active) LatentColors.Amber else LatentColors.Line, fontSize = 10.sp,
            modifier = Modifier.combinedClickable(onClick = { Haptics.tick(context); onActive(!active) }).padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

@Composable
private fun Note(text: String) {
    Text(text, color = LatentColors.TextDim, fontSize = 10.sp, lineHeight = 13.sp, modifier = Modifier.padding(vertical = 6.dp))
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
