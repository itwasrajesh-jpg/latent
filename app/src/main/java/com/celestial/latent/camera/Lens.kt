package com.celestial.latent.camera

/** The four rear lenses as the Xiaomi 15 Ultra driver exposes them (physical IDs behind logical camera 0). */
data class Lens(val physicalId: String, val label: String, val name: String, val mm: Int = 0)

object Lenses {
    val ALL = listOf(
        Lens("3", "0.6", "Ultrawide", 14),
        Lens("2", "1", "Main", 23),
        Lens("4", "3", "70 mm", 70),
        Lens("5", "4.3", "100 mm", 100),
    )
    const val LOGICAL_ID = "0"
    val DEFAULT = ALL[2] // 70 mm — the portrait lens
}
