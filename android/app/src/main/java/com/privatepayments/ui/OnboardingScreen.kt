package com.privatepayments.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.privatepayments.ui.theme.*
import kotlinx.coroutines.launch

private enum class OnbStep { Welcome, Generating, Seed, Confirm, Import }

/**
 * First-run onboarding: Welcome → (Create → reveal seed → confirm) or (Import phrase) → Home.
 * [onCreate] generates+stores the wallet and returns its 12-word phrase; [onImport]
 * validates+stores a pasted phrase; [onDone] enters the app.
 */
@Composable
fun OnboardingScreen(
    onCreate: suspend () -> String,
    onImport: suspend (String) -> Boolean,
    onDone: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(OnbStep.Welcome) }
    var phrase by remember { mutableStateOf("") }

    Box(Modifier.fillMaxSize().umbraScreen()) {
        when (step) {
            OnbStep.Welcome -> Welcome(
                onCreate = { step = OnbStep.Generating; scope.launch { phrase = onCreate(); step = OnbStep.Seed } },
                onImport = { step = OnbStep.Import },
            )
            OnbStep.Generating -> Centered("Creating your wallet…", "Generating keys + funding on testnet")
            OnbStep.Seed -> SeedReveal(phrase) { step = OnbStep.Confirm }
            OnbStep.Confirm -> ConfirmSeed(phrase, onBack = { step = OnbStep.Seed }, onDone = onDone)
            OnbStep.Import -> ImportPhrase(
                onBack = { step = OnbStep.Welcome },
                onImport = onImport,
                onDone = onDone,
            )
        }
    }
}

@Composable
private fun Welcome(onCreate: () -> Unit, onImport: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Hero scrolls if the screen is short; the CTAs stay pinned at the bottom.
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(32.dp))
            ProofOrb(Icons.Filled.Lock, Umbra.Primary, spin = false)
            Spacer(Modifier.height(24.dp))
            Text("Stella", color = Umbra.TextPrimary, fontFamily = Umbra.Display, fontSize = 40.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp)
            Spacer(Modifier.height(10.dp))
            Text(
                "Private payments on Stellar.\nShielded by default — your keys never leave the device.",
                color = Umbra.TextMuted, fontSize = 15.sp, lineHeight = 22.sp,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            Spacer(Modifier.height(24.dp))
            Column(Modifier.fillMaxWidth().elevatedCard(RoundedCornerShape(18.dp)).padding(18.dp)) {
                Feature("Self-custodial — one recovery phrase, on-device keys")
                Spacer(Modifier.height(12.dp))
                Feature("Shielded amounts + unlinkable transfers")
                Spacer(Modifier.height(12.dp))
                Feature("On-device zero-knowledge proofs")
            }
            Spacer(Modifier.height(20.dp))
        }
        GradientButton("Create a new wallet", Modifier.fillMaxWidth(), onClick = onCreate)
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                .background(SolidColor(Umbra.Surface))
                .clickable(onClick = onImport).padding(vertical = 14.dp),
            contentAlignment = Alignment.Center,
        ) { Text("I already have a recovery phrase", color = Umbra.TextSecondary, fontWeight = FontWeight.Medium) }
    }
}

@Composable
private fun Feature(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(Umbra.Primary))
        Spacer(Modifier.width(12.dp))
        Text(text, color = Umbra.TextSecondary, fontSize = 13.sp, lineHeight = 18.sp)
    }
}

@Composable
private fun Centered(title: String, subtitle: String) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        ProofOrb(Icons.Filled.Lock, Umbra.Primary, spin = true)
        Spacer(Modifier.height(24.dp))
        Text(title, color = Umbra.TextPrimary, fontFamily = Umbra.Display, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(subtitle, color = Umbra.TextMuted, fontSize = 13.sp)
    }
}

