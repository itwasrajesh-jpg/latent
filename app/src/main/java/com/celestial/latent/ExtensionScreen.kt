@file:OptIn(ExperimentalFoundationApi::class)

package com.celestial.latent

import android.annotation.SuppressLint
import android.content.ContentValues
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraExtensionCharacteristics
import android.hardware.camera2.CameraExtensionSession
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.ExtensionSessionConfiguration
import android.hardware.camera2.params.OutputConfiguration
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.view.Surface
import android.view.TextureView
import androidx.annotation.RequiresApi
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.celestial.latent.ui.LatentColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor

private fun extName(ext: Int) = when (ext) {
    CameraExtensionCharacteristics.EXTENSION_BOKEH -> "PORTRAIT"
    CameraExtensionCharacteristics.EXTENSION_NIGHT -> "NIGHT"
    else -> "AUTO"
}

/** Minimal Camera Extensions client: Xiaomi's own BOKEH / NIGHT / AUTOMATIC processing, JPEG out. API 31+. */
@RequiresApi(31)
class ExtensionCamera(private val context: android.content.Context, private val onStatus: (String) -> Unit) {
    private val cm = context.getSystemService(android.content.Context.CAMERA_SERVICE) as CameraManager
    private val thread = HandlerThread("latent-ext").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { r -> handler.post(r) }
    private var device: CameraDevice? = null
    private var session: CameraExtensionSession? = null
    private var reader: ImageReader? = null
    private var previewSurface: Surface? = null
    var extension = CameraExtensionCharacteristics.EXTENSION_BOKEH
    private val cameraId = "0"
    private var surfaceTexture: SurfaceTexture? = null
    @Volatile var zoom = 1f
    var onZoomSupport: (Boolean) -> Unit = {}

    /** What this extension lets us set. Filled on open, reported via onCaps. */
    data class Caps(val keys: List<String>, val zoom: Boolean, val ev: Boolean, val manual: Boolean, val afRegions: Boolean, val isz: Boolean, val evRange: android.util.Range<Int>?, val expRange: android.util.Range<Long>?, val isoRange: android.util.Range<Int>?)
    var caps: Caps? = null
    var onCaps: (Caps) -> Unit = {}
    @Volatile var evIndex = 0
    @Volatile var shutterNs: Long? = null
    @Volatile var iso: Int? = null
    @Volatile var isz = false
    private var afRegion: android.hardware.camera2.params.MeteringRectangle? = null

