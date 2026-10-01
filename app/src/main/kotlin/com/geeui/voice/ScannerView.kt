package com.geeui.voice

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * A red scanning bar. Idle sweeps slowly. Hearing lights from the center
 * with the mic level. Speaking runs a faster sweep. Thinking pulses.
 */
class ScannerView(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    constructor(context: Context) : this(context, null)

    private val dim = Paint().apply { color = Color.rgb(40, 0, 0) }
    private val hot = Paint().apply { color = Color.rgb(255, 40, 30) }
    private val mid = Paint().apply { color = Color.rgb(140, 16, 12) }
    private var tick = 0

    override fun onDraw(canvas: Canvas) {
        val n = 24
        val gap = 6f
        val w = (width - paddingLeft - paddingRight - gap * (n - 1)) / n
        val top = height * 0.35f
        val h = height * 0.3f
        val level = VoiceHud.level.coerceIn(0f, 1f)
        val center = (n - 1) / 2f
        val sweep = when (VoiceHud.mode) {
            "talk" -> (tick / 2) % n
            "think" -> n / 2
            else -> (tick / 4) % n
        }
        for (i in 0 until n) {
            val left = paddingLeft + i * (w + gap)
            val dist = kotlin.math.abs(i - center) / center
            val paint = when (VoiceHud.mode) {
                "hear" -> if (dist <= level) hot else dim
                "talk" -> if (kotlin.math.abs(i - sweep) <= 1) hot else if (kotlin.math.abs(i - sweep) <= 3) mid else dim
                "think" -> if (tick / 8 % 2 == 0 && dist < 0.35f) mid else dim
                else -> if (i == sweep) hot else if (kotlin.math.abs(i - sweep) == 1) mid else dim
            }
            canvas.drawRoundRect(left, top, left + w, top + h, 4f, 4f, paint)
        }
        tick++
        postInvalidateOnAnimation()
    }
}
