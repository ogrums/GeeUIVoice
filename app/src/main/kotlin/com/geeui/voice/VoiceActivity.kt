package com.geeui.voice

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/** Black screen, red scanner, one line of status. Not a copy of any show. */
class VoiceActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var caption: TextView
    private val refresh = object : Runnable {
        override fun run() {
            status.text = when (VoiceHud.mode) {
                "hear" -> "ÉCOUTE"
                "think" -> "CALCUL"
                "talk" -> "PAROLE"
                else -> "VEILLE"
            }
            caption.text = VoiceHud.line
            handler.postDelayed(this, 120)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        status = TextView(this).apply {
            setTextColor(Color.rgb(255, 48, 36))
            textSize = 28f
            gravity = Gravity.CENTER
            text = "VEILLE"
        }
        caption = TextView(this).apply {
            setTextColor(Color.rgb(180, 180, 180))
            textSize = 16f
            gravity = Gravity.CENTER
        }
        val scan = ScannerView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                220,
            )
        }
        root.addView(status)
        root.addView(scan)
        root.addView(caption)
        setContentView(root)
        startService(Intent(this, VoiceService::class.java).putExtras(intent))
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }
}
