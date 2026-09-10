package com.celestial.latent

import android.content.Context
import com.celestial.latent.camera.Lenses

/** User settings, persisted in SharedPreferences. Small on purpose. */
data class AppSettings(
    val antibanding: Int = ANTIBANDING_AUTO,   // Camera2 CONTROL_AE_ANTIBANDING_MODE values
    val gridlines: Boolean = true,
    val volumeShutter: Boolean = true,
    val defaultLensId: String = Lenses.DEFAULT.physicalId,
    val rememberLens: Boolean = true,
    val directOpen: Boolean = false,
    val saveJpeg: Boolean = false,      // RAW only, or RAW + JPEG side by side
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
                .apply()
        }
    }
}

/** Lets the Activity forward hardware volume keys to whatever screen owns the shutter. */
object ShutterBus {
    @Volatile var onShutter: (() -> Unit)? = null
}
