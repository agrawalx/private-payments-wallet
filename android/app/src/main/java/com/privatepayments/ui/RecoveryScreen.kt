package com.privatepayments.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.privatepayments.ui.theme.Umbra
import com.privatepayments.ui.theme.umbraScreen

/**
 * Phase 4 recovery-phrase screen. Two modes:
 *  - **View**: reveal the 12-word BIP39 backup so the user can save it offline.
 *  - **Import**: paste an existing phrase to restore a wallet on a new device.
 */
@Composable
fun RecoveryScreen(
    phrase: String?,
    onImport: (String) -> Boolean,
    onOpenBackup: () -> Unit,
    onClose: () -> Unit,
) {
    var importing by remember { mutableStateOf(false) }
    var revealed by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().umbraScreen().padding(24.dp)) {
        Spacer(Modifier.height(20.dp))
        Text(
            if (importing) "Import recovery phrase" else "Your recovery phrase",
            color = Umbra.TextPrimary, fontFamily = Umbra.Display, fontSize = 24.sp, fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (importing) "Paste your 12-word phrase to restore your wallet."
            else "These 12 words are the ONLY way to recover your wallet and funds. Write them down and store them offline. Never share them.",
            color = Umbra.TextMuted, fontSize = 13.sp, lineHeight = 19.sp,
        )
        Spacer(Modifier.height(20.dp))

        if (importing) {
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                    .background(Umbra.Surface).padding(14.dp).heightIn(min = 120.dp),
            ) {
                BasicTextField(
                    value = input,
                    onValueChange = { input = it; error = null },
                    textStyle = TextStyle(color = Umbra.TextPrimary, fontSize = 16.sp, lineHeight = 26.sp),
                    cursorBrush = SolidColor(Umbra.Primary),
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { inner ->
                        if (input.isEmpty()) {
                            Text("word1 word2 word3 …", color = Umbra.TextFaint, fontSize = 16.sp)
                        }
                        inner()
                    },
                )
            }
            if (error != null) {
                Spacer(Modifier.height(8.dp))
                Text(error!!, color = Umbra.Error, fontSize = 13.sp)
            }
            Spacer(Modifier.height(16.dp))
            PrimaryButton("Import wallet") {
                if (onImport(input)) onClose() else error = "Invalid recovery phrase — check the words and spacing."
            }
            Spacer(Modifier.height(10.dp))
            SecondaryButton("Back") { importing = false; error = null }
        } else {
            PhraseGrid(phrase, revealed) { revealed = true }
            Spacer(Modifier.height(20.dp))
            PrimaryButton(if (revealed) "I've saved it" else "Tap to reveal") {
                if (revealed) onClose() else revealed = true
            }
            Spacer(Modifier.height(10.dp))
            SecondaryButton("Cloud backup & restore") { onOpenBackup() }
            Spacer(Modifier.height(10.dp))
            SecondaryButton("Import a different phrase") { importing = true }
        }

        Spacer(Modifier.weight(1f))
        SecondaryButton("Close") { onClose() }
    }
}

@Composable
private fun PhraseGrid(phrase: String?, revealed: Boolean, onReveal: () -> Unit) {
    val words = phrase?.trim()?.split(Regex("\\s+")).orEmpty()
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Umbra.Surface)
            .clickable(enabled = !revealed) { onReveal() }.padding(16.dp),
    ) {
        Column {
            words.chunked(2).forEachIndexed { row, pair ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    pair.forEachIndexed { col, w ->
                        val n = row * 2 + col + 1
                        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                            Text("$n.", color = Umbra.TextFaint, fontSize = 13.sp, modifier = Modifier.width(28.dp))
                            Text(
                                if (revealed) w else "••••••",
                                color = Umbra.TextPrimary, fontSize = 16.sp,
                                fontFamily = com.privatepayments.ui.theme.Umbra.Mono, fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }
            }
        }
        if (!revealed) {
            Row(
                Modifier.matchParentSize(), horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.VisibilityOff, null, tint = Umbra.TextMuted, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Tap to reveal", color = Umbra.TextMuted, fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun PrimaryButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Umbra.Primary)
            .clickable(onClick = onClick).padding(vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = Umbra.Bg, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun SecondaryButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Umbra.SurfaceElevated)
            .clickable(onClick = onClick).padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = Umbra.TextSecondary, fontWeight = FontWeight.Medium) }
}
