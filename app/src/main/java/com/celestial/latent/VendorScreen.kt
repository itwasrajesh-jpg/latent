@file:OptIn(ExperimentalFoundationApi::class)

package com.celestial.latent

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.celestial.latent.ui.LatentColors

/**
 * MotionCam-style vendor overrides: a custom session opmode and a list of vendor tags.
 * exposedKeys: what the driver advertises for the current lens/path (name -> "session"/"request"/"both").
 */
@Composable
fun VendorScreen(settings: AppSettings, exposedKeys: List<Pair<String, String>>, lastEcho: String, onChange: (AppSettings) -> Unit, onBack: () -> Unit) {
    var search by remember { mutableStateOf("") }
    var opmodeText by remember { mutableStateOf(if (settings.opmode == 0) "0" else "0x" + Integer.toHexString(settings.opmode)) }

    Column(
        Modifier.fillMaxSize().background(LatentColors.Background).statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Text("‹ settings", color = LatentColors.Text, fontSize = 14.sp, modifier = Modifier.combinedClickable(onClick = onBack).padding(vertical = 6.dp))
        Spacer(Modifier.height(8.dp))
        Text("Vendor tags", color = LatentColors.TextBright, fontSize = 24.sp)
        Text("Path: camera ${settings.cameraPath}. Keys differ per path and lens. Changes apply on the next lens switch.", color = LatentColors.TextDim, fontSize = 12.sp)
        Spacer(Modifier.height(16.dp))

        Text("CUSTOM OPMODE", color = LatentColors.TextDim, fontSize = 11.sp, letterSpacing = 2.sp)
        Text("Vendor session operating mode. 0 keeps the regular session. Decimal or hex (0x8001).", color = LatentColors.TextDim, fontSize = 12.sp)
        Field(opmodeText, { t ->
            opmodeText = t
            val v = t.trim().let { if (it.startsWith("0x", true)) it.substring(2).toIntOrNull(16) else it.toIntOrNull() }
            if (v != null) onChange(settings.copy(opmode = v))
        }, "0", KeyboardType.Text)

        Spacer(Modifier.height(16.dp))
        Text("TAGS", color = LatentColors.TextDim, fontSize = 11.sp, letterSpacing = 2.sp)
        settings.vendorTags.forEachIndexed { i, tag ->
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(8.dp)).background(LatentColors.Surface).padding(10.dp)) {
                Field(tag.name, { onChange(settings.copy(vendorTags = settings.vendorTags.toMutableList().also { l -> l[i] = tag.copy(name = it) })) }, "org.codeaurora.qcamera3.…", KeyboardType.Text)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chips(listOf("session", "request"), tag.scope) { onChange(settings.copy(vendorTags = settings.vendorTags.toMutableList().also { l -> l[i] = tag.copy(scope = it) })) }
                    Chips(listOf("i32", "i64", "f32", "f64", "u8"), tag.type) { onChange(settings.copy(vendorTags = settings.vendorTags.toMutableList().also { l -> l[i] = tag.copy(type = it) })) }
                }
                Row {
                    Column(Modifier.weight(1f)) {
                        Field(tag.value, { onChange(settings.copy(vendorTags = settings.vendorTags.toMutableList().also { l -> l[i] = tag.copy(value = it) })) }, "value, or 4/12/24 for arrays", KeyboardType.Text)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text("✕", color = LatentColors.Text, fontSize = 18.sp, modifier = Modifier.combinedClickable(onClick = { onChange(settings.copy(vendorTags = settings.vendorTags.filterIndexed { j, _ -> j != i })) }).padding(12.dp))
                }
            }
        }
        Text("+ add tag", color = LatentColors.AmberInk, fontSize = 13.sp,
            modifier = Modifier.padding(vertical = 8.dp).clip(RoundedCornerShape(999.dp)).background(LatentColors.Amber)
                .combinedClickable(onClick = { onChange(settings.copy(vendorTags = settings.vendorTags + VendorTag("", "session", "i32", "0"))) })
                .padding(horizontal = 14.dp, vertical = 7.dp))

        Spacer(Modifier.height(16.dp))
        Text("ECHO FROM LAST CAPTURE", color = LatentColors.TextDim, fontSize = 11.sp, letterSpacing = 2.sp)
        Text(if (lastEcho.isEmpty()) "Take a photo; the driver's reported values for your tags appear here." else lastEcho, color = LatentColors.Text, fontSize = 12.sp, lineHeight = 16.sp)

        Spacer(Modifier.height(16.dp))
        Text("KEYS THE DRIVER EXPOSES (${exposedKeys.size})", color = LatentColors.TextDim, fontSize = 11.sp, letterSpacing = 2.sp)
        Field(search, { search = it }, "search keys", KeyboardType.Text)
        val shown = exposedKeys.filter { search.isBlank() || it.first.contains(search, ignoreCase = true) }.take(60)
        shown.forEach { (name, scope) ->
            Column(Modifier.fillMaxWidth().combinedClickable(onClick = {
                onChange(settings.copy(vendorTags = settings.vendorTags + VendorTag(name, if (scope == "request") "request" else "session", "i32", "0")))
            }).padding(vertical = 6.dp)) {
                Text(name.substringAfterLast('.'), color = LatentColors.TextBright, fontSize = 13.sp)
                Text("$name · $scope", color = LatentColors.TextDim, fontSize = 10.sp)
            }
        }
        if (exposedKeys.size > shown.size) Text("… ${exposedKeys.size - shown.size} more, refine the search", color = LatentColors.TextDim, fontSize = 11.sp)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, hint: String, kb: KeyboardType) {
    OutlinedTextField(
        value = value, onValueChange = onChange, singleLine = true,
        placeholder = { Text(hint, color = LatentColors.TextDim, fontSize = 12.sp) },
        keyboardOptions = KeyboardOptions(keyboardType = kb),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = LatentColors.TextBright, unfocusedTextColor = LatentColors.TextBright,
            focusedBorderColor = LatentColors.Amber, unfocusedBorderColor = LatentColors.Line, cursorColor = LatentColors.Amber,
        ),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}

@Composable
private fun Chips(options: List<String>, selected: String, onSelect: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { o ->
            val on = o == selected
            Text(o, color = if (on) LatentColors.AmberInk else LatentColors.Text, fontSize = 12.sp,
                modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(if (on) LatentColors.Amber else LatentColors.Background)
                    .combinedClickable(onClick = { onSelect(o) }).padding(horizontal = 10.dp, vertical = 5.dp))
        }
    }
}
