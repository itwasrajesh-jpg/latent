package com.celestial.latent

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Predefined haptic effects; respects the app's own Haptics setting. */
object Haptics {
    @Volatile var enabled = true

    private fun vibrator(ctx: Context): Vibrator? = runCatching {
        if (android.os.Build.VERSION.SDK_INT >= 31) (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        else @Suppress("DEPRECATION") ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }.getOrNull()

    private fun play(ctx: Context, effect: Int) {
        if (!enabled) return
        val v = vibrator(ctx) ?: return
        if (!v.hasVibrator()) return
        runCatching { v.vibrate(VibrationEffect.createPredefined(effect)) }
    }

    fun click(ctx: Context) = play(ctx, VibrationEffect.EFFECT_CLICK)
    fun heavy(ctx: Context) = play(ctx, VibrationEffect.EFFECT_HEAVY_CLICK)
    fun tick(ctx: Context) = play(ctx, VibrationEffect.EFFECT_TICK)
    fun double(ctx: Context) = play(ctx, VibrationEffect.EFFECT_DOUBLE_CLICK)
}
