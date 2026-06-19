package com.privatepayments.ui.theme

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.privatepayments.R

/**
 * The design system's three brand families, bundled as variable fonts and
 * exposed at the weights the design uses. Variable-font weight axis needs
 * API 26+, which is our minSdk.
 *   - Space Grotesk  → balances & display
 *   - Plus Jakarta Sans → UI & body
 *   - JetBrains Mono → addresses, seed words, hashes
 */
@OptIn(ExperimentalTextApi::class)
private fun variable(resId: Int, weight: Int) = Font(
    resId,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

private fun family(resId: Int, vararg weights: Int) =
    FontFamily(weights.map { variable(resId, it) })

val SpaceGrotesk = family(R.font.space_grotesk, 400, 500, 600, 700)
val PlusJakartaSans = family(R.font.plus_jakarta_sans, 400, 500, 600, 700)
val JetBrainsMono = family(R.font.jetbrains_mono, 400, 500, 600)
