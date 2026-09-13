package com.celestial.latent.develop

import android.graphics.Bitmap
import android.net.Uri

/**
 * What the film builder is in the middle of, kept outside the screen.
 *
 * Choosing references and a test shot is real work, and a fit takes minutes. Holding that in
 * the screen meant a back gesture threw it all away — so it lives here instead, and lasts until
 * the app closes or the session is cleared on purpose.
 */
object LookSession {

    var references: List<Pair<Uri, Fingerprint>> = emptyList()
    var textures: List<Texture> = emptyList()
    var thumbs: Map<Uri, Bitmap> = emptyMap()
    var testShot: Uri? = null
    var testIsRaw: Boolean = true
    var result: Reconstruct.Attempt? = null
    var resultBitmap: Bitmap? = null
    var progress: Reconstruct.Progress? = null
    var saved: String = ""

    val isEmpty: Boolean
        get() = references.isEmpty() && testShot == null && result == null

    /** Forgets everything, so the next session starts clean. */
    fun clear() {
        references = emptyList()
        textures = emptyList()
        thumbs = emptyMap()
        testShot = null
        testIsRaw = true
        result = null
        resultBitmap = null
        progress = null
        saved = ""
    }
}
