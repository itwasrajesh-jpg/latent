@file:OptIn(ExperimentalFoundationApi::class)

package com.celestial.latent

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.celestial.latent.develop.Develop
import com.celestial.latent.ui.LatentColors

/** The film selector that sits along the bottom of the viewfinder, on every camera screen. */
@Composable
fun FilmStrip(selected: String, modifier: Modifier = Modifier, onSelect: (String) -> Unit) {
    val context = LocalContext.current
    Row(
        modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Develop.FILMS.forEach { (id, label) ->
            val on = id == selected
            Text(
                label.uppercase(), color = if (on) LatentColors.AmberInk else LatentColors.TextBright, fontSize = 11.sp, letterSpacing = 1.sp,
                modifier = Modifier.clip(RoundedCornerShape(6.dp))
                    .background(if (on) LatentColors.Amber else Color(0x73000000))
                    .combinedClickable(onClick = { if (!on) { Haptics.tick(context); onSelect(id) } })
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            )
        }
    }
}
