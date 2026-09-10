package com.celestial.latent.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * Finds vendor keys per camera path and lens, and actively tests candidate keys to see what they change.
 * Runs on its own thread; the live camera must be closed while a deep probe runs.
 */
class VendorProbe(private val context: Context) {

    private val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val thread = HandlerThread("latent-probe").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { r -> handler.post(r) }

    companion object {
        val PATHS = listOf("0", "6", "7", "direct")
        private val INTERESTING = Regex("zoom|remosaic|qcfa|quadra|insensor|in_sensor|crop|hdr|bit|dcg|superres|super_res|highres|high_res|fullsize|full_size|binning|sensor.?mode|opmode|raw|resolution|native", RegexOption.IGNORE_CASE)
    }

    /** Passive: the full key matrix, MotionCam notation. */
    fun enumerate(): String {
        val sb = StringBuilder("LATENT VENDOR KEY MAP\n")
        val candidates = LinkedHashSet<String>()
        for (path in PATHS) {
            for (lens in Lenses.ALL) {
                val (label, keys) = keysFor(path, lens) ?: continue
                sb.appendLine("== $label (${lens.name})")
                sb.appendLine("  session keys (${keys.count { it.value == "session" }}), request keys (${keys.count { it.value == "request" }})")
                keys.forEach { (k, scope) ->
                    val star = if (INTERESTING.containsMatchIn(k)) " ★" else ""
                    if (star.isNotEmpty()) candidates += k
                    sb.appendLine("    $k · $scope$star")
                }
            }
        }
        sb.appendLine()
        sb.appendLine("★ candidates by name (${candidates.size}):")
        candidates.forEach { sb.appendLine("  $it") }
        return sb.toString()
    }

    /** Keys for one path+lens: name -> "session"/"request". Null if the path does not exist. */
    fun keysFor(path: String, lens: Lens): Pair<String, Map<String, String>>? {
        val direct = path == "direct"
        val chars = ArrayList<CameraCharacteristics>()
        try {
            if (direct) chars += cm.getCameraCharacteristics(lens.physicalId)
            else {
                val lc = cm.getCameraCharacteristics(path)
                if (lens.physicalId !in lc.physicalCameraIds) return null
                chars += lc; chars += cm.getCameraCharacteristics(lens.physicalId)
            }
        } catch (t: Throwable) { return null }
        val out = LinkedHashMap<String, String>()
        for (ch in chars) {
            val sess = runCatching { ch.availableSessionKeys?.map { it.name } }.getOrNull().orEmpty().toSet()
            val req = runCatching { ch.availableCaptureRequestKeys?.map { it.name } }.getOrNull().orEmpty()
            for (k in req) if (!k.startsWith("android.")) out[k] = if (k in sess) "session" else "request"
            for (k in sess) if (!k.startsWith("android.") && k !in out) out[k] = "session"
        }
        val label = if (direct) lens.physicalId else "$path/${lens.physicalId}"
        return label to out.toSortedMap()
    }

    fun candidateKeys(path: String, lens: Lens): List<Pair<String, String>> =
        keysFor(path, lens)?.second?.filter { INTERESTING.containsMatchIn(it.key) }?.map { it.key to it.value } ?: emptyList()

    /**
     * Active: for each candidate, open the camera, set key=1 (i32) as a session parameter and in the request,
     * grab one RAW frame, record what changed against a baseline run with no key.
     */
    @SuppressLint("MissingPermission")
    fun deepProbe(path: String, lens: Lens, keys: List<Pair<String, String>>, progress: (String) -> Unit): String {
        val sb = StringBuilder("LATENT DEEP PROBE · path=$path lens=${lens.physicalId} (${lens.name})\n")
        progress("baseline…")
        val base = runOne(path, lens, null)
        sb.appendLine("baseline: $base")
        keys.forEachIndexed { i, (k, scope) ->
            progress("${i + 1}/${keys.size} $k")
            val r = runOne(path, lens, k)
            val changed = if (r.ok && base.ok && (r.rawW != base.rawW || r.rawH != base.rawH || r.crop != base.crop || r.focal != base.focal)) " ← CHANGED" else ""
            sb.appendLine("$k ($scope): $r$changed")
        }
        return sb.toString()
    }

    data class Outcome(val ok: Boolean, val note: String, val rawW: Int = 0, val rawH: Int = 0, val crop: String = "", val focal: String = "", val echo: String = "") {
        override fun toString() = if (ok) "ok raw=${rawW}x$rawH crop=$crop focal=$focal echo=$echo" else "FAILED: $note"
    }

