package com.mechrobotix.mechbot

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.min
import kotlin.math.roundToInt

class DetectionOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 12f * scaledDensity
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private var detections: List<DetectionResult> = emptyList()
    private var imageWidth = 0
    private var imageHeight = 0

    fun setFrame(detections: List<DetectionResult>, imageWidth: Int, imageHeight: Int) {
        this.detections = detections
        this.imageWidth = imageWidth
        this.imageHeight = imageHeight
        invalidate()
    }

    fun clear() {
        detections = emptyList()
        imageWidth = 0
        imageHeight = 0
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (imageWidth <= 0 || imageHeight <= 0 || detections.isEmpty()) return
        val scale = min(width.toFloat() / imageWidth, height.toFloat() / imageHeight)
        val renderedWidth = imageWidth * scale
        val renderedHeight = imageHeight * scale
        val offsetX = (width - renderedWidth) / 2f
        val offsetY = (height - renderedHeight) / 2f
        detections.forEach { detection ->
            val color = colorFor(detection.proximity)
            boxPaint.color = color
            textPaint.color = ContextCompat.getColor(context, R.color.bg_dark)
            labelPaint.color = color
            val box = RectF(
                offsetX + detection.left * renderedWidth,
                offsetY + detection.top * renderedHeight,
                offsetX + detection.right * renderedWidth,
                offsetY + detection.bottom * renderedHeight
            )
            canvas.drawRoundRect(box, 8f * density, 8f * density, boxPaint)
            val confidence = if (detection.confidence > 0f) {
                "${(detection.confidence * 100f).roundToInt()}%"
            } else {
                null
            }
            val text = listOfNotNull(detection.label, confidence, detection.proximity.text).joinToString(" · ")
            val paddingX = 7f * density
            val paddingY = 5f * density
            val textWidth = textPaint.measureText(text)
            val labelHeight = textPaint.fontMetrics.run { bottom - top } + paddingY * 2f
            val labelWidth = textWidth + paddingX * 2f
            val labelLeft = box.left.coerceIn(0f, (width - labelWidth).coerceAtLeast(0f))
            val preferredTop = box.top - labelHeight
            val labelTop = if (preferredTop >= 0f) preferredTop else box.top
            val labelRect = RectF(labelLeft, labelTop, labelLeft + labelWidth, labelTop + labelHeight)
            canvas.drawRoundRect(labelRect, 7f * density, 7f * density, labelPaint)
            val baseline = labelRect.top + paddingY - textPaint.fontMetrics.top
            canvas.drawText(text, labelRect.left + paddingX, baseline, textPaint)
        }
    }

    private fun colorFor(proximity: ObjectProximity): Int = ContextCompat.getColor(
        context,
        when (proximity) {
            ObjectProximity.VERY_CLOSE -> R.color.detection_very_close
            ObjectProximity.CLOSE -> R.color.detection_close
            ObjectProximity.MEDIUM -> R.color.detection_medium
            ObjectProximity.FAR -> R.color.detection_far
            ObjectProximity.VERY_FAR -> R.color.detection_very_far
        }
    )
}
