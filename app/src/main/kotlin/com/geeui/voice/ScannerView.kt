package com.geeui.voice

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * 24 bars. Idle is a comet that leaves the screen before turning back.
 * While the robot speaks, bars bloom from the center and shrink again.
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

    /** Lit width grows and shrinks from the middle. Center stays the hottest. */
    private fun talkLevel(i: Int, center: Float): Int {
        val t = tick * 0.22f
        val breath = 0.58f + 0.42f * kotlin.math.sin(t)
        val syll = 0.14f * kotlin.math.sin(t * 2.6f)
        val radius = (breath + syll).coerceIn(0.08f, 1f) * center
        val d = kotlin.math.abs(i - center)
        if (d > radius) return 0
        val fall = d / radius.coerceAtLeast(0.01f)
        return (5f - fall * 4f).toInt().coerceIn(1, 5)
    }
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
        val comet = VoiceHud.mode != "hear" && VoiceHud.mode != "think" && VoiceHud.mode != "talk"
        for (i in 0 until n) {
            val left = paddingLeft + i * (w + gap)
            val dist = kotlin.math.abs(i - center) / center
            val paint = when (VoiceHud.mode) {
                "hear" -> if (dist <= level) shades[5] else shades[0]
                "think" -> if (tick / 8 % 2 == 0 && dist < 0.35f) shades[3] else shades[0]
                "talk" -> shades[talkLevel(i, center)]
                else -> shades[brightness(i)]
            }
            canvas.drawRoundRect(left, top, left + w, top + h, 8f, 8f, paint)
        }
        if (comet) advance(n, 6)
        else tick++
        postInvalidateOnAnimation()
    }
}
