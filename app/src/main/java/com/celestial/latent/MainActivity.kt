package com.celestial.latent

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.celestial.latent.camera.CameraReport
import com.celestial.latent.ui.LatentColors
import com.celestial.latent.ui.LatentTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLog.install(this)
        enableEdgeToEdge()
        setContent { LatentTheme { Root() } }
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP || keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN) {
            val f = ShutterBus.onShutter
            if (f != null) { if (event?.repeatCount == 0) f(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }
}

@Composable
private fun Root() {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> granted = ok }
    var crash by remember { mutableStateOf(CrashLog.read(context)) }
    var screen by remember { mutableStateOf("camera") }
    crash?.let { text ->
        Column(Modifier.fillMaxSize().background(LatentColors.Background).statusBarsPadding().navigationBarsPadding().padding(16.dp)) {
            Text("Latent crashed last time", color = LatentColors.TextBright, fontSize = 18.sp)
            Spacer(Modifier.height(8.dp))
            Row {
                Button(onClick = {
                    val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }
                    context.startActivity(Intent.createChooser(send, "Share crash"))
                }, colors = ButtonDefaults.buttonColors(containerColor = LatentColors.Amber, contentColor = LatentColors.AmberInk)) { Text("Share") }
                Spacer(Modifier.width(12.dp))
                Button(onClick = { CrashLog.clear(context); crash = null },
                    colors = ButtonDefaults.buttonColors(containerColor = LatentColors.Surface, contentColor = LatentColors.TextBright)) { Text("Dismiss") }
            }
            Spacer(Modifier.height(12.dp))
            Text(text, color = LatentColors.Text, fontSize = 10.sp, lineHeight = 13.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()))
        }
        return
    }
    var settings by remember { mutableStateOf(AppSettings.load(context)) }
    var controllerRef by remember { mutableStateOf<com.celestial.latent.camera.CameraController?>(null) }
    var vendorEcho by remember { mutableStateOf("") }

    if (!granted) {
        Column(Modifier.fillMaxSize().background(LatentColors.Background).statusBarsPadding().padding(24.dp)) {
            Text("LATENT", color = LatentColors.TextBright, fontSize = 20.sp, letterSpacing = 6.sp)
            Spacer(Modifier.height(16.dp))
            Text("Latent needs the camera to work. Photos are saved to DCIM/Latent as DNG.", color = LatentColors.Text, fontSize = 14.sp)
            Spacer(Modifier.height(16.dp))
            Button(onClick = { askPermission.launch(Manifest.permission.CAMERA) },
                colors = ButtonDefaults.buttonColors(containerColor = LatentColors.Amber, contentColor = LatentColors.AmberInk)) { Text("Allow camera") }
        }
        return
    }
    when (screen) {
        "report" -> ReportScreen(onBack = { screen = "settings" })
        "settings" -> SettingsScreen(
            settings = settings,
            onChange = { s -> settings = s; AppSettings.save(context, s) },
            onOpenReport = { screen = "report" },
            onOpenVendor = { screen = "vendor" },
            onOpenProbe = { screen = "probe" },
            onBack = { screen = "camera" },
        )
        "probe" -> ProbeScreen(settings = settings, onBack = { screen = "settings" })
        "vendor" -> VendorScreen(
            settings = settings,
            exposedKeys = controllerRef?.exposedVendorKeys() ?: emptyList(),
            lastEcho = vendorEcho,
            onChange = { s -> settings = s; AppSettings.save(context, s) },
            onBack = { screen = "settings" },
        )
        else -> CameraScreen(
            settings = settings,
            onOpenSettings = { screen = "settings" },
            onLensChanged = { l -> if (settings.rememberLens) { settings = settings.copy(defaultLensId = l.physicalId); AppSettings.save(context, settings) } },
            onController = { c -> controllerRef = c },
            onVendorEcho = { e -> vendorEcho = e },
        )
    }
}

@Composable
private fun ReportScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var report by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().background(LatentColors.Background).statusBarsPadding().navigationBarsPadding().padding(16.dp)) {
        Row {
            Button(onClick = onBack, colors = ButtonDefaults.buttonColors(containerColor = LatentColors.Surface, contentColor = LatentColors.TextBright)) { Text("Back") }
            Spacer(Modifier.width(12.dp))
            Button(onClick = { report = runCatching { CameraReport.build(context) }.getOrElse { "Report failed:\n$it" } },
                colors = ButtonDefaults.buttonColors(containerColor = LatentColors.Amber, contentColor = LatentColors.AmberInk)) { Text("Read cameras") }
            Spacer(Modifier.width(12.dp))
            Button(enabled = report != null, onClick = {
                val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, report ?: "") }
                context.startActivity(Intent.createChooser(send, "Share report"))
            }, colors = ButtonDefaults.buttonColors(containerColor = LatentColors.Surface, contentColor = LatentColors.TextBright)) { Text("Share") }
        }
        Spacer(Modifier.height(12.dp))
        Text(report ?: "Tap “Read cameras”.", color = LatentColors.Text, fontSize = 11.sp, fontFamily = FontFamily.Monospace, lineHeight = 15.sp,
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()))
    }
}