    @SuppressLint("MissingPermission")
    private fun runOne(path: String, lens: Lens, key: String?): Outcome {
        val direct = path == "direct"
        val physChars = try { cm.getCameraCharacteristics(lens.physicalId) } catch (t: Throwable) { return Outcome(false, "no characteristics") }
        val map = physChars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return Outcome(false, "no stream map")
        val rawSize = map.getOutputSizes(ImageFormat.RAW_SENSOR).maxByOrNull { it.width.toLong() * it.height } ?: return Outcome(false, "no RAW")
        val st = SurfaceTexture(0).apply { setDefaultBufferSize(1280, 960) }
        val previewSurface = Surface(st)
        val reader = ImageReader.newInstance(rawSize.width, rawSize.height, ImageFormat.RAW_SENSOR, 2)
        var device: CameraDevice? = null
        var session: CameraCaptureSession? = null
        var outcome = Outcome(false, "timeout")
        val done = CountDownLatch(1)
        var rawW = 0; var rawH = 0
        var result: TotalCaptureResult? = null
        val gotImage = CountDownLatch(1); val gotResult = CountDownLatch(1)
        reader.setOnImageAvailableListener({ r -> r.acquireNextImage()?.let { img -> rawW = img.width; rawH = img.height; img.close() }; gotImage.countDown() }, handler)

        fun finish(o: Outcome) { outcome = o; done.countDown() }
        fun setKey(b: CaptureRequest.Builder) { if (key != null) try { b.set(CaptureRequest.Key(key, Int::class.javaObjectType), 1) } catch (t: Throwable) { try { b.set(CaptureRequest.Key(key, Byte::class.javaObjectType), 1.toByte()) } catch (_: Throwable) {} } }

        try {
            cm.openCamera(if (direct) lens.physicalId else path, object : CameraDevice.StateCallback() {
                override fun onOpened(cam: CameraDevice) {
                    device = cam
                    try {
                        val oPrev = OutputConfiguration(previewSurface); val oRaw = OutputConfiguration(reader.surface)
                        if (!direct) { oPrev.setPhysicalCameraId(lens.physicalId); oRaw.setPhysicalCameraId(lens.physicalId) }
                        val cfg = SessionConfiguration(SessionConfiguration.SESSION_REGULAR, listOf(oPrev, oRaw), executor, object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(s: CameraCaptureSession) {
                                session = s
                                try {
                                    val prev = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(previewSurface); setKey(this) }
                                    s.setRepeatingRequest(prev.build(), null, handler)
                                    val still = cam.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply { addTarget(reader.surface); addTarget(previewSurface); setKey(this) }
                                    handler.postDelayed({
                                        try {
                                            s.capture(still.build(), object : CameraCaptureSession.CaptureCallback() {
                                                override fun onCaptureCompleted(ss: CameraCaptureSession, rq: CaptureRequest, res: TotalCaptureResult) { result = res; gotResult.countDown() }
                                                override fun onCaptureFailed(ss: CameraCaptureSession, rq: CaptureRequest, f: android.hardware.camera2.CaptureFailure) { finish(Outcome(false, "capture failed ${f.reason}")) }
                                            }, handler)
                                        } catch (t: Throwable) { finish(Outcome(false, "capture: ${t.message}")) }
                                    }, 600)
                                } catch (t: Throwable) { finish(Outcome(false, "request: ${t.message}")) }
                            }
                            override fun onConfigureFailed(s: CameraCaptureSession) { finish(Outcome(false, "session refused")) }
                        })
                        val sp = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW); setKey(sp); cfg.sessionParameters = sp.build()
                        cam.createCaptureSession(cfg)
                    } catch (t: Throwable) { finish(Outcome(false, "session: ${t.javaClass.simpleName} ${t.message}")) }
                }
                override fun onDisconnected(cam: CameraDevice) { finish(Outcome(false, "disconnected")) }
                override fun onError(cam: CameraDevice, error: Int) { finish(Outcome(false, "device error $error")) }
            }, handler)
        } catch (t: Throwable) { return Outcome(false, "open: ${t.message}") }

        // Wait for image + result (or a failure), max 6 s.
        val deadline = System.currentTimeMillis() + 6000
        while (System.currentTimeMillis() < deadline && done.count > 0) {
            if (gotImage.count == 0L && gotResult.count == 0L) {
                val r = result!!
                val pr = if (!direct) (r.physicalCameraResults[lens.physicalId] ?: r) else r
                val crop = pr.get(CaptureResult.SCALER_CROP_REGION)?.let { "${it.width()}x${it.height()}@${it.left},${it.top}" } ?: "?"
                val focal = pr.get(CaptureResult.LENS_FOCAL_LENGTH)?.toString() ?: "?"
                val echo = if (key == null) "" else (runCatching { pr.get(CaptureResult.Key(key, IntArray::class.java))?.joinToString() }.getOrNull()
                    ?: runCatching { pr.get(CaptureResult.Key(key, Int::class.javaObjectType))?.toString() }.getOrNull()
                    ?: runCatching { pr.get(CaptureResult.Key(key, ByteArray::class.java))?.joinToString() }.getOrNull() ?: "not reported")
                finish(Outcome(true, "", rawW, rawH, crop, focal, echo))
            }
            Thread.sleep(50)
        }
        try { session?.close() } catch (_: Throwable) {}
        try { device?.close() } catch (_: Throwable) {}
        reader.close(); previewSurface.release(); st.release()
        // Give the HAL a moment to fully release before the next open.
        Thread.sleep(400)
        return outcome
    }

    fun destroy() { thread.quitSafely() }
}
