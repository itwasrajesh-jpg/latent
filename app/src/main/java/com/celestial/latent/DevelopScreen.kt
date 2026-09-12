@file:OptIn(ExperimentalFoundationApi::class)

package com.celestial.latent

import android.content.ContentUris
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.util.Size
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.celestial.latent.develop.Develop
import com.celestial.latent.ui.LatentColors

/** First look at the film engine: take the newest DNG, develop it, show the result. */
@Composable
fun DevelopScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var film by remember { mutableStateOf(Develop.FILMS.first().first) }
    var status by remember { mutableStateOf("") }
    var log by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var latestDng by remember { mutableStateOf<Uri?>(null) }
    var latestName by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<Bitmap?>(null) }
    var resultUri by remember { mutableStateOf<Uri?>(null) }
    var profiles by remember { mutableStateOf<List<String>>(emptyList()) }

    LaunchedEffect(Unit) {
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME),
            "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ? AND ${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?",
            arrayOf("DCIM/Latent%", "%.dng"), "${MediaStore.Images.Media.DATE_ADDED} DESC",
        )?.use { c -> if (c.moveToFirst()) { latestDng = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0)); latestName = c.getString(1) } }
        profiles = runCatching { Develop.availableProfiles(context) }.getOrElse { listOf("engine not available: ${it.message}") }
    }

    fun develop(maxEdge: Int) {
        val uri = latestDng ?: return
        if (running) return
        running = true; result = null; log = ""; status = "working…"
        Thread {
            try {
                val out = Develop.developDng(context, uri, film, maxEdge = maxEdge) { m -> log = (log + "\n" + m).trim() }
                resultUri = out
                result = context.contentResolver.loadThumbnail(out, Size(1080, 1080), null)
                status = "done"
            } catch (t: Throwable) {
                status = "failed: ${t.message}"
                log = (log + "\n" + t.stackTraceToString().lines().take(6).joinToString("\n")).trim()
            }
            running = false
        }.start()
    }

    Column(
        Modifier.fillMaxSize().background(LatentColors.Background).statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Text("‹ settings", color = LatentColors.Text, fontSize = 14.sp, modifier = Modifier.combinedClickable(onClick = onBack).padding(vertical = 6.dp))
        Spacer(Modifier.height(8.dp))
        Text("Develop", color = LatentColors.TextBright, fontSize = 24.sp)
        Text("Runs the newest Latent DNG through the spektrafilm engine.", color = LatentColors.TextDim, fontSize = 12.sp)
        Spacer(Modifier.height(12.dp))
        Text(if (latestName.isEmpty()) "No DNG found in DCIM/Latent" else "Latest: $latestName", color = LatentColors.Text, fontSize = 12.sp)

        Spacer(Modifier.height(14.dp))
        Text("FILM", color = LatentColors.TextDim, fontSize = 11.sp, letterSpacing = 2.sp)
        Column {
            Develop.FILMS.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { (id, label) ->
                        val on = id == film
                        Text(label, color = if (on) LatentColors.AmberInk else LatentColors.Text, fontSize = 12.sp,
                            modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(if (on) LatentColors.Amber else LatentColors.Surface)
                                .combinedClickable(onClick = { film = id; Haptics.tick(context) }).padding(vertical = 10.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn("Quick look (2 MP)", !running && latestDng != null) { develop(1600) }
            Btn("Full size", !running && latestDng != null) { develop(0) }
        }
        Spacer(Modifier.height(10.dp))
        if (status.isNotEmpty()) Text(status, color = if (status.startsWith("failed")) LatentColors.Amber else LatentColors.Text, fontSize = 13.sp)
        if (log.isNotEmpty()) Text(log, color = LatentColors.TextDim, fontSize = 11.sp, lineHeight = 14.sp, fontFamily = FontFamily.Monospace)

        result?.let { bmp ->
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth().aspectRatio(bmp.width.toFloat() / bmp.height).clip(RoundedCornerShape(8.dp))
                .combinedClickable(onClick = {
                    resultUri?.let { u -> runCatching { context.startActivity(Intent(Intent.ACTION_VIEW).apply { setDataAndType(u, "image/jpeg"); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }) } }
                })) {
                Image(bmp.asImageBitmap(), contentDescription = "Developed", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            }
        }

        Spacer(Modifier.height(18.dp))
        Text("PROFILES IN THE ENGINE (${profiles.size})", color = LatentColors.TextDim, fontSize = 11.sp, letterSpacing = 2.sp)
        Text(profiles.joinToString(", "), color = LatentColors.TextDim, fontSize = 11.sp, lineHeight = 14.sp)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Btn(label: String, enabled: Boolean, onClick: () -> Unit) {
    val ctx = LocalContext.current
    Text(label, color = if (enabled) LatentColors.AmberInk else LatentColors.TextDim, fontSize = 13.sp,
        modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(if (enabled) LatentColors.Amber else LatentColors.Surface)
            .combinedClickable(enabled = enabled, onClick = { Haptics.tick(ctx); onClick() }).padding(horizontal = 14.dp, vertical = 9.dp))
}
