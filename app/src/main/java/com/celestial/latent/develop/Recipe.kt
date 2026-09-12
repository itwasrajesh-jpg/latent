package com.celestial.latent.develop

import android.content.Context
import com.spectrafilm.engine.CameraParams
import com.spectrafilm.engine.ColorSpace
import com.spectrafilm.engine.DirCouplersParams
import com.spectrafilm.engine.InputGamutCompress
import com.spectrafilm.engine.IoParams
import com.spectrafilm.engine.OutputGamutCompress
import com.spectrafilm.engine.Rgb2Raw
import com.spectrafilm.engine.SettingsParams
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
    // Halation, continued
    val halationProtectEv: Float = 4.0f,
    val halationBounces: Int = 3,
    val halationDecay: Float = 0.5f,
    // Grain, continued
    val grainMicroAmount: Float = 0.2f,
    val grainMicroScale: Float = 30f,
    val grainDyeCloudUm: Float = 1.0f,
    val grainSublayerCount: Int = 1,
    // DIR couplers (chemistry: saturation and edge contrast)
    val dir: Boolean = true,
    val dirAmount: Float = 1.0f,
    val dirSameLayer: Float = 1.0f,
    val dirInterLayer: Float = 1.0f,
    val dirDiffusionUm: Float = 20f,
    // Camera
    val lensBlurUm: Float = 0f,
    val filmFormatMm: Float = 35f,
    // Diffusion filter on the lens
    val diffusion: Boolean = false,
    val diffusionFamily: String = "black_pro_mist",
    val diffusionStrength: Float = 0.5f,
    val diffusionScale: Float = 1.0f,
    val diffusionCore: Float = 1.0f,
    val diffusionCoreSize: Float = 1.0f,
    val diffusionHalo: Float = 1.0f,
    val diffusionHaloSize: Float = 1.0f,
    val diffusionBloom: Float = 1.0f,
    val diffusionBloomSize: Float = 1.0f,
    val diffusionWarmth: Float = 0f,
    // Diffusion filter on the enlarger (printing through a filter)
    val printDiffusion: Boolean = false,
    val printDiffusionFamily: String = "black_pro_mist",
    val printDiffusionStrength: Float = 0.5f,
    // Enlarger / paper
    val printExposure: Float = 1.0f,
    val yFilterShift: Float = 0f,
    val mFilterShift: Float = 0f,
    val preflash: Float = 0f,
    val printContrast: Float = 1.0f,
    val enlargerLensBlur: Float = 0f,
    val yFilterNeutral: Float = 55f,
    val mFilterNeutral: Float = 65f,
    // Scanner
    val unsharpAmount: Float = 0.7f,
    val unsharpRadius: Float = 0.7f,
    val whiteCorrection: Boolean = false,
    val blackCorrection: Boolean = false,
    val scannerWhiteLevel: Float = 0.98f,
    val scannerBlackLevel: Float = 0.01f,
    val scannerLensBlur: Float = 0f,
    val scanFilm: Boolean = false,          // skip the print stage: the scanned-negative look
    // Output & colour
    val glare: Boolean = true,
    val glarePercent: Float = 0.03f,
    val glareRoughness: Float = 0.7f,
    val glareBlur: Float = 0.5f,
    val outputColorSpace: String = "SRGB",
    val outputGamutCompress: String = "LEGACY_CLIP",
    val inputGamutCompress: String = "OFF",
    // Engine
    val rgbToRaw: String = "HANATOS2025",
    val spectralBlur: Float = 0f,
    val gpuPreview: Boolean = false,
    val gpuExport: Boolean = false,
) {
    /** Build the engine's parameter tree from this recipe. */
    fun toParams(): SpektraParams = SpektraParams(
        filmProfile = film,
        printProfile = paper,
        camera = CameraParams(
            exposureCompensationEv = exposureEv,
            autoExposure = true,
            lensBlurUm = lensBlurUm,
            filmFormatMm = filmFormatMm,
            diffusionFilter = DiffusionFilterParams(
                active = diffusion, filterFamily = diffusionFamily, strength = diffusionStrength, spatialScale = diffusionScale,
                coreIntensity = diffusionCore, coreSize = diffusionCoreSize,
                haloIntensity = diffusionHalo, haloSize = diffusionHaloSize,
                bloomIntensity = diffusionBloom, bloomSize = diffusionBloomSize, haloWarmth = diffusionWarmth,
            ),
        ),
        filmRender = FilmRenderingParams(
            // Pushing film raises contrast: fold the push into the density gamma.
            densityCurveGamma = filmContrast * Math.pow(1.12, pushStops.toDouble()).toFloat(),
            grain = GrainParams(
                active = grain, sublayersActive = grainSublayers, agxParticleAreaUm2 = grainSizeUm2, blur = grainBlur,
                blurDyeCloudsUm = grainDyeCloudUm, microStructure = grainMicroAmount to grainMicroScale, nSubLayers = grainSublayerCount,
            ),
            halation = HalationParams(active = halation, halationAmount = halationAmount, halationSpatialScale = halationScale,
                scatterAmount = scatterAmount, boostEv = halationBoostEv, protectEv = halationProtectEv,
                halationNBounces = halationBounces, halationBounceDecay = halationDecay),
            dirCouplers = DirCouplersParams(active = dir, amount = dirAmount, inhibitionSamelayer = dirSameLayer,
                inhibitionInterlayer = dirInterLayer, diffusionSizeUm = dirDiffusionUm),
            glare = GlareParams(active = glare, percent = glarePercent, roughness = glareRoughness, blur = glareBlur),
        ),
        enlarger = EnlargerParams(
            printExposure = printExposure, yFilterShift = yFilterShift, mFilterShift = mFilterShift,
            yFilterNeutral = yFilterNeutral, mFilterNeutral = mFilterNeutral,
            preflashExposure = preflash, lensBlur = enlargerLensBlur,
            diffusionFilter = DiffusionFilterParams(active = printDiffusion, filterFamily = printDiffusionFamily, strength = printDiffusionStrength),
        ),
        printRender = PrintRenderingParams(densityCurveGamma = printContrast, glare = GlareParams(active = glare, percent = glarePercent)),
        scanner = ScannerParams(
            lensBlur = scannerLensBlur, unsharpMask = unsharpAmount to unsharpRadius,
            whiteCorrection = whiteCorrection, blackCorrection = blackCorrection,
            whiteLevel = scannerWhiteLevel, blackLevel = scannerBlackLevel,
        ),
        io = IoParams(
            outputColorSpace = runCatching { ColorSpace.valueOf(outputColorSpace) }.getOrDefault(ColorSpace.SRGB),
            outputGamutCompress = runCatching { OutputGamutCompress.valueOf(outputGamutCompress) }.getOrDefault(OutputGamutCompress.LEGACY_CLIP),
            inputGamutCompress = runCatching { InputGamutCompress.valueOf(inputGamutCompress) }.getOrDefault(InputGamutCompress.OFF),
            scanFilm = scanFilm,
        ),
        settings = SettingsParams(
            rgbToRawMethod = runCatching { Rgb2Raw.valueOf(rgbToRaw) }.getOrDefault(Rgb2Raw.HANATOS2025),
            spectralGaussianBlur = spectralBlur,
            gpuPreview = gpuPreview, gpuExport = gpuExport,
        ),
    )

    fun toJson(): String = JSONObject().apply {
        put("film", film); put("paper", paper)
        put("exposureEv", exposureEv.toDouble()); put("pushStops", pushStops.toDouble()); put("filmContrast", filmContrast.toDouble())
        put("halation", halation); put("halationAmount", halationAmount.toDouble()); put("halationScale", halationScale.toDouble())
        put("scatterAmount", scatterAmount.toDouble()); put("halationBoostEv", halationBoostEv.toDouble())
        put("halationProtectEv", halationProtectEv.toDouble()); put("halationBounces", halationBounces); put("halationDecay", halationDecay.toDouble())
        put("grain", grain); put("grainSizeUm2", grainSizeUm2.toDouble()); put("grainBlur", grainBlur.toDouble()); put("grainSublayers", grainSublayers)
        put("grainMicroAmount", grainMicroAmount.toDouble()); put("grainMicroScale", grainMicroScale.toDouble())
        put("grainDyeCloudUm", grainDyeCloudUm.toDouble()); put("grainSublayerCount", grainSublayerCount)
        put("dir", dir); put("dirAmount", dirAmount.toDouble()); put("dirSameLayer", dirSameLayer.toDouble())
        put("dirInterLayer", dirInterLayer.toDouble()); put("dirDiffusionUm", dirDiffusionUm.toDouble())
        put("lensBlurUm", lensBlurUm.toDouble()); put("filmFormatMm", filmFormatMm.toDouble())
        put("diffusion", diffusion); put("diffusionFamily", diffusionFamily); put("diffusionStrength", diffusionStrength.toDouble())
        put("diffusionScale", diffusionScale.toDouble()); put("diffusionCore", diffusionCore.toDouble()); put("diffusionCoreSize", diffusionCoreSize.toDouble())
        put("diffusionHalo", diffusionHalo.toDouble()); put("diffusionHaloSize", diffusionHaloSize.toDouble())
        put("diffusionBloom", diffusionBloom.toDouble()); put("diffusionBloomSize", diffusionBloomSize.toDouble()); put("diffusionWarmth", diffusionWarmth.toDouble())
        put("printDiffusion", printDiffusion); put("printDiffusionFamily", printDiffusionFamily); put("printDiffusionStrength", printDiffusionStrength.toDouble())
        put("printExposure", printExposure.toDouble()); put("yFilterShift", yFilterShift.toDouble()); put("mFilterShift", mFilterShift.toDouble())
        put("preflash", preflash.toDouble()); put("printContrast", printContrast.toDouble()); put("enlargerLensBlur", enlargerLensBlur.toDouble())
        put("yFilterNeutral", yFilterNeutral.toDouble()); put("mFilterNeutral", mFilterNeutral.toDouble())
        put("unsharpAmount", unsharpAmount.toDouble()); put("unsharpRadius", unsharpRadius.toDouble())
        put("whiteCorrection", whiteCorrection); put("blackCorrection", blackCorrection)
        put("scannerWhiteLevel", scannerWhiteLevel.toDouble()); put("scannerBlackLevel", scannerBlackLevel.toDouble())
        put("scannerLensBlur", scannerLensBlur.toDouble()); put("scanFilm", scanFilm)
        put("glare", glare); put("glarePercent", glarePercent.toDouble()); put("glareRoughness", glareRoughness.toDouble()); put("glareBlur", glareBlur.toDouble())
        put("outputColorSpace", outputColorSpace); put("outputGamutCompress", outputGamutCompress); put("inputGamutCompress", inputGamutCompress)
        put("rgbToRaw", rgbToRaw); put("spectralBlur", spectralBlur.toDouble()); put("gpuPreview", gpuPreview); put("gpuExport", gpuExport)
    }.toString()

    companion object {
        fun fromJson(s: String): Recipe = try {
            val o = JSONObject(s); val d = Recipe()
            fun f(k: String, v: Float) = o.optDouble(k, v.toDouble()).toFloat()
            Recipe(
                film = o.optString("film", d.film), paper = o.optString("paper", d.paper),
                exposureEv = f("exposureEv", d.exposureEv), pushStops = f("pushStops", d.pushStops), filmContrast = f("filmContrast", d.filmContrast),
                halation = o.optBoolean("halation", d.halation), halationAmount = f("halationAmount", d.halationAmount),
                halationScale = f("halationScale", d.halationScale), scatterAmount = f("scatterAmount", d.scatterAmount),
                halationBoostEv = f("halationBoostEv", d.halationBoostEv), halationProtectEv = f("halationProtectEv", d.halationProtectEv),
                halationBounces = o.optInt("halationBounces", d.halationBounces), halationDecay = f("halationDecay", d.halationDecay),
                grain = o.optBoolean("grain", d.grain), grainSizeUm2 = f("grainSizeUm2", d.grainSizeUm2), grainBlur = f("grainBlur", d.grainBlur),
                grainSublayers = o.optBoolean("grainSublayers", d.grainSublayers), grainMicroAmount = f("grainMicroAmount", d.grainMicroAmount),
                grainMicroScale = f("grainMicroScale", d.grainMicroScale), grainDyeCloudUm = f("grainDyeCloudUm", d.grainDyeCloudUm),
                grainSublayerCount = o.optInt("grainSublayerCount", d.grainSublayerCount),
                dir = o.optBoolean("dir", d.dir), dirAmount = f("dirAmount", d.dirAmount), dirSameLayer = f("dirSameLayer", d.dirSameLayer),
                dirInterLayer = f("dirInterLayer", d.dirInterLayer), dirDiffusionUm = f("dirDiffusionUm", d.dirDiffusionUm),
                lensBlurUm = f("lensBlurUm", d.lensBlurUm), filmFormatMm = f("filmFormatMm", d.filmFormatMm),
                diffusion = o.optBoolean("diffusion", d.diffusion), diffusionFamily = o.optString("diffusionFamily", d.diffusionFamily),
                diffusionStrength = f("diffusionStrength", d.diffusionStrength), diffusionScale = f("diffusionScale", d.diffusionScale),
                diffusionCore = f("diffusionCore", d.diffusionCore), diffusionCoreSize = f("diffusionCoreSize", d.diffusionCoreSize),
                diffusionHalo = f("diffusionHalo", d.diffusionHalo), diffusionHaloSize = f("diffusionHaloSize", d.diffusionHaloSize),
                diffusionBloom = f("diffusionBloom", d.diffusionBloom), diffusionBloomSize = f("diffusionBloomSize", d.diffusionBloomSize),
                diffusionWarmth = f("diffusionWarmth", d.diffusionWarmth),
                printDiffusion = o.optBoolean("printDiffusion", d.printDiffusion), printDiffusionFamily = o.optString("printDiffusionFamily", d.printDiffusionFamily),
                printDiffusionStrength = f("printDiffusionStrength", d.printDiffusionStrength),
                printExposure = f("printExposure", d.printExposure), yFilterShift = f("yFilterShift", d.yFilterShift),
                mFilterShift = f("mFilterShift", d.mFilterShift), preflash = f("preflash", d.preflash), printContrast = f("printContrast", d.printContrast),
                enlargerLensBlur = f("enlargerLensBlur", d.enlargerLensBlur), yFilterNeutral = f("yFilterNeutral", d.yFilterNeutral),
                mFilterNeutral = f("mFilterNeutral", d.mFilterNeutral),
                unsharpAmount = f("unsharpAmount", d.unsharpAmount), unsharpRadius = f("unsharpRadius", d.unsharpRadius),
                whiteCorrection = o.optBoolean("whiteCorrection", d.whiteCorrection), blackCorrection = o.optBoolean("blackCorrection", d.blackCorrection),
                scannerWhiteLevel = f("scannerWhiteLevel", d.scannerWhiteLevel), scannerBlackLevel = f("scannerBlackLevel", d.scannerBlackLevel),
                scannerLensBlur = f("scannerLensBlur", d.scannerLensBlur), scanFilm = o.optBoolean("scanFilm", d.scanFilm),
                glare = o.optBoolean("glare", d.glare), glarePercent = f("glarePercent", d.glarePercent),
                glareRoughness = f("glareRoughness", d.glareRoughness), glareBlur = f("glareBlur", d.glareBlur),
                outputColorSpace = o.optString("outputColorSpace", d.outputColorSpace),
                outputGamutCompress = o.optString("outputGamutCompress", d.outputGamutCompress),
                inputGamutCompress = o.optString("inputGamutCompress", d.inputGamutCompress),
                rgbToRaw = o.optString("rgbToRaw", d.rgbToRaw), spectralBlur = f("spectralBlur", d.spectralBlur),
                gpuPreview = o.optBoolean("gpuPreview", d.gpuPreview), gpuExport = o.optBoolean("gpuExport", d.gpuExport),
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
