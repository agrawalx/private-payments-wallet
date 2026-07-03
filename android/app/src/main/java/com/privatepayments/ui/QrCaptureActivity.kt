package com.privatepayments.ui

import com.journeyapps.barcodescanner.CaptureActivity
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.privatepayments.R

/**
 * Custom capture activity that swaps zxing's stock 1D-barcode scanning UI
 * (laser line + "Place a barcode..." prompt) for a QR-appropriate square
 * framing box with corner brackets. See [QrViewfinderView] and
 * res/layout/activity_qr_capture.xml + res/layout/qr_barcode_scanner.xml.
 */
internal class QrCaptureActivity : CaptureActivity() {
    override fun initializeContent(): DecoratedBarcodeView {
        setContentView(R.layout.activity_qr_capture)
        return findViewById(R.id.zxing_barcode_scanner)
    }
}
