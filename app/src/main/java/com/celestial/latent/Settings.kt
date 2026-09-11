package com.celestial.latent

import android.content.Context
import com.celestial.latent.camera.Lenses

/** One vendor tag override, MotionCam-style. scope = "session" or "request"; type = i32/i64/f32/f64/u8. Arrays as comma/slash separated. */
data class VendorTag(val name: String, val scope: String, val type: String, val value: String) {
    fun encode() = listOf(name, scope, type, value).joinToString("\t")
    companion object {
        fun decode(s: String): VendorTag? { val p = s.split("\t"); return if (p.size == 4) VendorTag(p[0], p[1], p[2], p[3]) else null }
    }
}

/** User settings, persisted in SharedPreferences. Small on purpose. */
data class AppSettings(
    val antibanding: Int = ANTIBANDING_AUTO,   // Camera2 CONTROL_AE_ANTIBANDING_MODE values
    val gridlines: Boolean = true,
    val volumeShutter: Boolean = true,
    val defaultLensId: String = Lenses.DEFAULT.physicalId,
    val rememberLens: Boolean = true,
    val directOpen: Boolean = false,
    val saveJpeg: Boolean = false,      // RAW only, or RAW + JPEG side by side
    val cameraPath: String = "0",       // logical camera ID to route through ("0", "6", "7"...) or "direct"
    val opmode: Int = 0,                // vendor session operating mode; 0 = regular
    val vendorTags: List<VendorTag> = emptyList(),
    val inSensorZoomJpeg: Boolean = false,  // Qualcomm EnableInsensorZoom: native-crop JPEG at 2x (RAW stays 1x for now)
) {
    companion object {
        const val ANTIBANDING_OFF = 0
        const val ANTIBANDING_50HZ = 1
        const val ANTIBANDING_60HZ = 2
        const val ANTIBANDING_AUTO = 3

        private const val FILE = "latent"

        fun load(ctx: Context): AppSettings {
            val p = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            return AppSettings(
                antibanding = p.getInt("antibanding", ANTIBANDING_AUTO),
                gridlines = p.getBoolean("gridlines", true),
                volumeShutter = p.getBoolean("volumeShutter", true),
                defaultLensId = p.getString("defaultLensId", Lenses.DEFAULT.physicalId) ?: Lenses.DEFAULT.physicalId,
                rememberLens = p.getBoolean("rememberLens", true),
                directOpen = p.getBoolean("directOpen", false),
                saveJpeg = p.getBoolean("saveJpeg", false),
                cameraPath = p.getString("cameraPath", if (p.getBoolean("directOpen", false)) "direct" else "0") ?: "0",
                opmode = p.getInt("opmode", 0),
                vendorTags = (p.getString("vendorTags", "") ?: "").split("\n").mapNotNull { VendorTag.decode(it) },
                inSensorZoomJpeg = p.getBoolean("inSensorZoomJpeg", false),
            )
        }

        fun save(ctx: Context, s: AppSettings) {
            ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
                .putInt("antibanding", s.antibanding)
                .putBoolean("gridlines", s.gridlines)
                .putBoolean("volumeShutter", s.volumeShutter)
                .putString("defaultLensId", s.defaultLensId)
                .putBoolean("rememberLens", s.rememberLens)
                .putBoolean("directOpen", s.directOpen)
                .putBoolean("saveJpeg", s.saveJpeg)
                .putString("cameraPath", s.cameraPath)
                .putInt("opmode", s.opmode)
                .putString("vendorTags", s.vendorTags.joinToString("\n") { it.encode() })
                .putBoolean("inSensorZoomJpeg", s.inSensorZoomJpeg)
                .apply()
        }
    }
}

/** Lets the Activity forward hardware volume keys to whatever screen owns the shutter. */
object ShutterBus {
    @Volatile var onShutter: (() -> Unit)? = null
}
