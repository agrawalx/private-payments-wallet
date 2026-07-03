package com.privatepayments.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.privatepayments.R
import com.privatepayments.ui.theme.Umbra
import com.privatepayments.ui.theme.glow
import com.privatepayments.ui.theme.umbraScreen

/** Brief brand splash shown while we decide onboarding vs. load the wallet. */
@Composable
fun SplashScreen() {
    Box(Modifier.fillMaxSize().umbraScreen(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .size(112.dp)
                    .glow(Umbra.Primary, CircleShape, 18.dp)
                    .clip(CircleShape)
                    .background(Color.White),
                contentAlignment = Alignment.Center,
            ) {
                // Launcher foreground already carries adaptive-icon safe-zone padding.
                Image(
                    painterResource(R.mipmap.ic_launcher_foreground),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Spacer(Modifier.height(22.dp))
            Text("Stella", color = Umbra.TextPrimary, fontFamily = Umbra.Display, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        }
    }
}
