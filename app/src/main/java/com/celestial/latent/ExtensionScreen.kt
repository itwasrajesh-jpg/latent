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

/** Minimal Camera Extensions client: Xiaomi's own BOKEH / NIGHT processing, JPEG out. API 31+. */
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
        try {
            val ec = cm.getCameraExtensionCharacteristics(cameraId)
            if (extension !in ec.supportedExtensions) { onStatus("Extension not supported: $extension"); return@post }
            val jpegSize = ec.getExtensionSupportedSizes(extension, ImageFormat.JPEG).maxByOrNull { it.width.toLong() * it.height } ?: return@post
            reader = ImageReader.newInstance(jpegSize.width, jpegSize.height, ImageFormat.JPEG, 2).also { r ->
                r.setOnImageAvailableListener({ rr ->
                    rr.acquireNextImage()?.let { img ->
                        try {
                            val buf = img.planes[0].buffer; val bytes = ByteArray(buf.remaining()); buf.get(bytes)
                            val name = "LATENT_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + "_" + (if (extension == CameraExtensionCharacteristics.EXTENSION_BOKEH) "PORTRAIT" else "NIGHT") + ".jpg"
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
            onStatus("Opening camera 0 with ${if (extension == CameraExtensionCharacteristics.EXTENSION_BOKEH) "BOKEH" else "NIGHT"}…")
            cm.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(cam: CameraDevice) {
                    device = cam
                    val cfg = ExtensionSessionConfiguration(extension, listOf(OutputConfiguration(surface), OutputConfiguration(reader!!.surface)), executor,
                        object : CameraExtensionSession.StateCallback() {
                            override fun onConfigured(s: CameraExtensionSession) {
                                session = s
                                try {
                                    val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(surface); if (zoom != 1f && zoomSupported()) set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom) }
                                    s.setRepeatingRequest(req.build(), executor, object : CameraExtensionSession.ExtensionCaptureCallback() {})
                                    onStatus("Ready · ${if (extension == CameraExtensionCharacteristics.EXTENSION_BOKEH) "Portrait" else "Night"} · tap the shutter")
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
            val req = dev.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply { addTarget(r.surface); set(CaptureRequest.JPEG_ORIENTATION, 90); set(CaptureRequest.JPEG_QUALITY, 100.toByte()); if (zoom != 1f && zoomSupported()) set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom) }
            s.capture(req.build(), executor, object : CameraExtensionSession.ExtensionCaptureCallback() {
                override fun onCaptureFailed(sess: CameraExtensionSession, request: CaptureRequest) { onStatus("capture failed") }
                override fun onCaptureProcessStarted(sess: CameraExtensionSession, request: CaptureRequest) { onStatus("Processing…") }
            })
        } catch (e: Exception) { onStatus("capture: ${e.message}") }
    }

    fun switchTo(ext: Int) { extension = ext; openOnTexture() }
    fun setZoom(z: Float) = handler.post {
        zoom = z
        val s = session ?: return@post; val dev = device ?: return@post; val surf = previewSurface ?: return@post
        try { s.setRepeatingRequest(dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(surf); if (zoomSupported()) set(CaptureRequest.CONTROL_ZOOM_RATIO, z) }.build(), executor, object : CameraExtensionSession.ExtensionCaptureCallback() {}) } catch (e: Exception) { onStatus("zoom: ${e.message}") }
    }
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
    val cam = remember { ExtensionCamera(context) { s -> status = s }.also { it.onZoomSupport = { ok -> zoomOk = ok } } }
    DisposableEffect(Unit) { onDispose { cam.destroy() } }

    Column(Modifier.fillMaxSize().background(LatentColors.Background).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("‹ settings", color = LatentColors.Text, fontSize = 14.sp, modifier = Modifier.combinedClickable(onClick = onBack))
            Row {
                listOf("Portrait" to CameraExtensionCharacteristics.EXTENSION_BOKEH, "Night" to CameraExtensionCharacteristics.EXTENSION_NIGHT).forEach { (label, ext) ->
                    val on = mode == ext
                    Text(label, color = if (on) LatentColors.AmberInk else LatentColors.Text, fontSize = 13.sp,
                        modifier = Modifier.padding(start = 6.dp).clip(RoundedCornerShape(999.dp)).background(if (on) LatentColors.Amber else LatentColors.Surface)
                            .combinedClickable(onClick = { if (!on) { mode = ext; Haptics.tick(context); cam.switchTo(ext) } }).padding(horizontal = 12.dp, vertical = 6.dp))
                }
            }
        }
        Box(Modifier.fillMaxWidth().aspectRatio(3f / 4f).background(LatentColors.Surface)) {
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
            Text("XIAOMI " + (if (mode == CameraExtensionCharacteristics.EXTENSION_BOKEH) "PORTRAIT" else "NIGHT") + " · JPEG ONLY", color = LatentColors.TextBright, fontSize = 10.sp, letterSpacing = 1.sp, modifier = Modifier.align(Alignment.TopStart).padding(12.dp))
            if (zoomOk) Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp)) {
                listOf(0.6f, 1f, 2f, 3f, 4.3f).forEach { z ->
                    val on = zoom == z
                    Text(if (on) "${z}×".replace(".0×", "×") else "$z".removeSuffix(".0"), color = if (on) LatentColors.TextBright else LatentColors.Text, fontSize = if (on) 15.sp else 12.sp,
                        modifier = Modifier.combinedClickable(onClick = { Haptics.tick(context); zoom = z; cam.setZoom(z) }).padding(horizontal = 10.dp, vertical = 6.dp))
                }
            } else Text("LENS: CHOSEN BY XIAOMI", color = LatentColors.TextDim, fontSize = 10.sp, letterSpacing = 1.sp, modifier = Modifier.align(Alignment.BottomStart).padding(12.dp))
        }
        Spacer(Modifier.height(12.dp))
        Text(status, color = LatentColors.Text, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp))
        Spacer(Modifier.height(16.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(Modifier.size(78.dp).clip(CircleShape).border(2.dp, LatentColors.TextBright, CircleShape).combinedClickable(onClick = { Haptics.heavy(context); cam.capture() }), contentAlignment = Alignment.Center) {
                Box(Modifier.size(62.dp).clip(CircleShape).background(LatentColors.TextBright))
            }
        }
    }
}
