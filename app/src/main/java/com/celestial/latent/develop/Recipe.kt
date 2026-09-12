package com.celestial.latent.develop

import android.content.Context
import com.spectrafilm.engine.CameraParams
import com.spectrafilm.engine.DiffusionFilterParams
import com.spectrafilm.engine.EnlargerParams
import com.spectrafilm.engine.FilmRenderingParams
import com.spectrafilm.engine.GlareParams
import com.spectrafilm.engine.GrainParams
import com.spectrafilm.engine.HalationParams
import com.spectrafilm.engine.PrintRenderingParams
import com.spectrafilm.engine.ScannerParams
import com.spectrafilm.engine.SpektraParams
import org.json.JSONObject

/**
 * The settings a photo is developed with. A flat, saveable set of the engine parameters that
 * actually change the picture — everything else keeps the film profile's own defaults.
 */
data class Recipe(
    val film: String = "kodak_portra_400",
    val paper: String = Develop.DEFAULT_PAPER,
    // Film & exposure
    val exposureEv: Float = 0f,
    val pushStops: Float = 0f,          // push/pull, applied as film density gamma
    val filmContrast: Float = 1.0f,     // film density curve gamma
    // Halation
    val halation: Boolean = true,
    val halationAmount: Float = 1.0f,
    val halationScale: Float = 1.0f,
    val scatterAmount: Float = 1.0f,
    val halationBoostEv: Float = 0f,
    // Grain
    val grain: Boolean = true,
    val grainSizeUm2: Float = 0.2f,
    val grainBlur: Float = 0.65f,
    val grainSublayers: Boolean = true,
    // Diffusion filter (in front of the lens)
    val diffusion: Boolean = false,
    val diffusionFamily: String = "black_pro_mist",
    val diffusionStrength: Float = 0.5f,
    val diffusionHalo: Float = 1.0f,
    val diffusionBloom: Float = 1.0f,
    val diffusionWarmth: Float = 0f,
    // Enlarger / paper
    val printExposure: Float = 1.0f,
    val yFilterShift: Float = 0f,
    val mFilterShift: Float = 0f,
    val preflash: Float = 0f,
    val printContrast: Float = 1.0f,
    // Scanner
    val unsharpAmount: Float = 0.7f,
    val unsharpRadius: Float = 0.7f,
    val whiteCorrection: Boolean = false,
    val blackCorrection: Boolean = false,
    // Output
    val glare: Boolean = true,
    val glarePercent: Float = 0.03f,
) {
    /** Build the engine's parameter tree from this recipe. */
    fun toParams(): SpektraParams = SpektraParams(
        filmProfile = film,
        printProfile = paper,
        camera = CameraParams(
            exposureCompensationEv = exposureEv,
            autoExposure = true,
            diffusionFilter = DiffusionFilterParams(
                active = diffusion, filterFamily = diffusionFamily, strength = diffusionStrength,
                haloIntensity = diffusionHalo, bloomIntensity = diffusionBloom, haloWarmth = diffusionWarmth,
            ),
        ),
        filmRender = FilmRenderingParams(
            // Pushing film raises contrast: fold the push into the density gamma.
            densityCurveGamma = filmContrast * Math.pow(1.12, pushStops.toDouble()).toFloat(),
            grain = GrainParams(active = grain, sublayersActive = grainSublayers, agxParticleAreaUm2 = grainSizeUm2, blur = grainBlur),
            halation = HalationParams(active = halation, halationAmount = halationAmount, halationSpatialScale = halationScale,
                scatterAmount = scatterAmount, boostEv = halationBoostEv),
            glare = GlareParams(active = glare, percent = glarePercent),
        ),
        enlarger = EnlargerParams(printExposure = printExposure, yFilterShift = yFilterShift, mFilterShift = mFilterShift, preflashExposure = preflash),
        printRender = PrintRenderingParams(densityCurveGamma = printContrast),
        scanner = ScannerParams(unsharpMask = unsharpAmount to unsharpRadius, whiteCorrection = whiteCorrection, blackCorrection = blackCorrection),
    )

    fun toJson(): String = JSONObject().apply {
        put("film", film); put("paper", paper)
        put("exposureEv", exposureEv.toDouble()); put("pushStops", pushStops.toDouble()); put("filmContrast", filmContrast.toDouble())
        put("halation", halation); put("halationAmount", halationAmount.toDouble()); put("halationScale", halationScale.toDouble())
        put("scatterAmount", scatterAmount.toDouble()); put("halationBoostEv", halationBoostEv.toDouble())
        put("grain", grain); put("grainSizeUm2", grainSizeUm2.toDouble()); put("grainBlur", grainBlur.toDouble()); put("grainSublayers", grainSublayers)
        put("diffusion", diffusion); put("diffusionFamily", diffusionFamily); put("diffusionStrength", diffusionStrength.toDouble())
        put("diffusionHalo", diffusionHalo.toDouble()); put("diffusionBloom", diffusionBloom.toDouble()); put("diffusionWarmth", diffusionWarmth.toDouble())
        put("printExposure", printExposure.toDouble()); put("yFilterShift", yFilterShift.toDouble()); put("mFilterShift", mFilterShift.toDouble())
        put("preflash", preflash.toDouble()); put("printContrast", printContrast.toDouble())
        put("unsharpAmount", unsharpAmount.toDouble()); put("unsharpRadius", unsharpRadius.toDouble())
        put("whiteCorrection", whiteCorrection); put("blackCorrection", blackCorrection)
        put("glare", glare); put("glarePercent", glarePercent.toDouble())
    }.toString()

    companion object {
        fun fromJson(s: String): Recipe = try {
            val o = JSONObject(s); val d = Recipe()
            Recipe(
                film = o.optString("film", d.film), paper = o.optString("paper", d.paper),
                exposureEv = o.optDouble("exposureEv", 0.0).toFloat(), pushStops = o.optDouble("pushStops", 0.0).toFloat(),
                filmContrast = o.optDouble("filmContrast", 1.0).toFloat(),
                halation = o.optBoolean("halation", true), halationAmount = o.optDouble("halationAmount", 1.0).toFloat(),
                halationScale = o.optDouble("halationScale", 1.0).toFloat(), scatterAmount = o.optDouble("scatterAmount", 1.0).toFloat(),
                halationBoostEv = o.optDouble("halationBoostEv", 0.0).toFloat(),
                grain = o.optBoolean("grain", true), grainSizeUm2 = o.optDouble("grainSizeUm2", 0.2).toFloat(),
                grainBlur = o.optDouble("grainBlur", 0.65).toFloat(), grainSublayers = o.optBoolean("grainSublayers", true),
                diffusion = o.optBoolean("diffusion", false), diffusionFamily = o.optString("diffusionFamily", d.diffusionFamily),
                diffusionStrength = o.optDouble("diffusionStrength", 0.5).toFloat(), diffusionHalo = o.optDouble("diffusionHalo", 1.0).toFloat(),
                diffusionBloom = o.optDouble("diffusionBloom", 1.0).toFloat(), diffusionWarmth = o.optDouble("diffusionWarmth", 0.0).toFloat(),
                printExposure = o.optDouble("printExposure", 1.0).toFloat(), yFilterShift = o.optDouble("yFilterShift", 0.0).toFloat(),
                mFilterShift = o.optDouble("mFilterShift", 0.0).toFloat(), preflash = o.optDouble("preflash", 0.0).toFloat(),
                printContrast = o.optDouble("printContrast", 1.0).toFloat(),
                unsharpAmount = o.optDouble("unsharpAmount", 0.7).toFloat(), unsharpRadius = o.optDouble("unsharpRadius", 0.7).toFloat(),
                whiteCorrection = o.optBoolean("whiteCorrection", false), blackCorrection = o.optBoolean("blackCorrection", false),
                glare = o.optBoolean("glare", true), glarePercent = o.optDouble("glarePercent", 0.03).toFloat(),
            )
        } catch (t: Throwable) { Recipe() }
    }
}

/** Named recipes, stored in SharedPreferences. */
object Recipes {
    private const val FILE = "latent_recipes"

    fun current(ctx: Context): Recipe = Recipe.fromJson(prefs(ctx).getString("__current", "{}") ?: "{}")
    fun setCurrent(ctx: Context, r: Recipe) = prefs(ctx).edit().putString("__current", r.toJson()).apply()

    fun names(ctx: Context): List<String> = prefs(ctx).all.keys.filter { !it.startsWith("__") }.sorted()
    fun save(ctx: Context, name: String, r: Recipe) = prefs(ctx).edit().putString(name, r.toJson()).apply()
    fun load(ctx: Context, name: String): Recipe? = prefs(ctx).getString(name, null)?.let { Recipe.fromJson(it) }
    fun delete(ctx: Context, name: String) = prefs(ctx).edit().remove(name).apply()

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
