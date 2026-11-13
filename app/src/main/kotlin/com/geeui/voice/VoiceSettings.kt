package com.geeui.voice

import android.content.Context
import android.content.Intent
import com.geeui.voiceemo.HostConfig

/** What the settings menu edits. A saved value wins over the launch extra. */
object VoiceSettings {
    private const val FILE = "geeui_voice"

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
    )

    fun label(key: String): String = labels.getValue(key)

    fun extra(key: String): String = extras.getValue(key)

    fun effective(context: Context, intent: Intent): Map<String, String> {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
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
