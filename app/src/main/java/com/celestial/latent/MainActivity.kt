package com.celestial.latent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.celestial.latent.ui.LatentColors
import com.celestial.latent.ui.LatentTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { LatentTheme { HelloLatent() } }
    }
}

@Composable
private fun HelloLatent() {
    Box(
        modifier = Modifier.fillMaxSize().background(LatentColors.Background),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "LATENT",
                color = LatentColors.TextBright,
                fontSize = 28.sp,
                letterSpacing = 8.sp,
                fontWeight = FontWeight.Light,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "film camera · step 1",
                color = LatentColors.Text,
                fontSize = 14.sp,
            )
            Spacer(Modifier.height(48.dp))
            Text(
                text = "v" + BuildConfig.VERSION_NAME,
                color = LatentColors.TextDim,
                fontSize = 12.sp,
            )
        }
    }
    Column(
        modifier = Modifier.fillMaxSize().padding(bottom = 32.dp),
        verticalArrangement = Arrangement.Bottom,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "film modeling will be powered by spektrafilm",
            color = LatentColors.TextDim,
            fontSize = 11.sp,
        )
    }
}
