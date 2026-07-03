package com.privatepayments.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.privatepayments.ui.theme.Umbra

/** Launches the camera QR scanner and reports the decoded text via [onResult]. */
@Composable
internal fun rememberQrScanner(onResult: (String) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ScanContract()) { r -> r.contents?.let(onResult) }
    return {
        launcher.launch(
            ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setBeepEnabled(false).setOrientationLocked(true)
                .setCaptureActivity(QrCaptureActivity::class.java)
                .setPrompt("Scan a Stella QR code"),
        )
    }
}

/** A small QR-scan icon button — tap to launch the camera scanner. */
@Composable
internal fun ScanIconButton(modifier: Modifier = Modifier, onResult: (String) -> Unit) {
    val scan = rememberQrScanner(onResult)
    Icon(
        Icons.Filled.QrCodeScanner, "Scan QR", tint = Umbra.TextFaint,
        modifier = modifier.size(20.dp).clickable { scan() },
    )
}
