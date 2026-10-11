package com.mechrobotix.mechbot

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat

class RadarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        color = ContextCompat.getColor(context, R.color.accent_dark)
    }

    private val robotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.accent)
    }

    private val obstaclePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val arcBounds = RectF()
    private var currentResult: SpatialAnalysisResult? = null

    fun updateSpatialData(result: SpatialAnalysisResult) {
        this.currentResult = result
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cX = width / 2f
        val cY = height * 0.85f
        val maxRadius = width * 0.40f

        // 1. DIBUJAR ABANICO DE RADAR FRONTAL (120° HACIA ARRIBA)
        val scaleStep = 0.333f
        for (i in 1..3) {
            val r = maxRadius * (i * scaleStep)
            arcBounds.set(cX - r, cY - r, cX + r, cY + r)
            canvas.drawArc(arcBounds, 210f, 120f, false, gridPaint)
        }

        // Líneas guía
        canvas.drawLine(cX, cY, cX, cY - maxRadius, gridPaint)

        val lineAngleLeft = Math.toRadians(210.0)
        canvas.drawLine(cX, cY, cX + (maxRadius * Math.cos(lineAngleLeft)).toFloat(), cY + (maxRadius * Math.sin(lineAngleLeft)).toFloat(), gridPaint)

        val lineAngleRight = Math.toRadians(330.0)
        canvas.drawLine(cX, cY, cX + (maxRadius * Math.cos(lineAngleRight)).toFloat(), cY + (maxRadius * Math.sin(lineAngleRight)).toFloat(), gridPaint)

        // 2. DIBUJAR POSICIÓN DEL ROBOT
        canvas.drawCircle(cX, cY, 10f, robotPaint)

        val result = currentResult ?: return
        val obstacles = result.allObstacles

        if (obstacles.isEmpty()) return

        // 3. DIBUJAR CADA OBSTÁCULO DETECTADO EN EL ESCENARIO
        val maxDistanceMeters = 3.0f

        for (obstacle in obstacles) {
            val normalizedDist = (obstacle.distanceMeters / maxDistanceMeters).coerceIn(0.05f, 1.0f)
            val obstacleRadiusPixels = maxRadius * normalizedDist

            val angleDeg = when (obstacle.zone) {
                "Izquierda" -> -30f
                "Derecha" -> 30f
                else -> 0f
            }

            val angleRad = Math.toRadians((angleDeg - 90.0))
            val obsX = cX + (obstacleRadiusPixels * Math.cos(angleRad)).toFloat()
            val obsY = cY + (obstacleRadiusPixels * Math.sin(angleRad)).toFloat()

            val isDanger = obstacle.isCritical || obstacle.distanceMeters <= 1.2f
            obstaclePaint.color = ContextCompat.getColor(
                context,
                if (isDanger) R.color.danger else R.color.accent
            )

            // Dibujar punto por objeto
            canvas.drawCircle(obsX, obsY, 12f, obstaclePaint)
        }
    }
}