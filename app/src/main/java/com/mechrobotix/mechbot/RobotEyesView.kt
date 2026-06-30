package com.mechrobotix.mechbot

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

class RobotEyesView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val eyePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(96, 220, 255) }
    private val pupilPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(5, 25, 40) }
    private val shinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    private var pupilX = 0f
    private var pupilY = 0f
    private var animator: ValueAnimator? = null

    fun react(command: MechbotCommand) {
        val target = when (command) {
            MechbotCommand.FORWARD -> 0f to -1f
            MechbotCommand.BACKWARD -> 0f to 1f
            MechbotCommand.LEFT -> -1f to 0f
            MechbotCommand.RIGHT -> 1f to 0f
            MechbotCommand.STOP -> 0f to 0f
            else -> pupilX to pupilY
        }
        animateTo(target.first, target.second)
    }

    private fun animateTo(targetX: Float, targetY: Float) {
        val startX = pupilX
        val startY = pupilY
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 160L
            addUpdateListener { valueAnimator ->
                val t = valueAnimator.animatedValue as Float
                pupilX = startX + (targetX - startX) * t
                pupilY = startY + (targetY - startY) * t
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.BLACK)

        val w = width.toFloat()
        val h = height.toFloat()
        val eyeW = w * 0.28f
        val eyeH = h * 0.42f
        val radius = min(eyeW, eyeH) * 0.28f
        val cy = h * 0.5f
        val leftCx = w * 0.32f
        val rightCx = w * 0.68f

        drawEye(canvas, leftCx, cy, eyeW, eyeH, radius)
        drawEye(canvas, rightCx, cy, eyeW, eyeH, radius)
    }

    private fun drawEye(canvas: Canvas, cx: Float, cy: Float, eyeW: Float, eyeH: Float, radius: Float) {
        val eyeRect = RectF(cx - eyeW / 2f, cy - eyeH / 2f, cx + eyeW / 2f, cy + eyeH / 2f)
        canvas.drawRoundRect(eyeRect, radius, radius, eyePaint)

        val maxOffsetX = eyeW * 0.18f
        val maxOffsetY = eyeH * 0.18f
        val pupilRadius = min(eyeW, eyeH) * 0.17f
        val px = cx + pupilX * maxOffsetX
        val py = cy + pupilY * maxOffsetY
        canvas.drawCircle(px, py, pupilRadius, pupilPaint)
        canvas.drawCircle(px - pupilRadius * 0.35f, py - pupilRadius * 0.35f, pupilRadius * 0.22f, shinePaint)
    }
}
