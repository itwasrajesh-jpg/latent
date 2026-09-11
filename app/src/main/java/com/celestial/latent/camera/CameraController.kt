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
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.RggbChannelVector
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Log
import android.util.Range
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
class CameraController(
    private val context: Context,
    private val onStatus: (String) -> Unit,
    private val onLog: (String) -> Unit,
    private val onReadout: (LiveReadout) -> Unit = {},
) {

    private val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val thread = HandlerThread("latent-camera").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { r -> handler.post(r) }

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var rawReader: ImageReader? = null
    private var jpegReader: ImageReader? = null
    @Volatile var saveJpeg = false
    private val baseNames = HashMap<Long, String>()      // sensor timestamp -> file base name
    private val pendingJpegs = HashMap<Long, ByteArray>() // JPEGs that arrived before their RAW was named
    private var oisAvailable = false
    private var previewSurface: Surface? = null
    private var lens: Lens = Lenses.DEFAULT
    private var directOpen = false
    /** Camera path: logical camera ID to route through ("0", "6", "7"...) or "direct". */
    @Volatile var cameraPath: String = Lenses.LOGICAL_ID
    private var fallbackDirect = false
    private var logicalId: String = Lenses.LOGICAL_ID
    /** Vendor overrides (MotionCam-style). Applied at session creation / in every request. */
    @Volatile var opmode: Int = 0
    @Volatile var vendorTags: List<VendorTagSpec> = emptyList()
    @Volatile var onVendorEcho: (String) -> Unit = {}
    private lateinit var physChars: CameraCharacteristics
    private var rawSize = Size(4096, 3072)

    // Pairing of RAW images with their metadata (matched on sensor timestamp).
    private val pendingResults = HashMap<Long, TotalCaptureResult>()
    private val pendingImages = HashMap<Long, Image>()

    // Burst state
    private var burst: BurstJob? = null

    // Control strip state
    @Volatile var controls: Controls = Controls(); private set
    private var lastAutoShutterNs = 10_000_000L
    private var lastAutoIso = 100
    private var lastTransform: ColorSpaceTransform? = null
    private var lastGains: RggbChannelVector? = null
    private var afRegion: MeteringRectangle? = null
    private var afTriggerPending = false
    private var frameCounter = 0
    @Volatile var antibanding: Int = CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_AUTO

    /** Ranges of the current lens, for the sliders. */
    val shutterRange: Range<Long> get() = physChars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE) ?: Range(100_000L, 1_000_000_000L)
    val isoRange: Range<Int> get() = physChars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) ?: Range(100, 3200)
    val minFocusDiopters: Float get() = physChars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 10f
    val evRange: Range<Int> get() = physChars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: Range(-12, 12)
    val hasCharacteristics get() = this::physChars.isInitialized

    val currentLens get() = lens

    fun previewSizeFor(lens: Lens): Size {
        val ch = cm.getCameraCharacteristics(lens.physicalId)
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        val sizes = map.getOutputSizes(android.graphics.SurfaceTexture::class.java)
        return sizes.filter { it.width * 3 == it.height * 4 && it.width <= 1920 }.maxByOrNull { it.width } ?: Size(1440, 1080)
    }

    /** Open the chosen lens. Preview surface must already exist. */
    @SuppressLint("MissingPermission")
    fun open(lens: Lens, surface: Surface, attempt: Int = 0) {
        handler.post {
            closeInternal()
            this.lens = lens
            this.previewSurface = surface
            directOpen = cameraPath == "direct" || fallbackDirect
            logicalId = if (cameraPath == "direct") Lenses.LOGICAL_ID else cameraPath
            try {
                physChars = cm.getCameraCharacteristics(lens.physicalId)
                afRegion = null; afTriggerPending = false; lastTransform = null; lastGains = null
                val map = physChars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
                rawSize = map.getOutputSizes(ImageFormat.RAW_SENSOR).maxByOrNull { it.width.toLong() * it.height } ?: rawSize
                rawReader = ImageReader.newInstance(rawSize.width, rawSize.height, ImageFormat.RAW_SENSOR, 6).also {
                    it.setOnImageAvailableListener({ r -> onRawImage(r) }, handler)
                }
                jpegReader = if (saveJpeg) {
                    val jpegSize = map.getOutputSizes(ImageFormat.JPEG).maxByOrNull { it.width.toLong() * it.height } ?: rawSize
                    ImageReader.newInstance(jpegSize.width, jpegSize.height, ImageFormat.JPEG, 2).also {
                        it.setOnImageAvailableListener({ r -> onJpegImage(r) }, handler)
                    }
                } else null
                oisAvailable = physChars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)?.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON) == true
                status("Opening ${lens.name} (${lens.label}) · RAW ${rawSize.width}x${rawSize.height}")
                val idToOpen = if (directOpen) lens.physicalId else logicalId
                cm.openCamera(idToOpen, object : CameraDevice.StateCallback() {
                    override fun onOpened(cam: CameraDevice) { device = cam; createSession() }
                    override fun onDisconnected(cam: CameraDevice) { log("camera disconnected"); cam.close(); device = null }
                    override fun onError(cam: CameraDevice, error: Int) { status("Camera error $error"); cam.close(); device = null }
                }, handler)
            } catch (e: Exception) {
                // Typically the camera service restarting after a driver crash: IDs vanish for a moment.
                if (attempt < 10) {
                    status("Camera service restarting… (${attempt + 1}/10)")
                    log("open failed: ${e.javaClass.simpleName} ${e.message}")
                    handler.postDelayed({ open(lens, surface, attempt + 1) }, 1500)
                } else {
                    status("Camera unavailable: ${e.message}. Remove any vendor tag the lens rejects and switch lenses again.")
                }
            }
        }
    }

    private fun createSession() {
        val dev = device ?: return
        val prev = previewSurface ?: return
        val reader = rawReader ?: return
        val outputs = ArrayList<OutputConfiguration>()
        outputs += OutputConfiguration(prev)
        outputs += OutputConfiguration(reader.surface)
        jpegReader?.let { outputs += OutputConfiguration(it.surface) }
        if (!directOpen) outputs.forEach { it.setPhysicalCameraId(lens.physicalId) }
        val sessionType = if (opmode != 0) opmode else SessionConfiguration.SESSION_REGULAR
        val config = SessionConfiguration(sessionType, outputs, executor, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) { session = s; startPreview() }
            override fun onConfigureFailed(s: CameraCaptureSession) {
                if (!directOpen) {
                    log("session via logical camera failed; retrying with direct open of ${lens.physicalId}")
                    fallbackDirect = true
                    val surf = previewSurface!!
                    handler.post { open(lens, surf) }
                } else status("Session configuration failed for ${lens.name}")
            }
        })
        try {
            val sessionTags = vendorTags.filter { it.scope == "session" && it.name.isNotBlank() }
            if (sessionTags.isNotEmpty()) {
                val sp = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                sessionTags.forEach { applyVendorTag(sp, it) }
                config.sessionParameters = sp.build()
            }
            if (opmode != 0) log("session opmode 0x${Integer.toHexString(opmode)}")
            dev.createCaptureSession(config)
        } catch (e: Exception) {
            if (!directOpen) {
                log("createCaptureSession threw (${e.message}); retrying with direct open of ${lens.physicalId}")
                fallbackDirect = true
                val surf = previewSurface!!
                handler.post { open(lens, surf) }
            } else status("createCaptureSession: ${e.message}")
        }
    }

    private fun startPreview() {
        val dev = device ?: return; val s = session ?: return; val prev = previewSurface ?: return
        val req = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(prev)
            applyControls(this)
        }
        try {
            s.setRepeatingRequest(req.build(), previewCallback, handler)
            status("${lens.name} · ${lens.label} · ${if (directOpen) "direct" else "via $logicalId"} · ${if (oisAvailable) "OIS on" else "no OIS"} · streams: preview+RAW${if (jpegReader != null) "+JPEG" else ""} · tap = RAW, hold = burst")
        } catch (e: Exception) { status("preview failed: ${e.message}") }
    }

    /** Re-issue the repeating preview request with the current controls. */
    private fun updatePreview() {
        val dev = device ?: return; val s = session ?: return; val prev = previewSurface ?: return
        try {
            val req = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(prev); applyControls(this) }
            if (afTriggerPending) {
                // One-shot AF trigger, then the repeating request continues without it.
                afTriggerPending = false
                val trig = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(prev); applyControls(this)
                    set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
                }
                s.capture(trig.build(), previewCallback, handler)
            }
            s.setRepeatingRequest(req.build(), previewCallback, handler)
        } catch (e: Exception) { status("update failed: ${e.message}") }
    }

    fun setControls(c: Controls) = handler.post { controls = c; updatePreview() }
    fun setAntibanding(mode: Int) = handler.post { antibanding = mode; updatePreview() }

    /** Viewfinder point (u right, v down, both 0..1) -> metering rectangle in sensor coordinates. */
    private fun regionFor(u: Float, v: Float): MeteringRectangle? {
        val active = physChars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return null
        val orientation = physChars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        // Undo the sensor->display rotation. 90: display (u,v) came from sensor (x=v, y=1-u).
        val (sx, sy) = when (orientation) {
            90 -> v to (1f - u)
            270 -> (1f - v) to u
            180 -> (1f - u) to (1f - v)
            else -> u to v
        }
        val half = 0.06f
        val l = ((sx - half) * active.width()).toInt().coerceIn(0, active.width() - 2)
        val t = ((sy - half) * active.height()).toInt().coerceIn(0, active.height() - 2)
        val w = (2 * half * active.width()).toInt().coerceAtLeast(2).coerceAtMost(active.width() - l)
        val h = (2 * half * active.height()).toInt().coerceAtLeast(2).coerceAtMost(active.height() - t)
        val r = MeteringRectangle(l, t, w, h, MeteringRectangle.METERING_WEIGHT_MAX - 1)
        log("AF region for tap (%.2f, %.2f) -> sensor [%d,%d %dx%d] of %dx%d, orientation %d".format(u, v, l, t, w, h, active.width(), active.height(), orientation))
        return r
    }

    /** Tap-to-focus. Also clears any AE/AF lock. */
    fun tapFocus(u: Float, v: Float) = handler.post {
        afRegion = regionFor(u, v) ?: return@post
        afTriggerPending = true
        controls = controls.copy(focusDiopters = null, locked = false)
        updatePreview()
    }

    /** Long-press: focus at the point, then lock exposure and hold focus there. */
    fun lockAt(u: Float, v: Float) = handler.post {
        afRegion = regionFor(u, v) ?: return@post
        afTriggerPending = true
        controls = controls.copy(focusDiopters = null, locked = true)
        updatePreview()
    }

    fun unlock() = handler.post {
        afRegion = null
        controls = controls.copy(locked = false)
        updatePreview()
    }

    /** Applies the control strip to any request (preview or still). */
    private fun applyControls(b: CaptureRequest.Builder) {
        val c = controls
        b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        b.set(CaptureRequest.CONTROL_ZOOM_RATIO, c.zoom)
        // Exposure
        if (c.manualExposure) {
            val exp = c.shutterNs ?: lastAutoShutterNs
            val iso = c.iso ?: lastAutoIso
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exp.coerceIn(shutterRange.lower, shutterRange.upper))
            b.set(CaptureRequest.SENSOR_SENSITIVITY, iso.coerceIn(isoRange.lower, isoRange.upper))
            b.set(CaptureRequest.SENSOR_FRAME_DURATION, maxOf(33_333_333L, exp))
        } else {
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, c.evIndex.coerceIn(evRange.lower, evRange.upper))
            b.set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, antibanding)
            b.set(CaptureRequest.CONTROL_AE_LOCK, c.locked)
        }
        // Keep the preview and still pipelines identical so a capture does not visibly re-configure anything.
        b.set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE, CameraMetadata.STATISTICS_LENS_SHADING_MAP_MODE_ON)
        // Image quality: optical stabilisation on where the lens has it; best hot-pixel correction; no EIS (crops, stills don't need it).
        b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, if (oisAvailable) CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON else CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF)
        b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
        physChars.get(CameraCharacteristics.HOT_PIXEL_AVAILABLE_HOT_PIXEL_MODES)?.let { modes ->
            if (modes.contains(CameraMetadata.HOT_PIXEL_MODE_HIGH_QUALITY)) b.set(CaptureRequest.HOT_PIXEL_MODE, CameraMetadata.HOT_PIXEL_MODE_HIGH_QUALITY)
        }
        // Vendor tags: session-scoped ones are sent again in requests too (harmless, and some HALs read them there).
        vendorTags.filter { it.name.isNotBlank() }.forEach { applyVendorTag(b, it) }
        // White balance
        val k = c.kelvin
        if (k != null) {
            b.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
            b.set(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
            b.set(CaptureRequest.COLOR_CORRECTION_GAINS, ControlMath.gainsForKelvin(physChars, k))
            b.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, lastTransform ?: ColorSpaceTransform(intArrayOf(1, 1, 0, 1, 0, 1, 0, 1, 1, 1, 0, 1, 0, 1, 0, 1, 1, 1)))
        } else {
            b.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
            b.set(CaptureRequest.CONTROL_AWB_LOCK, c.locked)
        }
        // Focus
        val f = c.focusDiopters
        if (f != null) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            b.set(CaptureRequest.LENS_FOCUS_DISTANCE, f.coerceIn(0f, minFocusDiopters))
        } else {
            val region = afRegion
            if (region != null) {
                b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
                b.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(region))
                b.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(region))
            } else {
                b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            }
        }
    }

    /** Reads what the camera actually did, so "A" can show real numbers and manual modes start from them. */
    private val previewCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(sess: CameraCaptureSession, req: CaptureRequest, result: TotalCaptureResult) {
            val r = metaFor(result)
            val c = controls
            r.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { if (!c.manualExposure) lastAutoShutterNs = it }
            r.get(CaptureResult.SENSOR_SENSITIVITY)?.let { if (!c.manualExposure) lastAutoIso = it }
            if (c.kelvin == null) {
                r.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)?.let { lastTransform = it }
                r.get(CaptureResult.COLOR_CORRECTION_GAINS)?.let { lastGains = it }
            }
            if (++frameCounter % 6 == 0) {
                val gains = lastGains
                val af = when (r.get(CaptureResult.CONTROL_AF_STATE)) {
                    CameraMetadata.CONTROL_AF_STATE_INACTIVE -> "inactive"
                    CameraMetadata.CONTROL_AF_STATE_PASSIVE_SCAN -> "scanning"
                    CameraMetadata.CONTROL_AF_STATE_PASSIVE_FOCUSED -> "focused"
                    CameraMetadata.CONTROL_AF_STATE_ACTIVE_SCAN -> "scanning*"
                    CameraMetadata.CONTROL_AF_STATE_FOCUSED_LOCKED -> "locked ✓"
                    CameraMetadata.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED -> "locked ✗"
                    CameraMetadata.CONTROL_AF_STATE_PASSIVE_UNFOCUSED -> "unfocused"
                    else -> "?"
                }
                onReadout(LiveReadout(
                    shutterNs = r.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0,
                    iso = r.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0,
                    focusDiopters = r.get(CaptureResult.LENS_FOCUS_DISTANCE) ?: 0f,
                    kelvinEstimate = if (gains != null && frameCounter % 30 == 0) ControlMath.kelvinForGains(physChars, gains) else -1,
                    afState = af,
                ))
            }
        }
    }

    private fun stillRequest(withJpeg: Boolean = false): CaptureRequest.Builder {
        val dev = device!!
        return dev.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
            addTarget(rawReader!!.surface)
            if (withJpeg) jpegReader?.let { addTarget(it.surface) }
            previewSurface?.let { addTarget(it) }
            applyControls(this)
            set(CaptureRequest.JPEG_ORIENTATION, physChars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90)
            set(CaptureRequest.JPEG_QUALITY, 100.toByte())
        }
    }

    fun captureSingle() = handler.post {
        val s = session ?: return@post
        try {
            val t0 = System.nanoTime()
            s.capture(stillRequest(withJpeg = saveJpeg).build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(sess: CameraCaptureSession, req: CaptureRequest, result: TotalCaptureResult) {
                    onResult(result, t0)
                    echoVendorTags(result)
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
                val base = fileBase()
                baseNames[img.timestamp] = base
                pendingJpegs.remove(img.timestamp)?.let { saveJpegBytes(base, it) }
                if (baseNames.size > 8) baseNames.remove(baseNames.keys.minOrNull()!!)
                val name = "$base.dng"
                val ms = writeDngCreator(img, result, name)
                metaFor(result).get(CaptureResult.SCALER_CROP_REGION)?.let { cr -> if (controls.zoom != 1f) log("zoom ${controls.zoom}x → crop region ${cr.width()}x${cr.height()}@${cr.left},${cr.top} (full frame = in-sensor, quarter = digital)") }
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
        creator.setDescription("Latent single RAW - ${lens.name} ${lens.label}")
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

    private fun fileName(kind: String): String = fileBase(kind) + ".dng"

    private fun fileBase(kind: String = "RAW"): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return "LATENT_${stamp}_${lens.label.replace(".", "_")}_$kind"
    }

    private fun onJpegImage(reader: ImageReader) {
        val img = reader.acquireNextImage() ?: return
        try {
            val buf = img.planes[0].buffer
            val bytes = ByteArray(buf.remaining()); buf.get(bytes)
            val base = baseNames[img.timestamp]
            if (base != null) saveJpegBytes(base, bytes) else pendingJpegs[img.timestamp] = bytes
        } catch (e: Exception) { log("jpeg: ${e.message}") } finally { img.close() }
    }

    private fun saveJpegBytes(base: String, bytes: ByteArray) {
        try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "$base.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/Latent")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return
            resolver.openOutputStream(uri)!!.use { it.write(bytes) }
            values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            log("saved $base.jpg (${bytes.size / 1024} KB)")
        } catch (e: Exception) { log("jpeg save: ${e.message}") }
    }

    // ---- vendor tags ----------------------------------------------------------------

    data class VendorTagSpec(val name: String, val scope: String, val type: String, val value: String)

    private fun parseNums(v: String): List<String> = v.split(',', '/', ' ').map { it.trim() }.filter { it.isNotEmpty() }

    /** Sets one vendor key on a request builder; logs if the driver rejects the key or type. */
    private fun applyVendorTag(b: CaptureRequest.Builder, t: VendorTagSpec) {
        try {
            val parts = parseNums(t.value)
            val isArray = parts.size > 1
            when (t.type) {
                "i32" -> if (isArray) b.set(CaptureRequest.Key(t.name, IntArray::class.java), parts.map { it.toInt() }.toIntArray())
                         else b.set(CaptureRequest.Key(t.name, Int::class.javaObjectType), parts[0].toInt())
                "i64" -> if (isArray) b.set(CaptureRequest.Key(t.name, LongArray::class.java), parts.map { it.toLong() }.toLongArray())
                         else b.set(CaptureRequest.Key(t.name, Long::class.javaObjectType), parts[0].toLong())
                "f32" -> if (isArray) b.set(CaptureRequest.Key(t.name, FloatArray::class.java), parts.map { it.toFloat() }.toFloatArray())
                         else b.set(CaptureRequest.Key(t.name, Float::class.javaObjectType), parts[0].toFloat())
                "f64" -> if (isArray) b.set(CaptureRequest.Key(t.name, DoubleArray::class.java), parts.map { it.toDouble() }.toDoubleArray())
                         else b.set(CaptureRequest.Key(t.name, Double::class.javaObjectType), parts[0].toDouble())
                "u8" -> if (isArray) b.set(CaptureRequest.Key(t.name, ByteArray::class.java), parts.map { it.toInt().toByte() }.toByteArray())
                        else b.set(CaptureRequest.Key(t.name, Byte::class.javaObjectType), parts[0].toInt().toByte())
            }
        } catch (e: Exception) { log("vendor tag ${t.name} rejected: ${e.javaClass.simpleName} ${e.message}") }
    }

    /** Reads back every configured tag from the capture result so the user can see what the driver actually did. */
    private fun echoVendorTags(result: TotalCaptureResult) {
        val tags = vendorTags.filter { it.name.isNotBlank() }
        if (tags.isEmpty() && opmode == 0) return
        val sb = StringBuilder()
        if (opmode != 0) sb.appendLine("opmode 0x${Integer.toHexString(opmode)} · session ${if (directOpen) "direct" else "via $logicalId"}")
        for (t in tags) {
            val v: Any? = try {
                val r = metaFor(result)
                when (t.type) {
                    "i32" -> r.get(CaptureResult.Key(t.name, IntArray::class.java))?.joinToString() ?: r.get(CaptureResult.Key(t.name, Int::class.javaObjectType))
                    "i64" -> r.get(CaptureResult.Key(t.name, LongArray::class.java))?.joinToString() ?: r.get(CaptureResult.Key(t.name, Long::class.javaObjectType))
                    "f32" -> r.get(CaptureResult.Key(t.name, FloatArray::class.java))?.joinToString() ?: r.get(CaptureResult.Key(t.name, Float::class.javaObjectType))
                    "f64" -> r.get(CaptureResult.Key(t.name, DoubleArray::class.java))?.joinToString() ?: r.get(CaptureResult.Key(t.name, Double::class.javaObjectType))
                    else -> r.get(CaptureResult.Key(t.name, ByteArray::class.java))?.joinToString() ?: r.get(CaptureResult.Key(t.name, Byte::class.javaObjectType))
                }
            } catch (e: Exception) { "<${e.javaClass.simpleName}>" }
            sb.appendLine("${t.name.substringAfterLast('.')} (${t.scope}/${t.type}) sent=${t.value} → result=${v ?: "not reported"}")
        }
        val text = sb.toString().trim()
        log(text); onVendorEcho(text)
    }

    /** Vendor keys the driver advertises for the current lens/path: name -> session/request/both. */
    fun exposedVendorKeys(): List<Pair<String, String>> {
        if (!hasCharacteristics) return emptyList()
        val chars = listOfNotNull(physChars, if (!directOpen) runCatching { cm.getCameraCharacteristics(logicalId) }.getOrNull() else null)
        val out = LinkedHashMap<String, String>()
        for (ch in chars) {
            val sess = runCatching { ch.availableSessionKeys?.map { it.name } }.getOrNull().orEmpty().toSet()
            val req = runCatching { ch.availableCaptureRequestKeys?.map { it.name } }.getOrNull().orEmpty()
            for (k in req) if (!k.startsWith("android.")) out[k] = if (k in sess) "session" else "request"
            for (k in sess) if (!k.startsWith("android.") && k !in out) out[k] = "session"
        }
        return out.entries.map { it.key to it.value }.sortedBy { it.first }
    }

    // ---- lifecycle ----------------------------------------------------------------

    fun close() = handler.post { closeInternal() }

    private fun closeInternal() {
        try { session?.close() } catch (_: Exception) {}
        session = null
        try { device?.close() } catch (_: Exception) {}
        device = null
        rawReader?.close(); rawReader = null
        jpegReader?.close(); jpegReader = null
        baseNames.clear(); pendingJpegs.clear()
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
