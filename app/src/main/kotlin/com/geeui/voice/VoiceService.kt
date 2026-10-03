package com.geeui.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.geeui.voice.audio.VadConfig

/**
 * adb shell am start -n com.geeui.voice/.VoiceActivity \
 *   -e host http://192.168.1.10:13305/api/v1 \
 *   -e model whisper-small \
 *   -e chat <id-du-llm> \
 *   -e tts kokoro
 * STT inconnu → whisper-base. TTS inconnu → kokoro, sinon le premier modèle speech.
 */
class VoiceService : Service() {
    private var loop: VoiceLoop? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, note())
        if (intent?.action == ACTION_STOP) {
            stopMic()
            return START_STICKY
        }
        if (intent?.action == ACTION_START) {
            val host = intent.getStringExtra("host") ?: "http://127.0.0.1:13305/api/v1"
            val extras = listOf("vad_threshold", "vad_start_ms", "vad_hangover_ms", "vad_min_ms", "vad_max_ms")
                .associateWith { intent.getStringExtra(it) }
            if (loop == null) {
                val model = intent.getStringExtra("model") ?: "whisper-base"
                val chat = intent.getStringExtra("chat") ?: ""
                val tts = intent.getStringExtra("tts") ?: "kokoro"
                loop = VoiceLoop(
                    AidlBus(this), host, cacheDir, VadConfig.from(extras).toVad(), model, chat, tts, this,
                )
            }
            loop?.start()
        }
        return START_STICKY
    }

    private fun stopMic() {
        loop?.stop()
        loop = null
        VoiceHud.mode = "idle"
        VoiceHud.level = 0f
        VoiceHud.mic = false
        VoiceHud.line = "micro coupé"
    }

    override fun onDestroy() {
        loop?.stop()
        loop = null
        super.onDestroy()
    }

    private fun note(): Notification {
        val mgr = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            mgr.createNotificationChannel(
                NotificationChannel("voice", "GeeUIVoice", NotificationManager.IMPORTANCE_LOW),
            )
        }
        return Notification.Builder(this, "voice")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("GeeUIVoice")
            .setContentText(if (VoiceHud.mic) "micro ouvert" else "micro coupé")
            .build()
    }

    companion object {
        const val ACTION_START = "com.geeui.voice.START"
        const val ACTION_STOP = "com.geeui.voice.STOP"
    }
}