    private fun discover(): Caps {
        val names = if (Build.VERSION.SDK_INT >= 33) runCatching { cm.getCameraExtensionCharacteristics(cameraId).getAvailableCaptureRequestKeys(extension).map { it.name } }.getOrDefault(emptyList()) else emptyList()
        val ch = cm.getCameraCharacteristics(cameraId)
        val c = Caps(
            keys = names,
            zoom = names.contains(CaptureRequest.CONTROL_ZOOM_RATIO.name),
            ev = names.contains(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION.name),
            manual = names.contains(CaptureRequest.SENSOR_EXPOSURE_TIME.name) && names.contains(CaptureRequest.SENSOR_SENSITIVITY.name) && names.contains(CaptureRequest.CONTROL_AE_MODE.name),
            afRegions = names.contains(CaptureRequest.CONTROL_AF_REGIONS.name),
            isz = names.contains("org.codeaurora.qcamera3.sessionParameters.EnableInsensorZoom"),
            evRange = ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE),
            expRange = ch.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE),
            isoRange = ch.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE),
        )
        android.util.Log.i("Latent", "extension ${extName(extension)} accepts ${names.size} keys: ${names.joinToString()}")
        caps = c; onCaps(c)
        return c
    }

    /** Applies whatever this extension allows to a request. */
    private fun applyAllowed(b: CaptureRequest.Builder) {
        val c = caps ?: return
        if (c.zoom && zoom != 1f) b.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom)
        if (c.ev && !(c.manual && shutterNs != null)) b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, evIndex)
        if (c.manual && (shutterNs != null || iso != null)) {
            b.set(CaptureRequest.CONTROL_AE_MODE, android.hardware.camera2.CameraMetadata.CONTROL_AE_MODE_OFF)
            shutterNs?.let { b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, it) }
            iso?.let { b.set(CaptureRequest.SENSOR_SENSITIVITY, it) }
        }
        if (c.afRegions) afRegion?.let { r -> b.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(r)); b.set(CaptureRequest.CONTROL_AF_MODE, android.hardware.camera2.CameraMetadata.CONTROL_AF_MODE_AUTO); b.set(CaptureRequest.CONTROL_AF_TRIGGER, android.hardware.camera2.CameraMetadata.CONTROL_AF_TRIGGER_START) }
        if (c.isz && isz) try { b.set(CaptureRequest.Key("org.codeaurora.qcamera3.sessionParameters.EnableInsensorZoom", Int::class.javaObjectType), 1) } catch (_: Throwable) {}
    }

    fun refresh() = handler.post {
        val s = session ?: return@post; val dev = device ?: return@post; val surf = previewSurface ?: return@post
        try { s.setRepeatingRequest(dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(surf); applyAllowed(this) }.build(), executor, object : CameraExtensionSession.ExtensionCaptureCallback() {}) } catch (e: Exception) { onStatus("update: ${e.message}") }
        afRegion = null
    }

    fun tapFocus(u: Float, v: Float) {
        val active = cm.getCameraCharacteristics(cameraId).get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
        val sx = v.coerceIn(0f, 1f); val sy = (1f - u).coerceIn(0f, 1f); val half = 0.06f
        val l = ((sx - half) * active.width()).toInt().coerceIn(0, active.width() - 2); val t = ((sy - half) * active.height()).toInt().coerceIn(0, active.height() - 2)
        afRegion = android.hardware.camera2.params.MeteringRectangle(l, t, (2 * half * active.width()).toInt(), (2 * half * active.height()).toInt(), 999)
        refresh()
    }

    private fun zoomSupported(): Boolean = Build.VERSION.SDK_INT >= 33 && runCatching {
        cm.getCameraExtensionCharacteristics(cameraId).getAvailableCaptureRequestKeys(extension).any { it.name == CaptureRequest.CONTROL_ZOOM_RATIO.name }
    }.getOrDefault(false)

    fun previewSize(): android.util.Size {
        if (Build.VERSION.SDK_INT < 31) return android.util.Size(1440, 1080)
        val ec = cm.getCameraExtensionCharacteristics(cameraId)
        val sizes = ec.getExtensionSupportedSizes(extension, SurfaceTexture::class.java)
        return sizes.filter { it.width * 3 == it.height * 4 && it.width <= 1920 }.maxByOrNull { it.width } ?: sizes.firstOrNull() ?: android.util.Size(1440, 1080)
    }

    fun attach(st: SurfaceTexture) { surfaceTexture = st; openOnTexture() }

    /** Sizes the preview for the current extension, then opens. */
    private fun openOnTexture() {
        val st = surfaceTexture ?: return
        val size = previewSize()
        st.setDefaultBufferSize(size.width, size.height)
        open(Surface(st))
    }

    @SuppressLint("MissingPermission")
    fun open(surface: Surface) = handler.post {
        if (Build.VERSION.SDK_INT < 31) { onStatus("Extensions need Android 12+"); return@post }
        closeInternal()
        Thread.sleep(250) // let the previous extension session fully release before the next one
        previewSurface = surface
        onZoomSupport(zoomSupported())
        discover()
        try {
            val ec = cm.getCameraExtensionCharacteristics(cameraId)
            if (extension !in ec.supportedExtensions) { onStatus("Extension not supported: $extension"); return@post }
            val jpegSize = ec.getExtensionSupportedSizes(extension, ImageFormat.JPEG).maxByOrNull { it.width.toLong() * it.height } ?: return@post
            reader = ImageReader.newInstance(jpegSize.width, jpegSize.height, ImageFormat.JPEG, 2).also { r ->
                r.setOnImageAvailableListener({ rr ->
                    rr.acquireNextImage()?.let { img ->
                        try {
                            val buf = img.planes[0].buffer; val bytes = ByteArray(buf.remaining()); buf.get(bytes)
                            val name = "LATENT_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + "_" + extName(extension) + ".jpg"
                            val values = ContentValues().apply {
                                put(MediaStore.Images.Media.DISPLAY_NAME, name); put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                                put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/Latent"); put(MediaStore.Images.Media.IS_PENDING, 1)
                            }
                            val resolver = context.contentResolver
                            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
                            resolver.openOutputStream(uri)!!.use { it.write(bytes) }
                            values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0); resolver.update(uri, values, null, null)
                            onStatus("Saved $name (${bytes.size / 1024} KB, ${img.width}x${img.height})")
                        } catch (e: Exception) { onStatus("save failed: ${e.message}") } finally { img.close() }
                    }
                }, handler)
            }
            onStatus("Opening camera 0 with ${extName(extension)}…")
            cm.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(cam: CameraDevice) {
                    device = cam
                    val cfg = ExtensionSessionConfiguration(extension, listOf(OutputConfiguration(surface), OutputConfiguration(reader!!.surface)), executor,
                        object : CameraExtensionSession.StateCallback() {
                            override fun onConfigured(s: CameraExtensionSession) {
                                session = s
                                try {
                                    val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(surface); applyAllowed(this) }
                                    s.setRepeatingRequest(req.build(), executor, object : CameraExtensionSession.ExtensionCaptureCallback() {})
                                    onStatus("Ready · ${extName(extension)} · tap the shutter")
                                } catch (e: Exception) { onStatus("preview: ${e.message}") }
                            }
                            override fun onConfigureFailed(s: CameraExtensionSession) { onStatus("Extension session refused") }
                        })
                    try { cam.createExtensionSession(cfg) } catch (e: Exception) { onStatus("createExtensionSession: ${e.message}") }
                }
                override fun onDisconnected(cam: CameraDevice) { cam.close(); device = null }
                override fun onError(cam: CameraDevice, error: Int) { onStatus("camera error $error"); cam.close(); device = null }
            }, handler)
        } catch (e: Exception) { onStatus("open: ${e.message}") }
    }

    fun capture() = handler.post {
        val s = session ?: return@post; val dev = device ?: return@post; val r = reader ?: return@post
        try {
            val req = dev.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply { addTarget(r.surface); set(CaptureRequest.JPEG_ORIENTATION, 90); set(CaptureRequest.JPEG_QUALITY, 100.toByte()); applyAllowed(this) }
            s.capture(req.build(), executor, object : CameraExtensionSession.ExtensionCaptureCallback() {
                override fun onCaptureFailed(sess: CameraExtensionSession, request: CaptureRequest) { onStatus("capture failed") }
                override fun onCaptureProcessStarted(sess: CameraExtensionSession, request: CaptureRequest) { onStatus("Processing…") }
            })
        } catch (e: Exception) { onStatus("capture: ${e.message}") }
    }

    fun switchTo(ext: Int) { extension = ext; openOnTexture() }
    fun applyZoom(z: Float) { zoom = z; refresh() }
    fun close() = handler.post { closeInternal() }
    private fun closeInternal() {
        try { session?.close() } catch (_: Exception) {}; session = null
        try { device?.close() } catch (_: Exception) {}; device = null
        reader?.close(); reader = null
    }
    fun destroy() { close(); handler.post { thread.quitSafely() } }
}

