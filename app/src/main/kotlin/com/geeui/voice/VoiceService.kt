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
 *   -e stt_host http://nimbus:13305/api/v1 \
 *   -e model Whisper-Small \
 *   -e llm_host http://nimbus:13305/api/v1 \
 *   -e chat gemma4e-flash-e2b-FLM \
 *   -e tts_host http://nimbus:13305/api/v1 \
 *   -e tts kokoro-v1 \
 *   -e image_host http://nimbus:13305/api/v1 \
 *   -e image SD-Turbo \
 *   -e voice ff_siwis \
 *   -e sidecar http://nimbus:13306 \
 *   -e cosy_model Fun-CosyVoice3-0.5B-2512
 * `-e host` recopié sur STT, LLM, TTS et image quand l'hôte dédié est absent.
 * Les défauts compilés sont dans Defaults.kt. Le menu ne s'ouvre qu'avec `--es mode debug`.
 */
class VoiceService : Service() {
    private var loop: VoiceLoop? = null
    private var sttHost = Defaults.STT_HOST
    private var llmHost = Defaults.LLM_HOST
    private var ttsHost = Defaults.TTS_HOST
    private var imageHost = Defaults.IMAGE_HOST
    private var model = Defaults.STT_MODEL
    private var chat = Defaults.LLM_MODEL
    private var tts = Defaults.TTS_MODEL
    private var voice = Defaults.VOICE
    private var prompt = com.geeui.voice.engine.LemonadeClient.SPOKEN
    private var sidecar = Defaults.SIDECAR
    private var cosyModel = Defaults.COSY_MODEL
    private var image = Defaults.IMAGE_MODEL
    private val vad = mutableMapOf<String, String?>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, note())
        if (intent?.action == ACTION_STOP) {
            stopMic()
            return START_STICKY
        }
        if (intent?.action == ACTION_START) {
            intent.getStringExtra("host")?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }?.let {
                sttHost = it
                llmHost = it
                ttsHost = it
                imageHost = it
            }
            intent.getStringExtra("stt_host")?.let { sttHost = it.trimEnd('/') }
            intent.getStringExtra("llm_host")?.let { llmHost = it.trimEnd('/') }
            intent.getStringExtra("tts_host")?.let { ttsHost = it.trimEnd('/') }
            intent.getStringExtra("image_host")?.let { imageHost = it.trimEnd('/') }
            intent.getStringExtra("model")?.let { model = it }
            intent.getStringExtra("chat")?.let { chat = it }
            intent.getStringExtra("tts")?.let { tts = it }
            intent.getStringExtra("voice")?.let { voice = it }
            intent.getStringExtra("prompt")?.let { prompt = it }
            intent.getStringExtra("sidecar")?.let {
                sidecar = com.geeui.voiceemo.HostConfig.orDefault(it, Defaults.SIDECAR)
            }
            intent.getStringExtra("cosy_model")?.let { cosyModel = it }
            intent.getStringExtra("image")?.let { if (it.isNotBlank()) image = it }
            for (key in VAD_KEYS) intent.getStringExtra(key)?.let { vad[key] = it }
            loop?.stop()
            loop = VoiceLoop(
                AidlBus(this), llmHost, cacheDir, VadConfig.from(vad).toVad(), model, chat, tts, voice, prompt, this,
                sidecar, cosyModel, image, sttHost, ttsHost, imageHost,
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
