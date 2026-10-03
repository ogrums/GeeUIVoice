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
    private val tail = Paint().apply { color = Color.rgb(70, 8, 6) }
    private var tick = 0

    /** Head goes left to right, then right to left. */
    private fun bounce(n: Int, step: Int): Int {
        val span = (n - 1).coerceAtLeast(1)
        val pos = (tick / step) % (span * 2)
        return if (pos < span) pos else span * 2 - pos
    }

    override fun onDraw(canvas: Canvas) {
        val n = 24
        val gap = 6f
        val w = (width - paddingLeft - paddingRight - gap * (n - 1)) / n
        val top = height * 0.12f
        val h = height * 0.76f
        val level = VoiceHud.level.coerceIn(0f, 1f)
        val center = (n - 1) / 2f
        val sweep = bounce(n, if (VoiceHud.mode == "talk") 2 else 4)
        for (i in 0 until n) {
            val left = paddingLeft + i * (w + gap)
            val dist = kotlin.math.abs(i - center) / center
            val fromSweep = kotlin.math.abs(i - sweep)
            val paint = when (VoiceHud.mode) {
                "hear" -> if (dist <= level) hot else dim
                "think" -> if (tick / 8 % 2 == 0 && dist < 0.35f) mid else dim
                else -> when (fromSweep) {
                    0 -> hot
                    1 -> mid
                    2 -> tail
                    else -> dim
                }
            }
            canvas.drawRoundRect(left, top, left + w, top + h, 8f, 8f, paint)
        }
        tick++
        postInvalidateOnAnimation()
    }
}
