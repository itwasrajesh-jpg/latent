package com.celestial.latent.camera

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.Surface
import android.media.ExifInterface
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor

/**
 * Camera2 controller for step 2b/2c:
 *  - opens logical camera 0 and routes preview + RAW to one physical lens,
 *  - single-frame DNG capture (Android's DngCreator, full metadata),
 *  - 16-frame RAW burst: measures sensor timing, saves frame 1 and the plain average.
 *
 * All camera callbacks run on one background thread; UI gets plain strings via [onStatus].
 */
class CameraController(private val context: Context, private val onStatus: (String) -> Unit, private val onLog: (String) -> Unit) {

    private val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val thread = HandlerThread("latent-camera").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { r -> handler.post(r) }

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var rawReader: ImageReader? = null
    private var previewSurface: Surface? = null
    private var lens: Lens = Lenses.DEFAULT
    private var directOpen = false
    private lateinit var physChars: CameraCharacteristics
    private var rawSize = Size(4096, 3072)

    // Pairing of RAW images with their metadata (matched on sensor timestamp).
    private val pendingResults = HashMap<Long, TotalCaptureResult>()
    private val pendingImages = HashMap<Long, Image>()

    // Burst state
    private var burst: BurstJob? = null

    val currentLens get() = lens

    fun previewSizeFor(lens: Lens): Size {
        val ch = cm.getCameraCharacteristics(lens.physicalId)
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        val sizes = map.getOutputSizes(android.view.SurfaceHolder::class.java)
        return sizes.filter { it.width * 3 == it.height * 4 && it.width <= 1920 }.maxByOrNull { it.width } ?: Size(1440, 1080)
    }

    /** Open the chosen lens. Preview surface must already exist. */
    @SuppressLint("MissingPermission")
    fun open(lens: Lens, surface: Surface) {
        handler.post {
            closeInternal()
            this.lens = lens
            this.previewSurface = surface
            physChars = cm.getCameraCharacteristics(lens.physicalId)
            val map = physChars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
            rawSize = map.getOutputSizes(ImageFormat.RAW_SENSOR).maxByOrNull { it.width.toLong() * it.height } ?: rawSize
            rawReader = ImageReader.newInstance(rawSize.width, rawSize.height, ImageFormat.RAW_SENSOR, 6).also {
                it.setOnImageAvailableListener({ r -> onRawImage(r) }, handler)
            }
            status("Opening ${lens.name} (${lens.label}) · RAW ${rawSize.width}x${rawSize.height}")
            val idToOpen = if (directOpen) lens.physicalId else Lenses.LOGICAL_ID
            try {
                cm.openCamera(idToOpen, object : CameraDevice.StateCallback() {
                    override fun onOpened(cam: CameraDevice) { device = cam; createSession() }
                    override fun onDisconnected(cam: CameraDevice) { log("camera disconnected"); cam.close(); device = null }
                    override fun onError(cam: CameraDevice, error: Int) { status("Camera error $error"); cam.close(); device = null }
                }, handler)
            } catch (e: Exception) {
                status("openCamera failed: ${e.message}")
            }
        }
    }

