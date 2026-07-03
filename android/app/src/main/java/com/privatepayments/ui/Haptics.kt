package com.privatepayments.ui

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

/** Small tactile-feedback wrapper — see call sites: ConfirmScreen (tick on confirm),
 *  Success entry (confirm), ProofScreen error (reject), CopyCard (tick),
 *  ModeSlider (tick), PeopleScreen contact save (confirm). */
@Composable
internal fun rememberHaptics(): Haptics {
    val v = LocalView.current
    return remember(v) { Haptics(v) }
}

internal class Haptics(private val view: View) {
    fun tick() = view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
    fun confirm() {
        if (Build.VERSION.SDK_INT >= 30) view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        else view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }
    fun reject() {
        if (Build.VERSION.SDK_INT >= 30) view.performHapticFeedback(HapticFeedbackConstants.REJECT)
        else view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }
}
