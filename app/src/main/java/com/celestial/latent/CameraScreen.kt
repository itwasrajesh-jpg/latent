@file:OptIn(ExperimentalFoundationApi::class)

package com.celestial.latent

import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.celestial.latent.camera.CameraController
import com.celestial.latent.camera.ControlMath
import com.celestial.latent.camera.Controls
import com.celestial.latent.camera.Lens
import com.celestial.latent.camera.Lenses
import com.celestial.latent.camera.LiveReadout
import com.celestial.latent.ui.LatentColors
import kotlinx.coroutines.delay

private enum class Cell { EV, S, ISO, WB, F }

@Composable
fun CameraScreen(settings: AppSettings, onOpenSettings: () -> Unit, onLensChanged: (Lens) -> Unit, onController: (CameraController) -> Unit = {}, onVendorEcho: (String) -> Unit = {}) {
    val context = LocalContext.current
    var status by remember { mutableStateOf("Starting camera…") }
    var log by remember { mutableStateOf("") }
    val startLens = remember { Lenses.ALL.firstOrNull { it.physicalId == settings.defaultLensId } ?: Lenses.DEFAULT }
    var lens by remember { mutableStateOf(startLens) }
    var readout by remember { mutableStateOf(LiveReadout()) }
    var kelvinShown by remember { mutableStateOf(0) }
    var controls by remember { mutableStateOf(Controls()) }
    var selected by remember { mutableStateOf<Cell?>(null) }
    var focusTap by remember { mutableStateOf<Offset?>(null) }
    var focusTapAt by remember { mutableStateOf(0L) }

    val controller = remember {
        CameraController(
            context,
            onStatus = { s -> status = s },
            onLog = { s -> log = (s + "\n" + log).take(2000) },
            onReadout = { r -> readout = r; if (r.kelvinEstimate > 0) kelvinShown = r.kelvinEstimate },
        )
    }
    var surfaceRef by remember { mutableStateOf<Surface?>(null) }

    DisposableEffect(Unit) {
        ShutterBus.onShutter = { if (settings.volumeShutter) controller.captureSingle() }
        onDispose { ShutterBus.onShutter = null; controller.destroy() }
    }
    LaunchedEffect(settings.antibanding) { controller.setAntibanding(settings.antibanding) }
    LaunchedEffect(settings.cameraPath) { controller.cameraPath = settings.cameraPath }
    LaunchedEffect(settings.opmode) { controller.opmode = settings.opmode }
    LaunchedEffect(settings.vendorTags) { controller.vendorTags = settings.vendorTags.map { CameraController.VendorTagSpec(it.name, it.scope, it.type, it.value) } }
    LaunchedEffect(Unit) { controller.onVendorEcho = onVendorEcho; onController(controller) }
    LaunchedEffect(settings.saveJpeg) { controller.saveJpeg = settings.saveJpeg }
    LaunchedEffect(settings.inSensorZoomJpeg) { controller.inSensorZoomJpeg = settings.inSensorZoomJpeg }
    LaunchedEffect(settings.dcgMode) { controller.dcgMode = settings.dcgMode }
    LaunchedEffect(settings.sensorShdr) { controller.sensorShdr = settings.sensorShdr }
    LaunchedEffect(focusTapAt) { if (focusTapAt > 0) { delay(1500); focusTap = null } }

    fun push(c: Controls) { controls = c; controller.setControls(c) }

    Column(
        modifier = Modifier.fillMaxSize().background(LatentColors.Background).statusBarsPadding().navigationBarsPadding(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("LATENT", color = LatentColors.Text, fontSize = 13.sp, letterSpacing = 4.sp, fontWeight = FontWeight.Light)
            Text("12.5M · " + (if (settings.saveJpeg) "RAW+JPG" else "RAW") + " · v" + BuildConfig.VERSION_NAME, color = LatentColors.TextDim, fontSize = 11.sp)
            Text("settings", color = LatentColors.TextDim, fontSize = 11.sp, modifier = Modifier.combinedClickable(onClick = onOpenSettings))
        }

        // Viewfinder: 3:4 box. Tap = focus at that point.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .background(LatentColors.Surface)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { pos ->
                            focusTap = pos; focusTapAt = System.currentTimeMillis()
                            if (controls.locked) { controller.unlock() }
                            controller.tapFocus(pos.x / size.width, pos.y / size.height)
                            controls = controls.copy(focusDiopters = null, locked = false)
                        },
                        onLongPress = { pos ->
                            focusTap = pos; focusTapAt = System.currentTimeMillis()
                            controller.lockAt(pos.x / size.width, pos.y / size.height)
                            controls = controls.copy(focusDiopters = null, locked = true)
                        },
                    )
                },
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    // TextureView applies the camera's rotation transform itself, so the first
                    // frame is drawn with the right geometry (SurfaceView got this wrong on launch).
                    TextureView(ctx).apply {
                        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                            override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                                val size = controller.previewSizeFor(lens)
                                st.setDefaultBufferSize(size.width, size.height)
                                val surf = Surface(st)
                                surfaceRef = surf
                                controller.open(lens, surf)
                            }
                            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {}
                            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { surfaceRef = null; controller.close(); return true }
                            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                        }
                    }
                },
            )
            if (settings.gridlines) {
                Canvas(Modifier.fillMaxSize()) {
                    val c = Color(0x66FFFFFF)
                    val w = size.width; val h = size.height
                    drawLine(c, Offset(w / 3, 0f), Offset(w / 3, h), 1f)
                    drawLine(c, Offset(2 * w / 3, 0f), Offset(2 * w / 3, h), 1f)
                    drawLine(c, Offset(0f, h / 3), Offset(w, h / 3), 1f)
                    drawLine(c, Offset(0f, 2 * h / 3), Offset(w, 2 * h / 3), 1f)
                }
            }
            focusTap?.let { p ->
                val d = LocalDensity.current
                val boxPx = with(d) { 72.dp.toPx() }
                Box(
                    Modifier
                        .offset { androidx.compose.ui.unit.IntOffset((p.x - boxPx / 2).toInt(), (p.y - boxPx / 2).toInt()) }
                        .size(72.dp)
                        .border(1.dp, LatentColors.Amber, RoundedCornerShape(4.dp)),
                )
            }
            Text(
                text = lens.name + (if (readout.afState.isNotEmpty()) " · AF " + readout.afState else ""),
                color = LatentColors.Text, fontSize = 11.sp,
                modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
            )
            if (controls.locked) {
                Text(
                    "AE/AF LOCK", color = LatentColors.AmberInk, fontSize = 11.sp, letterSpacing = 1.sp,
                    modifier = Modifier.align(Alignment.TopCenter).padding(8.dp).clip(RoundedCornerShape(999.dp)).background(LatentColors.Amber).padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }

        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.Center) {
            // 2x request on any lens: digital crop unless a vendor tag / sensor mode makes it in-sensor.
            run {
                Text(
                    text = "2x", color = if (controls.zoom == 2f) LatentColors.Amber else LatentColors.Text, fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 6.dp).clip(RoundedCornerShape(999.dp))
                        .background(if (controls.zoom == 2f) LatentColors.Surface else LatentColors.Background)
                        .combinedClickable(onClick = { push(controls.copy(zoom = if (controls.zoom == 2f) 1f else 2f)) })
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
            Lenses.ALL.forEach { l ->
                LensChip(l, l == lens) {
                    if (l != lens) {
                        lens = l
                        // Manual values may be out of range on the new lens; go back to auto for exposure and focus.
                        push(controls.copy(shutterNs = null, iso = null, focusDiopters = null, zoom = 1f))
                        onLensChanged(l)
                        surfaceRef?.let { surf -> controller.open(l, surf) }
                    }
                }
            }
        }

        // ---- Control strip -------------------------------------------------------------
        val evStr = if (controls.manualExposure) "—" else String.format("%+.1f", controls.evIndex / 6.0).replace("+0.0", "0.0")
        val sStr = ControlMath.shutterLabel(controls.shutterNs ?: readout.shutterNs)
        val isoStr = (controls.iso ?: readout.iso).takeIf { it > 0 }?.toString() ?: "—"
        val wbStr = controls.kelvin?.let { "${it}K" } ?: if (kelvinShown > 0) "${kelvinShown}K" else "—"
        val fStr = if (controls.focusDiopters == null) "AF" else focusLabel(controls.focusDiopters!!)

        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            StripCell("EV", evStr, auto = false, selected = selected == Cell.EV, dim = controls.manualExposure, Modifier.weight(1f)) { selected = if (selected == Cell.EV) null else Cell.EV }
            StripCell("S", sStr, auto = controls.shutterNs == null, selected = selected == Cell.S, dim = false, Modifier.weight(1f)) { selected = if (selected == Cell.S) null else Cell.S }
            StripCell("ISO", isoStr, auto = controls.iso == null, selected = selected == Cell.ISO, dim = false, Modifier.weight(1f)) { selected = if (selected == Cell.ISO) null else Cell.ISO }
            StripCell("WB", wbStr, auto = controls.kelvin == null, selected = selected == Cell.WB, dim = false, Modifier.weight(1f)) { selected = if (selected == Cell.WB) null else Cell.WB }
            StripCell("F", fStr, auto = controls.focusDiopters == null, selected = selected == Cell.F, dim = false, Modifier.weight(1f)) { selected = if (selected == Cell.F) null else Cell.F }
        }

        // Slider for the selected cell.
        Box(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
            when (selected) {
                Cell.EV -> {
                    val r = if (controller.hasCharacteristics) controller.evRange else android.util.Range(-24, 24)
                    StripSlider(
                        value = controls.evIndex.toFloat(), min = r.lower.toFloat(), max = r.upper.toFloat(), steps = (r.upper - r.lower - 1).coerceAtLeast(0),
                        onChange = { push(controls.copy(evIndex = Math.round(it))) },
                        onAuto = { push(controls.copy(evIndex = 0)) }, autoLabel = "0",
                        enabled = !controls.manualExposure,
                    )
                }
                Cell.S -> {
                    val presets = if (controller.hasCharacteristics) ControlMath.shutterPresets(controller.shutterRange.lower, controller.shutterRange.upper) else listOf(10_000_000L)
                    val cur = controls.shutterNs ?: readout.shutterNs
                    val idx = presets.indices.minByOrNull { Math.abs(presets[it] - cur) } ?: 0
                    StripSlider(
                        value = idx.toFloat(), min = 0f, max = (presets.size - 1).toFloat(), steps = (presets.size - 2).coerceAtLeast(0),
                        onChange = { push(controls.copy(shutterNs = presets[Math.round(it).coerceIn(0, presets.size - 1)])) },
                        onAuto = { push(controls.copy(shutterNs = null)) }, autoLabel = "A",
                    )
                }
                Cell.ISO -> {
                    val presets = if (controller.hasCharacteristics) ControlMath.isoPresets(controller.isoRange.lower, controller.isoRange.upper) else listOf(100)
                    val cur = controls.iso ?: readout.iso
                    val idx = presets.indices.minByOrNull { Math.abs(presets[it] - cur) } ?: 0
                    StripSlider(
                        value = idx.toFloat(), min = 0f, max = (presets.size - 1).toFloat(), steps = (presets.size - 2).coerceAtLeast(0),
                        onChange = { push(controls.copy(iso = presets[Math.round(it).coerceIn(0, presets.size - 1)])) },
                        onAuto = { push(controls.copy(iso = null)) }, autoLabel = "A",
                    )
                }
                Cell.WB -> {
                    val cur = controls.kelvin ?: (if (kelvinShown > 0) kelvinShown else 5200)
                    StripSlider(
                        value = cur.toFloat(), min = 2000f, max = 10000f, steps = 79,
                        onChange = { push(controls.copy(kelvin = (Math.round(it / 100f) * 100))) },
                        onAuto = { push(controls.copy(kelvin = null)) }, autoLabel = "A",
                    )
                }
                Cell.F -> {
                    val maxD = if (controller.hasCharacteristics) controller.minFocusDiopters else 10f
                    val cur = controls.focusDiopters ?: readout.focusDiopters
                    // Slider runs infinity (left) to closest (right).
                    StripSlider(
                        value = cur.coerceIn(0f, maxD), min = 0f, max = maxD, steps = 0,
                        onChange = { push(controls.copy(focusDiopters = it)) },
                        onAuto = { push(controls.copy(focusDiopters = null)) }, autoLabel = "AF",
                    )
                }
                null -> Text(status, color = LatentColors.Text, fontSize = 11.sp, lineHeight = 14.sp, modifier = Modifier.fillMaxWidth())
            }
        }

        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .size(76.dp)
                    .clip(CircleShape)
                    .border(3.dp, LatentColors.TextBright, CircleShape)
                    .combinedClickable(onClick = { controller.captureSingle() }, onLongClick = { controller.captureBurst(16) }),
                contentAlignment = Alignment.Center,
            ) { Box(Modifier.size(58.dp).clip(CircleShape).background(LatentColors.TextBright)) }
        }

        Spacer(Modifier.height(6.dp))
        Text(
            text = if (selected == null) log else status,
            color = LatentColors.TextDim, fontSize = 10.sp, lineHeight = 13.sp, fontFamily = FontFamily.Monospace,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
    }
}

