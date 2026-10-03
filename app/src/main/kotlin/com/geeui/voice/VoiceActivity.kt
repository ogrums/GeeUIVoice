package com.geeui.voice

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.os.Handler
import android.os.Looper
import android.media.MediaPlayer
import android.view.Gravity
import android.widget.Button
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.text.TextUtils

/** 480×480 round screen. The top 30 px is the robot battery strip: draw only, never a control. */
class VoiceActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var caption: TextView
    private lateinit var mic: Switch
    private var painting = false
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
            caption.text = buildString {
                if (VoiceHud.heard.isNotBlank()) append("« ").append(VoiceHud.heard).append(" »")
                if (VoiceHud.answer.isNotBlank()) {
                    if (isNotEmpty()) append("\n")
                    append(VoiceHud.answer)
                }
                if (isEmpty()) append(VoiceHud.line)
            }
            if (mic.isChecked != VoiceHud.mic) {
                painting = true
                mic.isChecked = VoiceHud.mic
                painting = false
            }
            handler.postDelayed(this, 120)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 30, 0, 72)
            isClickable = false
        }
        val title = label("GeeUI Voice", 13f, Color.rgb(180, 40, 32)).apply { sidePad() }
        status = label("VEILLE", 20f, Color.rgb(255, 64, 48)).apply { sidePad() }
        caption = label("", 15f, Color.rgb(230, 190, 180)).apply {
            maxLines = 6
            ellipsize = TextUtils.TruncateAt.END
            sidePad()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
        }
        val scan = ScannerView(this).apply {
            isClickable = false
            isFocusable = false
            layoutParams = LinearLayout.LayoutParams(480, 150)
        }
        mic = Switch(this).apply {
            text = "Micro"
            setTextColor(Color.rgb(255, 48, 36))
            setOnCheckedChangeListener { _: CompoundButton, checked: Boolean ->
                if (painting) return@setOnCheckedChangeListener
                if (checked) askMic() else send(VoiceService.ACTION_STOP)
            }
            val pad = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            pad.setMargins(72, 8, 72, 0)
            layoutParams = pad
        }
        val tests = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            val pad = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 52)
            pad.setMargins(48, 8, 48, 0)
            layoutParams = pad
        }
        tests.addView(testButton("MP3") { playSample("olivier.mp3") })
        tests.addView(testButton("WAV") { playSample("olivier.wav") })
        root.addView(title)
        root.addView(status)
        root.addView(scan)
        root.addView(caption)
        root.addView(mic)
        root.addView(tests)
        setContentView(root)
        VoiceHud.line = "micro coupé"
    }

    private fun label(text: String, size: Float, color: Int): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            gravity = Gravity.CENTER
            isClickable = false
            isFocusable = false
        }
    }

    private fun TextView.sidePad() {
        val pad = 72
        setPadding(pad, 0, pad, 0)
    }

    private var sample: MediaPlayer? = null

    private fun testButton(label: String, click: () -> Unit): Button {
        return Button(this).apply {
            text = label
            setTextColor(Color.rgb(255, 48, 36))
            setBackgroundColor(Color.rgb(24, 0, 0))
            setOnClickListener { click() }
            val pad = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            pad.setMargins(6, 0, 6, 0)
            layoutParams = pad
        }
    }

    /** Bundled clip, same MediaPlayer path as LTPAudioService. No mic, no Lemonade. */
    private fun playSample(asset: String) {
        sample?.release()
        sample = null
        val mp = MediaPlayer()
        sample = mp
        try {
            assets.openFd(asset).use { afd ->
                mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            }
            mp.setOnCompletionListener {
                VoiceHud.mode = "idle"
                VoiceHud.line = "$asset fini"
                if (sample === it) {
                    it.release()
                    sample = null
                }
            }
            mp.setOnErrorListener { player, what, extra ->
                VoiceHud.line = "$asset $what/$extra"
                player.release()
                if (sample === player) sample = null
                true
            }
            mp.prepare()
            mp.start()
            VoiceHud.heard = "Olivier, il fait beau aujourd'hui, n'est-ce pas ?"
            VoiceHud.answer = "Ah ah ah !"
            VoiceHud.line = asset
            VoiceHud.mode = "talk"
        } catch (e: Exception) {
            mp.release()
            sample = null
            VoiceHud.line = "$asset: ${e.javaClass.simpleName}"
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
            VoiceHud.line = "autorise le micro dans les réglages"
            val settings = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null),
            )
            startActivity(settings)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    private fun send(action: String) {
        val intent = Intent(this, VoiceService::class.java).setAction(action)
        intent.putExtras(getIntent())
        startForegroundService(intent)
    }

    override fun onDestroy() {
        sample?.release()
        sample = null
        super.onDestroy()
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
