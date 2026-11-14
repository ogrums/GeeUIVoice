package com.geeui.voice

import android.content.Context
import android.content.Intent
import com.geeui.voiceemo.HostConfig

/** What the settings menu edits. A saved value wins over the launch extra. */
object VoiceSettings {
    private const val FILE = "geeui_voice"
    private const val SCHEMA = "faces_schema"

    val fields = listOf(
        "lemonade" to HostConfig.LEMONADE,
        "sidecar" to HostConfig.SIDECAR,
        "stt" to HostConfig.STT,
        "chat" to HostConfig.CHAT,
        "tts" to HostConfig.KOKORO,
        "cosy" to HostConfig.COSY,
        "phonon" to "",
        "phonon_voice" to com.geeui.voice.engine.PhononTts.VOICE,
        "listen_mode" to "auto",
        "wake_word" to "both",
        "listen_face" to "h0347",
        "backend_face" to "h0242",
        "think_face" to "",
        "idle_faces" to "off",
        "idle_face_interval" to "",
        "talk_face" to "speak",
        "idle_video" to "loop",
        "dobby_gate" to "auto",
    )

    private val labels = mapOf(
        "lemonade" to "Lemonade",
        "sidecar" to "CosyVoice",
        "stt" to "STT",
        "chat" to "Chat",
        "tts" to "TTS",
        "cosy" to "Modèle Cosy",
        "phonon" to "Phonon (vide = Kokoro)",
        "phonon_voice" to "Voix Phonon",
        "listen_mode" to "Écoute (auto, wake, continuous)",
        "wake_word" to "Mot de réveil (hirux, dobby, both)",
        "listen_face" to "Visage écoute (h0347)",
        "backend_face" to "Visage backend (h0242)",
        "think_face" to "Visage réflexion (vide = backend)",
        "idle_faces" to "Visages au repos (off, ou on = alternance)",
        "idle_face_interval" to "Intervalle repos en secondes (min. 60, défaut 90)",
        "talk_face" to "Visage pendant la parole (speak = bouche h0292, emotion)",
        "idle_video" to "Vidéo au repos (loop, once, ou secondes entre deux lectures)",
        "dobby_gate" to "Seuil Hey Dobby (auto, ou RMS fixe ex. 50)",
    )

    private val extras = mapOf(
        "lemonade" to "host",
        "sidecar" to "sidecar",
        "stt" to "model",
        "chat" to "chat",
        "tts" to "tts",
        "cosy" to "cosy_model",
        "phonon" to "phonon",
        "phonon_voice" to "phonon_voice",
        "listen_mode" to "listen_mode",
        "wake_word" to "wake_word",
        "listen_face" to "listen_face",
        "backend_face" to "backend_face",
        "think_face" to "think_face",
        "idle_faces" to "idle_faces",
        "idle_face_interval" to "idle_face_interval",
        "talk_face" to "talk_face",
        "idle_video" to "idle_video",
        "dobby_gate" to "dobby_gate",
    )

    fun label(key: String): String = labels.getValue(key)

    fun extra(key: String): String = extras.getValue(key)

    /** One-time: previous packaged defaults (h0180 / h0181) become the new ones. */
    internal fun migrate(prefs: android.content.SharedPreferences) {
        if (prefs.getInt(SCHEMA, 0) >= 2) return
        val edit = prefs.edit()
        if (prefs.getString("listen_face", null) == "h0180") edit.putString("listen_face", "h0347")
        if (prefs.getString("think_face", null) == "h0181") edit.remove("think_face")
        edit.putInt(SCHEMA, 2)
        edit.apply()
    }

    fun effective(context: Context, intent: Intent): Map<String, String> {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        migrate(prefs)
        return fields.associate { (key, fallback) ->
            val saved = prefs.getString(key, null)?.trim()?.takeIf { it.isNotEmpty() }
            val fromIntent = intent.getStringExtra(extra(key))?.trim()?.takeIf { it.isNotEmpty() }
            val raw = saved ?: fromIntent ?: fallback
            key to if (key == "lemonade" || key == "sidecar" || key == "phonon") raw.trimEnd('/') else raw
        }
    }

    fun save(context: Context, values: Map<String, String>) {
        val edit = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
        for ((key, _) in fields) edit.putString(key, values[key]?.trim().orEmpty())
        edit.apply()
    }
}