private fun focusLabel(d: Float): String = when {
    d <= 0.05f -> "∞"
    d < 1f -> String.format("%.1fm", 1 / d)
    else -> String.format("%.0fcm", 100 / d)
}

@Composable
private fun StripCell(label: String, value: String, auto: Boolean, selected: Boolean, dim: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier = modifier.combinedClickable(onClick = onClick).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, color = if (dim) LatentColors.Line else if (selected) LatentColors.Amber else LatentColors.TextBright, fontSize = 14.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = LatentColors.TextDim, fontSize = 10.sp)
            if (auto) {
                Spacer(Modifier.width(3.dp))
                Text("A", color = LatentColors.TextDim, fontSize = 8.sp,
                    modifier = Modifier.border(0.5.dp, LatentColors.TextDim, CircleShape).padding(horizontal = 3.dp))
            }
        }
    }
}

@Composable
private fun StripSlider(value: Float, min: Float, max: Float, steps: Int, onChange: (Float) -> Unit, onAuto: () -> Unit, autoLabel: String, enabled: Boolean = true) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            autoLabel, color = LatentColors.AmberInk, fontSize = 11.sp,
            modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(LatentColors.Amber).combinedClickable(onClick = onAuto).padding(horizontal = 10.dp, vertical = 4.dp),
        )
        Spacer(Modifier.width(12.dp))
        Slider(
            value = value.coerceIn(min, max), onValueChange = onChange, valueRange = min..max, steps = steps, enabled = enabled,
            colors = SliderDefaults.colors(thumbColor = LatentColors.Amber, activeTrackColor = LatentColors.Amber, inactiveTrackColor = LatentColors.Line),
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun LensChip(l: Lens, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = l.label,
        color = if (selected) LatentColors.Amber else LatentColors.Text,
        fontSize = 13.sp,
        modifier = Modifier
            .padding(horizontal = 6.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) LatentColors.Surface else LatentColors.Background)
            .combinedClickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}