@RequiresApi(31)
@Composable
fun ExtensionScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var status by remember { mutableStateOf("Starting…") }
    var mode by remember { mutableStateOf(CameraExtensionCharacteristics.EXTENSION_BOKEH) }
    var zoomOk by remember { mutableStateOf(false) }
    var zoom by remember { mutableStateOf(1f) }
    var caps by remember { mutableStateOf<ExtensionCamera.Caps?>(null) }
    var ev by remember { mutableStateOf(0) }
    var shutter by remember { mutableStateOf<Long?>(null) }
    var isoV by remember { mutableStateOf<Int?>(null) }
    var iszOn by remember { mutableStateOf(false) }
    val cam = remember { ExtensionCamera(context) { s -> status = s }.also { it.onZoomSupport = { ok -> zoomOk = ok }; it.onCaps = { c -> caps = c } } }
    DisposableEffect(Unit) { onDispose { cam.destroy() } }

    Column(Modifier.fillMaxSize().background(LatentColors.Background).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("‹ settings", color = LatentColors.Text, fontSize = 14.sp, modifier = Modifier.combinedClickable(onClick = onBack))
            Row {
                listOf("Portrait" to CameraExtensionCharacteristics.EXTENSION_BOKEH, "Night" to CameraExtensionCharacteristics.EXTENSION_NIGHT, "Auto" to CameraExtensionCharacteristics.EXTENSION_AUTOMATIC).forEach { (label, ext) ->
                    val on = mode == ext
                    Text(label, color = if (on) LatentColors.AmberInk else LatentColors.Text, fontSize = 13.sp,
                        modifier = Modifier.padding(start = 6.dp).clip(RoundedCornerShape(999.dp)).background(if (on) LatentColors.Amber else LatentColors.Surface)
                            .combinedClickable(onClick = { if (!on) { mode = ext; Haptics.tick(context); cam.switchTo(ext) } }).padding(horizontal = 12.dp, vertical = 6.dp))
                }
            }
        }
        Box(Modifier.fillMaxWidth().aspectRatio(3f / 4f).background(LatentColors.Surface).pointerInput(Unit) {
            detectTapGestures { pos -> if (caps?.afRegions == true) { Haptics.tick(context); cam.tapFocus(pos.x / size.width, pos.y / size.height) } }
        }) {
            AndroidView(modifier = Modifier.fillMaxSize(), factory = { ctx ->
                TextureView(ctx).apply {
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) { cam.attach(st) }
                        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { cam.close(); return true }
                        override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                    }
                }
            })
            Text("XIAOMI " + extName(mode) + " · JPEG ONLY", color = LatentColors.TextBright, fontSize = 10.sp, letterSpacing = 1.sp, modifier = Modifier.align(Alignment.TopStart).padding(12.dp))
            if (zoomOk) Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp)) {
                listOf(0.6f, 1f, 2f, 3f, 4.3f).forEach { z ->
                    val on = zoom == z
                    Text(if (on) "${z}×".replace(".0×", "×") else "$z".removeSuffix(".0"), color = if (on) LatentColors.TextBright else LatentColors.Text, fontSize = if (on) 15.sp else 12.sp,
                        modifier = Modifier.combinedClickable(onClick = { Haptics.tick(context); zoom = z; cam.applyZoom(z) }).padding(horizontal = 10.dp, vertical = 6.dp))
                }
                if (caps?.isz == true) Text(if (iszOn) "ISZ" else "isz", color = if (iszOn) LatentColors.AmberInk else LatentColors.Amber, fontSize = 11.sp,
                    modifier = Modifier.padding(start = 6.dp).clip(RoundedCornerShape(999.dp)).background(if (iszOn) LatentColors.Amber else androidx.compose.ui.graphics.Color.Transparent).border(0.5.dp, LatentColors.Amber, RoundedCornerShape(999.dp))
                        .combinedClickable(onClick = { iszOn = !iszOn; cam.isz = iszOn; cam.refresh() }).padding(horizontal = 8.dp, vertical = 3.dp))
            } else Text("LENS: CHOSEN BY XIAOMI", color = LatentColors.TextDim, fontSize = 10.sp, letterSpacing = 1.sp, modifier = Modifier.align(Alignment.BottomStart).padding(12.dp))
            caps?.let { c -> Text("ACCEPTS: " + listOfNotNull(if (c.zoom) "ZOOM" else null, if (c.ev) "EV" else null, if (c.manual) "MANUAL" else null, if (c.afRegions) "TAP-AF" else null, if (c.isz) "ISZ" else null).ifEmpty { listOf("NOTHING EXTRA") }.joinToString(" · "),
                color = LatentColors.TextDim, fontSize = 10.sp, letterSpacing = 1.sp, modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)) }
        }
        caps?.let { c ->
            if (c.ev && c.evRange != null) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("EV %+.1f".format(ev / 6.0), color = LatentColors.TextBright, fontSize = 12.sp, modifier = Modifier.padding(end = 10.dp))
                androidx.compose.material3.Slider(value = ev.toFloat(), onValueChange = { v -> val n = Math.round(v); if (n != ev) { Haptics.tick(context); ev = n; cam.evIndex = n; cam.refresh() } },
                    valueRange = c.evRange.lower.toFloat()..c.evRange.upper.toFloat(), steps = (c.evRange.upper - c.evRange.lower - 1).coerceAtLeast(0),
                    colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = LatentColors.Amber, activeTrackColor = LatentColors.Amber, inactiveTrackColor = LatentColors.Line))
            }
            if (c.manual && c.expRange != null && c.isoRange != null) {
                val sPresets = com.celestial.latent.camera.ControlMath.shutterPresets(c.expRange.lower, c.expRange.upper)
                val iPresets = com.celestial.latent.camera.ControlMath.isoPresets(c.isoRange.lower, c.isoRange.upper)
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("S " + (shutter?.let { com.celestial.latent.camera.ControlMath.shutterLabel(it) } ?: "A"), color = LatentColors.TextBright, fontSize = 12.sp, modifier = Modifier.padding(end = 10.dp).combinedClickable(onClick = { shutter = null; cam.shutterNs = null; cam.refresh() }))
                    androidx.compose.material3.Slider(value = (shutter?.let { sh -> sPresets.indexOfFirst { it >= sh }.coerceAtLeast(0) } ?: 0).toFloat(), onValueChange = { v -> val ns = sPresets[Math.round(v).coerceIn(0, sPresets.size - 1)]; if (ns != shutter) { Haptics.tick(context); shutter = ns; cam.shutterNs = ns; cam.refresh() } },
                        valueRange = 0f..(sPresets.size - 1).toFloat(), steps = (sPresets.size - 2).coerceAtLeast(0),
                        colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = LatentColors.Amber, activeTrackColor = LatentColors.Amber, inactiveTrackColor = LatentColors.Line))
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("ISO " + (isoV?.toString() ?: "A"), color = LatentColors.TextBright, fontSize = 12.sp, modifier = Modifier.padding(end = 10.dp).combinedClickable(onClick = { isoV = null; cam.iso = null; cam.refresh() }))
                    androidx.compose.material3.Slider(value = (isoV?.let { i -> iPresets.indexOfFirst { it >= i }.coerceAtLeast(0) } ?: 0).toFloat(), onValueChange = { v -> val i = iPresets[Math.round(v).coerceIn(0, iPresets.size - 1)]; if (i != isoV) { Haptics.tick(context); isoV = i; cam.iso = i; cam.refresh() } },
                        valueRange = 0f..(iPresets.size - 1).toFloat(), steps = (iPresets.size - 2).coerceAtLeast(0),
                        colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = LatentColors.Amber, activeTrackColor = LatentColors.Amber, inactiveTrackColor = LatentColors.Line))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(status, color = LatentColors.Text, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp))
        Spacer(Modifier.height(16.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(Modifier.size(78.dp).clip(CircleShape).border(2.dp, LatentColors.TextBright, CircleShape).combinedClickable(onClick = { Haptics.heavy(context); cam.capture() }), contentAlignment = Alignment.Center) {
                Box(Modifier.size(62.dp).clip(CircleShape).background(LatentColors.TextBright))
            }
        }
    }
}