    private fun createSession() {
        val dev = device ?: return
        val prev = previewSurface ?: return
        val reader = rawReader ?: return
        val outPrev = OutputConfiguration(prev)
        val outRaw = OutputConfiguration(reader.surface)
        if (!directOpen) {
            outPrev.setPhysicalCameraId(lens.physicalId)
            outRaw.setPhysicalCameraId(lens.physicalId)
        }
        val config = SessionConfiguration(SessionConfiguration.SESSION_REGULAR, listOf(outPrev, outRaw), executor, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) { session = s; startPreview() }
            override fun onConfigureFailed(s: CameraCaptureSession) {
                if (!directOpen) {
                    log("session via logical camera failed; retrying with direct open of ${lens.physicalId}")
                    directOpen = true
                    val surf = previewSurface!!
                    handler.post { open(lens, surf) }
                } else status("Session configuration failed for ${lens.name}")
            }
        })
        try { dev.createCaptureSession(config) } catch (e: Exception) {
            if (!directOpen) {
                log("createCaptureSession threw (${e.message}); retrying with direct open of ${lens.physicalId}")
                directOpen = true
                val surf = previewSurface!!
                handler.post { open(lens, surf) }
            } else status("createCaptureSession: ${e.message}")
        }
    }

    private fun startPreview() {
        val dev = device ?: return; val s = session ?: return; val prev = previewSurface ?: return
        val req = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(prev)
            set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
        }
        try {
            s.setRepeatingRequest(req.build(), null, handler)
            status("${lens.name} · ${lens.label} · ${if (directOpen) "direct" else "via logical 0"} · tap = RAW, hold = 16-frame burst")
        } catch (e: Exception) { status("preview failed: ${e.message}") }
    }

    private fun stillRequest(): CaptureRequest.Builder {
        val dev = device!!
        return dev.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
            addTarget(rawReader!!.surface)
            previewSurface?.let { addTarget(it) }
            set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
            // Ask for the lens shading map so the DNG carries the vignetting correction (like Xiaomi's files).
            set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE, CameraMetadata.STATISTICS_LENS_SHADING_MAP_MODE_ON)
            set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_OFF)
        }
    }

    fun captureSingle() = handler.post {
        val s = session ?: return@post
        try {
            val t0 = System.nanoTime()
            s.capture(stillRequest().build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(sess: CameraCaptureSession, req: CaptureRequest, result: TotalCaptureResult) {
                    onResult(result, t0)
                }
                override fun onCaptureFailed(sess: CameraCaptureSession, req: CaptureRequest, failure: android.hardware.camera2.CaptureFailure) {
                    status("capture failed (reason ${failure.reason})")
                }
            }, handler)
            status("Capturing…")
        } catch (e: Exception) { status("capture: ${e.message}") }
    }

    fun captureBurst(frames: Int = 16) = handler.post {
        val s = session ?: return@post
        if (burst != null) { status("burst already running"); return@post }
        try {
            val job = BurstJob(frames, rawSize.width, rawSize.height, System.nanoTime())
            burst = job
            val reqs = List(frames) { stillRequest().build() }
            s.captureBurst(reqs, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(sess: CameraCaptureSession, req: CaptureRequest, result: TotalCaptureResult) { onResult(result, null) }
                override fun onCaptureFailed(sess: CameraCaptureSession, req: CaptureRequest, failure: android.hardware.camera2.CaptureFailure) {
                    log("burst frame failed reason=${failure.reason}"); job.failed++
                    if (job.received + job.failed >= job.frames) finishBurst(job)
                }
            }, handler)
            status("Burst of $frames… hold still")
        } catch (e: Exception) { burst = null; status("burst: ${e.message}") }
    }

    // ---- pairing ----------------------------------------------------------------

    private fun onResult(result: TotalCaptureResult, t0: Long?) {
        val ts = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
        val img = pendingImages.remove(ts)
        if (img != null) handle(img, result, t0) else pendingResults[ts] = result
    }

    private fun onRawImage(reader: ImageReader) {
        val img = reader.acquireNextImage() ?: return
        val ts = img.timestamp
        val res = pendingResults.remove(ts)
        if (res != null) handle(img, res, null) else {
            pendingImages[ts] = img
            if (pendingImages.size > 4) { // never let the reader starve
                val oldest = pendingImages.keys.minOrNull()!!
                pendingImages.remove(oldest)?.close(); log("dropped unmatched frame $oldest")
            }
        }
    }

    /** Metadata for the physical lens if the logical camera reports it, else the logical result. */
    private fun metaFor(result: TotalCaptureResult): CaptureResult =
        if (directOpen) result else (result.physicalCameraResults[lens.physicalId] ?: result)

    private fun handle(img: Image, result: TotalCaptureResult, t0: Long?) {
        val job = burst
        try {
            if (job == null) {
                val name = fileName("RAW")
                val ms = writeDngCreator(img, result, name)
                val took = t0?.let { (System.nanoTime() - it) / 1_000_000 } ?: -1
                status("Saved $name (${img.width}x${img.height}) · shutter→file ${took} ms · write $ms ms")
            } else {
                job.accept(img, result)
                if (job.received + job.failed >= job.frames) finishBurst(job)
            }
        } catch (e: Exception) {
            status("save failed: ${e.message}"); Log.e("Latent", "save", e)
        } finally { img.close() }
    }

    private fun finishBurst(job: BurstJob) {
        burst = null
        handler.post {
            try {
                val sensorMs = if (job.lastTs > 0 && job.firstTs > 0) (job.lastTs - job.firstTs) / 1_000_000 else -1
                val wallMs = (System.nanoTime() - job.startNs) / 1_000_000
                val avgName = fileName("STACK${job.received}")
                val meta = DngWriter.metaFrom(physChars, job.firstResult?.let { metaFor(it) }, job.w, job.h,
                    black = 64 * 16, white = 1023 * 16, orientation = 6,
                    description = "Latent burst average of ${job.received} frames, sensor span $sensorMs ms")
                val pixels = job.averageTimes16()
                val t = System.nanoTime()
                saveTo(avgName) { DngWriter.write(it, meta, pixels) }
                val wms = (System.nanoTime() - t) / 1_000_000
                status("Burst: ${job.received}/${job.frames} frames (${job.failed} failed) · sensor span $sensorMs ms · total $wallMs ms · saved ${job.firstName} + $avgName (write $wms ms)")
                onLog("burst frame timestamps ms from first: " + job.tsList.joinToString { ((it - job.firstTs) / 1_000_000).toString() })
            } catch (e: Exception) { status("burst save failed: ${e.message}"); Log.e("Latent", "burst", e) }
        }
    }

    // ---- writing ------------------------------------------------------------------

    private fun writeDngCreator(img: Image, result: TotalCaptureResult, name: String): Long {
        val creator = DngCreator(physChars, metaFor(result))
        creator.setOrientation(ExifInterface.ORIENTATION_ROTATE_90)
        creator.setDescription("Latent single RAW · ${lens.name} ${lens.label}")
        val t = System.nanoTime()
        saveTo(name) { creator.writeImage(it, img) }
        creator.close()
        return (System.nanoTime() - t) / 1_000_000
    }

    private fun saveTo(name: String, block: (OutputStream) -> Unit) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/x-adobe-dng")
            put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/Latent")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: throw IllegalStateException("MediaStore insert failed")
        resolver.openOutputStream(uri)!!.use(block)
        values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
    }

    private fun fileName(kind: String): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return "LATENT_${stamp}_${lens.label.replace(".", "_")}_$kind.dng"
    }

    // ---- lifecycle ----------------------------------------------------------------

    fun close() = handler.post { closeInternal() }

    private fun closeInternal() {
        try { session?.close() } catch (_: Exception) {}
        session = null
        try { device?.close() } catch (_: Exception) {}
        device = null
        rawReader?.close(); rawReader = null
        pendingImages.values.forEach { it.close() }; pendingImages.clear(); pendingResults.clear()
        burst = null
    }

    fun destroy() { close(); handler.post { thread.quitSafely() } }

    private fun status(s: String) { Log.i("Latent", s); onStatus(s) }
    private fun log(s: String) { Log.i("Latent", s); onLog(s) }

    /** Accumulates a burst: sums 16-bit CFA samples into ints, keeps frame 1 as a normal DNG. */
    private inner class BurstJob(val frames: Int, val w: Int, val h: Int, val startNs: Long) {
        var received = 0; var failed = 0
        var firstTs = 0L; var lastTs = 0L
        var firstResult: TotalCaptureResult? = null
        var firstName = ""
        val tsList = ArrayList<Long>()
        private val sum = IntArray(w * h)

        fun accept(img: Image, result: TotalCaptureResult) {
            val ts = img.timestamp
            tsList += ts
            if (received == 0) {
                firstTs = ts; firstResult = result
                firstName = fileName("BURST1")
                writeDngCreator(img, result, firstName)
            }
            lastTs = ts
            val plane = img.planes[0]
            val buf = plane.buffer
            val rowStride = plane.rowStride
            val sb = buf.order(java.nio.ByteOrder.nativeOrder()).asShortBuffer()
            val row = ShortArray(w)
            for (y in 0 until h) {
                sb.position(y * rowStride / 2)
                sb.get(row, 0, w)
                val base = y * w
                for (x in 0 until w) sum[base + x] += row[x].toInt() and 0xFFFF
            }
            received++
            status("Burst: frame $received/$frames")
        }

        /** Average scaled by 16 (so 10-bit input becomes a 14-bit-range file), clamped to 16 bits. */
        fun averageTimes16(): ShortArray {
            val n = received.coerceAtLeast(1)
            val out = ShortArray(w * h)
            for (i in out.indices) {
                val v = (sum[i].toLong() * 16 + n / 2) / n
                out[i] = v.coerceAtMost(65535).toInt().toShort()
            }
            return out
        }
    }
}
