package com.celestial.latent.camera

/** The four rear lenses as the Xiaomi 15 Ultra driver exposes them (physical IDs behind logical camera 0). */
data class Lens(val physicalId: String, val label: String, val name: String)

object Lenses {
    val ALL = listOf(
        Lens("3", "0.6x", "Ultrawide"),
        Lens("2", "1x", "Main"),
        Lens("4", "3x", "70 mm"),
        Lens("5", "4.3x", "100 mm"),
    )
    const val LOGICAL_ID = "0"
    val DEFAULT = ALL[2] // 70 mm — the portrait lens
}
