package com.privatepayments.ui

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Renders [content] as a QR code. `fg`/`bg` default to a themed dark-on-light
 * code; pass palette colors if you want it to invert with the mode. `quietZone`
 * keeps a scannable margin.
 */
@Composable
fun QrCode(
    content: String,
    size: Dp,
    fg: Color,
    bg: Color,
    modifier: Modifier = Modifier,
) {
    val fgArgb = fg.toArgb()
    val bgArgb = bg.toArgb()
    val bitmap = remember(content, fgArgb, bgArgb) {
        encodeQr(content, 512, fgArgb, bgArgb)
    }
    Image(bitmap = bitmap.asImageBitmap(), contentDescription = "QR code", modifier = modifier)
}

private fun encodeQr(content: String, px: Int, fg: Int, bg: Int): Bitmap {
    val hints = mapOf(
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        EncodeHintType.MARGIN to 1,
    )
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, px, px, hints)
    val w = matrix.width
    val h = matrix.height
    val pixels = IntArray(w * h)
    for (y in 0 until h) {
        val row = y * w
        for (x in 0 until w) {
            pixels[row + x] = if (matrix.get(x, y)) fg else bg
        }
    }
    return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
        it.setPixels(pixels, 0, w, 0, 0, w, h)
    }
}