@Composable
private fun SeedReveal(phrase: String, onNext: () -> Unit) {
    val words = remember(phrase) { phrase.trim().split(Regex("\\s+")) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
        Spacer(Modifier.height(20.dp))
        Text("Your recovery phrase", color = Umbra.TextPrimary, fontFamily = Umbra.Display, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(14.dp))
        Row(
            Modifier.fillMaxWidth().accentWash(Umbra.PublicWash, Umbra.PublicBorder, RoundedCornerShape(14.dp)).padding(14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(Icons.Filled.Warning, null, tint = Umbra.Public, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text(
                "Write these 12 words down in order and keep them offline. Anyone with them controls your funds — Stella cannot recover them for you.",
                color = Umbra.TextSecondary, fontSize = 12.5.sp, lineHeight = 18.sp,
            )
        }
        Spacer(Modifier.height(18.dp))
        Column(Modifier.fillMaxWidth().elevatedCard(RoundedCornerShape(18.dp)).padding(16.dp)) {
            words.chunked(2).forEachIndexed { row, pair ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    pair.forEachIndexed { col, w ->
                        SeedWord(row * 2 + col + 1, w, Modifier.weight(1f))
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        GradientButton("I've saved it", Modifier.fillMaxWidth(), onClick = onNext)
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun SeedWord(n: Int, word: String, modifier: Modifier = Modifier) {
    Row(modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("$n", color = Umbra.TextFaint, fontFamily = Umbra.Mono, fontSize = 12.sp, modifier = Modifier.width(22.dp))
        Text(word, color = Umbra.TextPrimary, fontFamily = Umbra.Mono, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ConfirmSeed(phrase: String, onBack: () -> Unit, onDone: () -> Unit) {
    val words = remember(phrase) { phrase.trim().split(Regex("\\s+")) }
    // 3 challenge positions, each with the correct word + 2 distractors from the phrase.
    val challenges = remember(phrase) {
        words.indices.shuffled().take(3).sorted().map { idx ->
            val distractors = words.filterIndexed { i, _ -> i != idx }.shuffled().take(2)
            idx to (distractors + words[idx]).shuffled()
        }
    }
    val answers = remember(phrase) { mutableStateMapOf<Int, String>() }
    val allCorrect = challenges.all { (idx, _) -> answers[idx] == words[idx] }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
        Spacer(Modifier.height(20.dp))
        Text("Confirm your phrase", color = Umbra.TextPrimary, fontFamily = Umbra.Display, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text("Tap the correct word for each position.", color = Umbra.TextMuted, fontSize = 13.sp)
        Spacer(Modifier.height(20.dp))
        challenges.forEach { (idx, options) ->
            Text("Word #${idx + 1}", color = Umbra.TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                options.forEach { opt ->
                    val selected = answers[idx] == opt
                    val correct = selected && opt == words[idx]
                    val wrong = selected && opt != words[idx]
                    Box(
                        Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                            .background(
                                when {
                                    correct -> Umbra.SuccessWash
                                    wrong -> Umbra.Error.copy(alpha = 0.14f)
                                    else -> Umbra.SurfaceElevated
                                },
                            )
                            .clickable { answers[idx] = opt }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            opt,
                            color = when { correct -> Umbra.Success; wrong -> Umbra.Error; else -> Umbra.TextSecondary },
                            fontFamily = Umbra.Mono, fontSize = 13.sp, maxLines = 1,
                        )
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
        }
        Spacer(Modifier.height(8.dp))
        GradientButton("Finish", Modifier.fillMaxWidth(), enabled = allCorrect, onClick = onDone)
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier.fillMaxWidth().clickable(onClick = onBack).padding(vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) { Text("Back to phrase", color = Umbra.TextMuted, fontWeight = FontWeight.Medium) }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun ImportPhrase(onBack: () -> Unit, onImport: suspend (String) -> Boolean, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Spacer(Modifier.height(20.dp))
        Text("Import wallet", color = Umbra.TextPrimary, fontFamily = Umbra.Display, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text("Enter your 12- or 24-word recovery phrase, separated by spaces.", color = Umbra.TextMuted, fontSize = 13.sp, lineHeight = 18.sp)
        Spacer(Modifier.height(18.dp))
        Box(Modifier.fillMaxWidth().heightIn(min = 120.dp).elevatedCard(RoundedCornerShape(16.dp)).padding(16.dp)) {
            BasicTextField(
                value = text,
                onValueChange = { text = it; error = null },
                textStyle = androidx.compose.ui.text.TextStyle(color = Umbra.TextPrimary, fontFamily = Umbra.Mono, fontSize = 15.sp, lineHeight = 22.sp),
                cursorBrush = SolidColor(Umbra.Primary),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    if (text.isEmpty()) Text("word1 word2 word3 …", color = Umbra.TextFaint, fontFamily = Umbra.Mono, fontSize = 15.sp)
                    inner()
                },
            )
        }
        error?.let { Spacer(Modifier.height(12.dp)); Text(it, color = Umbra.Error, fontSize = 13.sp) }
        Spacer(Modifier.weight(1f))
        GradientButton(if (busy) "Importing…" else "Import", Modifier.fillMaxWidth(), enabled = !busy && text.isNotBlank()) {
            scope.launch {
                busy = true; error = null
                val ok = runCatching { onImport(text) }.getOrDefault(false)
                busy = false
                if (ok) onDone() else error = "That doesn't look like a valid recovery phrase. Check the words and order."
            }
        }
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth().clickable(enabled = !busy, onClick = onBack).padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
            Text("Back", color = Umbra.TextMuted, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(8.dp))
    }
}
