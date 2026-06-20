package com.privatepayments.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.privatepayments.ui.theme.ProofOrb
import com.privatepayments.ui.theme.Umbra
import com.privatepayments.ui.theme.umbraScreen

/** Brief brand splash shown while we decide onboarding vs. load the wallet. */
@Composable
fun SplashScreen() {
    Box(Modifier.fillMaxSize().umbraScreen(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            ProofOrb(Icons.Filled.Lock, Umbra.Primary, spin = true)
            Spacer(Modifier.height(22.dp))
            Text("Stella", color = Umbra.TextPrimary, fontFamily = Umbra.Display, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        }
    }
}
