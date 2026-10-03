package com.geeui.voice

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Home: scanner, status, mic on and mic off. Recording starts only on the button. */
class VoiceActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var caption: TextView
    private lateinit var on: Button
    private lateinit var off: Button
    private val refresh = object : Runnable {
        override fun run() {
            status.text = when {
                !VoiceHud.mic && VoiceHud.mode == "idle" -> "VEILLE"
                VoiceHud.mode == "hear" -> "ÉCOUTE"
                VoiceHud.mode == "think" -> "CALCUL"
                VoiceHud.mode == "talk" -> "PAROLE"
                VoiceHud.mic -> "MICRO OUVERT"
                else -> "VEILLE"
            }
            caption.text = VoiceHud.line
            on.isEnabled = !VoiceHud.mic
            off.isEnabled = VoiceHud.mic
            handler.postDelayed(this, 120)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(32, 24, 32, 24)
        }
        val title = TextView(this).apply {
            setTextColor(Color.rgb(255, 48, 36))
            textSize = 22f
            gravity = Gravity.CENTER
            text = "GeeUI Voice"
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
            text = "micro coupé"
        }
        val scan = ScannerView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                180,
            )
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        on = button("Activer le micro") { askMic() }
        off = button("Couper le micro") { send(VoiceService.ACTION_STOP) }
        off.isEnabled = false
        row.addView(on)
        row.addView(off)
        root.addView(title)
        root.addView(scan)
        root.addView(status)
        root.addView(caption)
        root.addView(row)
        setContentView(root)
        VoiceHud.line = "micro coupé"
    }

    private fun button(label: String, click: () -> Unit): Button {
        return Button(this).apply {
            text = label
            setTextColor(Color.rgb(255, 48, 36))
            setBackgroundColor(Color.rgb(24, 0, 0))
            setOnClickListener { click() }
            val pad = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            pad.setMargins(12, 24, 12, 0)
            layoutParams = pad
        }
    }

    private fun askMic() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            send(VoiceService.ACTION_START)
        } else {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, grants: IntArray) {
        super.onRequestPermissionsResult(code, perms, grants)
        if (code == 1 && grants.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            send(VoiceService.ACTION_START)
        } else {
            VoiceHud.line = "permission micro refusée"
        }
    }

    private fun send(action: String) {
        val intent = Intent(this, VoiceService::class.java).setAction(action)
        intent.putExtras(getIntent())
        startForegroundService(intent)
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
