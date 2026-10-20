package com.gold.chatapp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class VoiceVisualizerView(context: Context, attrs: AttributeSet) : View(context, attrs) {

    private val paintBase = Paint().apply {
        color = 0xFFD1D1D1.toInt() // Light Grey background
        isAntiAlias = true
        style = Paint.Style.FILL
    }

    private val paintProgress = Paint().apply {
        color = 0xFF00E676.toInt() // WhatsApp Green
        isAntiAlias = true
        style = Paint.Style.FILL
    }

    private val paintThumb = Paint().apply {
        color = 0xFF808080.toInt() // Grey #808080
        isAntiAlias = true
        style = Paint.Style.FILL
        setShadowLayer(6f, 0f, 2f, 0x40000000)
    }

    private var thumbColor: Int = 0xFF808080.toInt()

    private var progress = 0f
    private var amplitudes: List<Float> = emptyList()
    private val barRect = RectF()

    // Configuration for the "WhatsApp Look" - Forced Exact Specs
    private val barWidthDp = 3f
    private val barSpacingDp = 0.5f
    private val thumbDiameterDp = 14f

    private fun dpToPx(dp: Float): Float = dp * context.resources.displayMetrics.density

    init {
        // Necessary for setShadowLayer to work consistently on all devices
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    fun setAmplitudes(amps: List<Float>) {
        this.amplitudes = amps
        postInvalidate()
    }

    fun setProgress(p: Float) {
        this.progress = p.coerceIn(0f, 1f)
        postInvalidate()
    }

    fun setThumbColor(color: Int) {
        this.thumbColor = color
        paintThumb.color = color
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if ((width <= 0) || (height <= 0)) return

        val centerY = height / 2f
        val thickness = dpToPx(barWidthDp)
        val spacing = dpToPx(barSpacingDp)
        val step = thickness + spacing

        val barCount = (width / step).toInt()
        val cornerRadius = thickness / 2f

        for (i in 0 until barCount) {
            val x = (i * step) + (thickness / 2f)

            // Sample amplitude based on position with averaging if many points exist
            val ampValue = if (amplitudes.isNotEmpty()) {
                val start = (i.toFloat() / barCount * amplitudes.size).toInt()
                val end = ((i + 1).toFloat() / barCount * amplitudes.size).toInt()
                    .coerceIn(0, amplitudes.size)
                if (end > start) {
                    amplitudes.subList(start, end).average().toFloat()
                } else {
                    amplitudes[start.coerceIn(0, amplitudes.size - 1)]
                }
            } else {
                0.1f
            }

            // Apply a strong dynamic range mapping (WhatsApp style)
            val scaledAmp = (0.15f + (0.85f * ampValue)).coerceIn(0.15f, 1.0f)
            val barHeight = (height * 0.9f) * scaledAmp // Tall requirement

            barRect.set(
                x - (thickness / 2f),
                centerY - (barHeight / 2f),
                x + (thickness / 2f),
                centerY + (barHeight / 2f),
            )

            val barProgressThreshold = i.toFloat() / barCount
            if (barProgressThreshold <= progress) {
                canvas.drawRoundRect(barRect, cornerRadius, cornerRadius, paintProgress)
            } else {
                canvas.drawRoundRect(barRect, cornerRadius, cornerRadius, paintBase)
            }
        }

        val tRadius = thumbRadius()
        val thumbX = (progress * width).coerceIn(tRadius, width - tRadius)
        canvas.drawCircle(thumbX, centerY, tRadius, paintThumb)
    }

    private fun thumbRadius() = dpToPx(thumbDiameterDp) / 2f
}
