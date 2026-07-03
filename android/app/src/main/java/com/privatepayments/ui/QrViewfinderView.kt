package com.privatepayments.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import com.journeyapps.barcodescanner.ViewfinderView

/**
 * QR-appropriate viewfinder: no scanning laser, square framing box with corner
 * brackets instead of zxing's default 1D-barcode laser-line UI.
 */
internal class QrViewfinderView(context: Context, attrs: AttributeSet?) : ViewfinderView(context, attrs) {

    init {
        // Stock zxing draws a red laser line meant for 1D barcodes; QR codes don't need it.
        setLaserVisibility(false)
    }

    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = CORNER_COLOR
        style = Paint.Style.STROKE
        strokeWidth = STROKE_WIDTH_PX
    }

    override fun onDraw(canvas: Canvas) {
        // Draws the dim mask + (now hidden) laser + possible-result points.
        super.onDraw(canvas)

        val rect = framingRect ?: return
        val arm = CORNER_ARM_PX

        // Top-left
        canvas.drawLine(rect.left.toFloat(), rect.top.toFloat(), rect.left + arm, rect.top.toFloat(), cornerPaint)
        canvas.drawLine(rect.left.toFloat(), rect.top.toFloat(), rect.left.toFloat(), rect.top + arm, cornerPaint)
        // Top-right
        canvas.drawLine(rect.right.toFloat(), rect.top.toFloat(), rect.right - arm, rect.top.toFloat(), cornerPaint)
        canvas.drawLine(rect.right.toFloat(), rect.top.toFloat(), rect.right.toFloat(), rect.top + arm, cornerPaint)
        // Bottom-left
        canvas.drawLine(rect.left.toFloat(), rect.bottom.toFloat(), rect.left + arm, rect.bottom.toFloat(), cornerPaint)
        canvas.drawLine(rect.left.toFloat(), rect.bottom.toFloat(), rect.left.toFloat(), rect.bottom - arm, cornerPaint)
        // Bottom-right
        canvas.drawLine(rect.right.toFloat(), rect.bottom.toFloat(), rect.right - arm, rect.bottom.toFloat(), cornerPaint)
        canvas.drawLine(rect.right.toFloat(), rect.bottom.toFloat(), rect.right.toFloat(), rect.bottom - arm, cornerPaint)
    }

    private companion object {
        const val CORNER_COLOR = 0xFF8B7CF6.toInt() // app purple
        val STROKE_WIDTH_PX = 4 * Resources.density
        val CORNER_ARM_PX = 24 * Resources.density
    }
}

/** Lazily-resolved density so the companion object's consts can stay simple floats. */
private object Resources {
    val density: Float by lazy { android.content.res.Resources.getSystem().displayMetrics.density }
}
