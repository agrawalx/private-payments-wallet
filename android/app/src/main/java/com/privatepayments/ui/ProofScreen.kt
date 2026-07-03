package com.privatepayments.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.privatepayments.ui.theme.ProofOrb
import com.privatepayments.ui.theme.Umbra
import com.privatepayments.ui.theme.umbraScreen

private enum class StepState { Done, Active, Pending }

private data class Step(val title: String, val detail: String? = null)

private val steps = listOf(
    Step("Prepared your notes"),
    Step(
        "Generating zero-knowledge proof",
        "Your phone is building cryptographic math that proves the payment is valid — without revealing it.",
    ),
    Step("Submit to Stellar network"),
    Step("Confirm payment"),
)

/**
 * Option A "narrated stepper". Drives the real on-device prove: step 2 runs
 * [runProof] (which calls into the Rust prover via UniFFI) and reports its ms.
 */
@Composable
fun ProofScreen(
    amount: String,
    recipient: String,
    runProof: suspend (advance: (Int) -> Unit) -> String,
    onDone: (hash: String, proofMs: Long?) -> Unit,
    onCancel: () -> Unit,
    title: String = "Sending privately",
    isPublic: Boolean = false,
) {
    // Public (deposit) reads amber/globe; private (send/withdraw) purple/lock.
    val accent = if (isPublic) Umbra.Public else Umbra.Primary
    // 0..4 — index of the first not-yet-done step (4 == all done).
    var current by remember { mutableStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    val haptics = rememberHaptics()

    LaunchedEffect(Unit) {
        try {
            current = 1
            // Proving starts as soon as we hand control to runProof (step 1 =
            // assembled is instantaneous vs. the actual prove); stop the clock
            // when advance(2) fires (2=proved) to time just the ZK proving step.
            val proveStart = android.os.SystemClock.elapsedRealtime()
            var proofMs: Long? = null
            // runProof drives the real pipeline and reports step progress
            // (1=assembled, 2=proved, 3=submitted); returns the tx hash.
            val txHash = runProof { step ->
                if (step == 2 && proofMs == null) proofMs = android.os.SystemClock.elapsedRealtime() - proveStart
                current = step
            }
            current = 4
            kotlinx.coroutines.delay(300)
            onDone(txHash, proofMs)
        } catch (e: Exception) {
            error = e.message ?: "Submission failed"
            haptics.reject()
        }
    }

    Column(
        Modifier.fillMaxSize().umbraScreen().padding(24.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Text(title, color = Umbra.TextMuted, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(amount, color = Umbra.TextPrimary, fontFamily = Umbra.Display, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(6.dp))
            Text("XLM", color = Umbra.TextMuted, fontSize = 16.sp)
            Spacer(Modifier.width(14.dp))
            Icon(Icons.Filled.ArrowForward, null, tint = Umbra.TextFaint, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(recipient, color = accent, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        }

        Spacer(Modifier.height(36.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            ProofOrb(
                icon = if (isPublic) Icons.Filled.Public else Icons.Filled.Shield,
                accent = accent,
                spin = error == null && current < 4,
            )
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(
                when {
                    error != null -> error!!.take(120)
                    current >= 4 -> "Done"
                    else -> "About 7 seconds · keep Stella open"
                },
                color = if (error != null) Umbra.Error else Umbra.TextMuted,
                fontSize = 12.sp,
            )
        }

        Spacer(Modifier.height(32.dp))
        steps.forEachIndexed { i, step ->
            val state = when {
                i < current -> StepState.Done
                i == current && error == null -> StepState.Active
                else -> StepState.Pending
            }
            StepRow(step, state, accent)
        }

        Spacer(Modifier.height(24.dp))
        ReassurancePanel(isPublic)

        Spacer(Modifier.weight(1f))
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                .background(Umbra.SurfaceElevated)
                .clickable(onClick = onCancel)
                .padding(vertical = 16.dp),
            contentAlignment = Alignment.Center,
        ) { Text(if (error != null) "Close" else "Cancel", color = Umbra.TextSecondary, fontWeight = FontWeight.Medium) }
    }
}

@Composable
private fun StepRow(step: Step, state: StepState, accent: androidx.compose.ui.graphics.Color) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Box(
            Modifier.size(26.dp).clip(CircleShape)
                .background(
                    when (state) {
                        StepState.Done -> accent
                        StepState.Active -> Umbra.SurfaceHi
                        StepState.Pending -> Umbra.Surface
                    }
                ),
            contentAlignment = Alignment.Center,
        ) {
            when (state) {
                StepState.Done -> Icon(Icons.Filled.Check, null, tint = Umbra.Bg, modifier = Modifier.size(15.dp))
                StepState.Active -> CircularProgressIndicator(
                    Modifier.size(15.dp), color = accent, strokeWidth = 2.dp,
                )
                StepState.Pending -> {}
            }
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(
                step.title,
                color = if (state == StepState.Pending) Umbra.TextFaint else Umbra.TextPrimary,
                fontSize = 15.sp,
                fontWeight = if (state == StepState.Active) FontWeight.SemiBold else FontWeight.Normal,
            )
            if (step.detail != null && state == StepState.Active) {
                Spacer(Modifier.height(4.dp))
                Text(step.detail, color = Umbra.TextMuted, fontSize = 12.sp, lineHeight = 17.sp)
            }
        }
    }
}

@Composable
private fun ReassurancePanel(isPublic: Boolean) {
    // Per the design: a public action always carries a judgment-free
    // "this is visible on-chain" note; private actions reassure nothing leaks.
    val tint = if (isPublic) Umbra.Public else Umbra.Primary
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(if (isPublic) Umbra.PublicWash else Umbra.Surface).padding(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            if (isPublic) Icons.Filled.Public else Icons.Filled.VisibilityOff,
            null, tint = tint, modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            if (isPublic)
                "Public · this deposit is visible on-chain — your address and the amount in. Your shielded balance and future sends stay private."
            else
                "Nothing is leaving your device yet. The proof reveals only that the math checks out — never who you're paying or how much.",
            color = Umbra.TextMuted, fontSize = 12.sp, lineHeight = 18.sp,
        )
    }
}
