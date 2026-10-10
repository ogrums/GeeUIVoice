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
 *   -e tts kokoro \
 *   -e voice ff_siwis \
 *   -e sidecar http://nimbus:13306 \
 *   -e cosy_model Fun-CosyVoice3-0.5B-2512
 * Kokoro stays on Lemonade. A short non-neutral line uses the sidecar when /health answered.
 * Une nouvelle commande START réapplique les extras présents. Un extra absent ne les efface pas.
 */
class VoiceService : Service() {
    private var loop: VoiceLoop? = null
    private var host = "http://nimbus:13305/api/v1"
    private var model = "whisper-small"
    private var chat = "gemma4e-flash-e2b-FLM"
    private var tts = "kokoro-v1"
    private var voice = ""
    private var prompt = com.geeui.voice.engine.LemonadeClient.SPOKEN
    private var sidecar = com.geeui.voiceemo.HostConfig.SIDECAR
    private var cosyModel = com.geeui.voice.engine.CosyTts.MODEL
    private val vad = mutableMapOf<String, String?>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, note())
        if (intent?.action == ACTION_STOP) {
            stopMic()
            return START_STICKY
        }
        if (intent?.action == ACTION_START) {
            intent.getStringExtra("host")?.let { host = it }
            intent.getStringExtra("model")?.let { model = it }
            intent.getStringExtra("chat")?.let { chat = it }
            intent.getStringExtra("tts")?.let { tts = it }
            intent.getStringExtra("voice")?.let { voice = it }
            intent.getStringExtra("prompt")?.let { prompt = it }
            intent.getStringExtra("sidecar")?.let {
                sidecar = com.geeui.voiceemo.HostConfig.orDefault(it, com.geeui.voiceemo.HostConfig.SIDECAR)
            }
            intent.getStringExtra("cosy_model")?.let { cosyModel = it }
            for (key in VAD_KEYS) intent.getStringExtra(key)?.let { vad[key] = it }
            loop?.stop()
            loop = VoiceLoop(
                AidlBus(this), host, cacheDir, VadConfig.from(vad).toVad(), model, chat, tts, voice, prompt, this,
                sidecar, cosyModel,
            )
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
        private val VAD_KEYS = listOf("vad_threshold", "vad_start_ms", "vad_hangover_ms", "vad_min_ms", "vad_max_ms")
    }
}
