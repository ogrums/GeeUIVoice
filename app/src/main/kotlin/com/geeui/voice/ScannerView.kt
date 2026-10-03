package com.geeui.voice

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * 24 bars. A comet of brightness 5..1 travels across, leaving 0 behind.
 * It leaves the screen completely, then comes back the other way.
 * Hearing lights from the center. Thinking pulses.
 */
class ScannerView(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    constructor(context: Context) : this(context, null)

    private val shades = Array(6) { level ->
        Paint().apply {
            color = when (level) {
                5 -> Color.rgb(255, 48, 36)
                4 -> Color.rgb(214, 34, 26)
                3 -> Color.rgb(150, 20, 16)
                2 -> Color.rgb(96, 12, 10)
                1 -> Color.rgb(56, 6, 5)
                else -> Color.rgb(22, 0, 0)
            }
        }
    }
    private var tick = 0
    private var head = 0
    private var dir = 1

    /** 5 on the leading bar, then 4, 3, 2, 1 behind it. Everything else is off. */
    private fun brightness(i: Int): Int {
        val behind = if (dir > 0) head - i else i - head
        return if (behind in 0..4) 5 - behind else 0
    }

    private fun advance(n: Int, step: Int) {
        tick++
        if (tick % step != 0) return
        head += dir
        if (dir > 0 && head >= n + 4) {
            dir = -1
            head = n
        } else if (dir < 0 && head <= -5) {
            dir = 1
            head = -1
        }
    }

    override fun onDraw(canvas: Canvas) {
        val n = 24
        val gap = 6f
        val w = (width - paddingLeft - paddingRight - gap * (n - 1)) / n
        val top = height * 0.12f
        val h = height * 0.76f
        val level = VoiceHud.level.coerceIn(0f, 1f)
        val center = (n - 1) / 2f
        val comet = VoiceHud.mode != "hear" && VoiceHud.mode != "think"
        for (i in 0 until n) {
            val left = paddingLeft + i * (w + gap)
            val dist = kotlin.math.abs(i - center) / center
            val paint = when (VoiceHud.mode) {
                "hear" -> if (dist <= level) shades[5] else shades[0]
                "think" -> if (tick / 8 % 2 == 0 && dist < 0.35f) shades[3] else shades[0]
                else -> shades[brightness(i)]
            }
            canvas.drawRoundRect(left, top, left + w, top + h, 8f, 8f, paint)
        }
        if (comet) advance(n, if (VoiceHud.mode == "talk") 3 else 6)
        else tick++
        postInvalidateOnAnimation()
    }
}
