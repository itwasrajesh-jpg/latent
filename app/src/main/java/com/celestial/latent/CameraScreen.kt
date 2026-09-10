@file:OptIn(ExperimentalFoundationApi::class)

package com.celestial.latent

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.celestial.latent.camera.CameraController
import com.celestial.latent.camera.Lens
import com.celestial.latent.camera.Lenses
import com.celestial.latent.ui.LatentColors

@Composable
fun CameraScreen(onOpenReport: () -> Unit) {
    val context = LocalContext.current
    var status by remember { mutableStateOf("Starting camera…") }
    var log by remember { mutableStateOf("") }
    var lens by remember { mutableStateOf(Lenses.DEFAULT) }
    val controller = remember {
        CameraController(context, onStatus = { s -> status = s }, onLog = { s -> log = (s + "\n" + log).take(2000) })
    }
    var holderRef by remember { mutableStateOf<SurfaceHolder?>(null) }

    DisposableEffect(Unit) { onDispose { controller.destroy() } }

    Column(
        modifier = Modifier.fillMaxSize().background(LatentColors.Background).statusBarsPadding().navigationBarsPadding(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("LATENT", color = LatentColors.Text, fontSize = 13.sp, letterSpacing = 4.sp, fontWeight = FontWeight.Light)
            Text("12.5M · RAW · v" + BuildConfig.VERSION_NAME, color = LatentColors.TextDim, fontSize = 11.sp)
            Text("report", color = LatentColors.TextDim, fontSize = 11.sp, modifier = Modifier.combinedClickable(onClick = onOpenReport))
        }

        // Viewfinder: 3:4 box. Camera2 rotates the SurfaceView output to the display orientation itself.
        Box(modifier = Modifier.fillMaxWidth().aspectRatio(3f / 4f).background(LatentColors.Surface)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    SurfaceView(ctx).apply {
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(h: SurfaceHolder) {
                                val size = controller.previewSizeFor(lens)
                                h.setFixedSize(size.width, size.height)
                                holderRef = h
                                controller.open(lens, h.surface)
                            }
                            override fun surfaceChanged(h: SurfaceHolder, format: Int, width: Int, height: Int) {}
                            override fun surfaceDestroyed(h: SurfaceHolder) { holderRef = null; controller.close() }
                        })
                    }
                },
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            Lenses.ALL.forEach { l -> LensChip(l, l == lens) { if (l != lens) { lens = l; holderRef?.let { h -> controller.open(l, h.surface) } } } }
        }

        Text(
            text = status,
            color = LatentColors.TextBright,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )

        Spacer(Modifier.height(8.dp))

        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            // Shutter: tap = single RAW, long-press = 16-frame burst.
            Box(
                modifier = Modifier
                    .size(76.dp)
                    .clip(CircleShape)
                    .border(3.dp, LatentColors.TextBright, CircleShape)
                    .combinedClickable(onClick = { controller.captureSingle() }, onLongClick = { controller.captureBurst(16) }),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(58.dp).clip(CircleShape).background(LatentColors.TextBright))
            }
        }

        Spacer(Modifier.height(10.dp))
        Text(
            text = log,
            color = LatentColors.TextDim,
            fontSize = 10.sp,
            lineHeight = 13.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
    }
}

@Composable
private fun LensChip(l: Lens, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = l.label,
        color = if (selected) LatentColors.Amber else LatentColors.Text,
        fontSize = 13.sp,
        modifier = Modifier
            .padding(horizontal = 6.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) LatentColors.Surface else LatentColors.Background)
            .combinedClickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}
