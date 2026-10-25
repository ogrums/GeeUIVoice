package com.geeui.voice

import android.content.Context
import android.content.Intent
import com.geeui.voiceemo.HostConfig

/** What the settings menu edits. A launch extra wins. The saved menu value is used when that extra is absent. */
object VoiceSettings {
    private const val FILE = "geeui_voice"

    val fields = listOf(
        "lemonade" to HostConfig.LEMONADE,
        "sidecar" to HostConfig.SIDECAR,
        "stt" to HostConfig.STT,
        "chat" to HostConfig.CHAT,
        "tts" to HostConfig.KOKORO,
        "cosy" to HostConfig.COSY,
        "image" to HostConfig.IMAGE,
    )

    private val labels = mapOf(
        "lemonade" to "Lemonade",
        "sidecar" to "CosyVoice",
        "stt" to "STT",
        "chat" to "Chat",
        "tts" to "TTS",
        "cosy" to "Modèle Cosy",
        "image" to "Image",
    )

    private val extras = mapOf(
        "lemonade" to "host",
        "sidecar" to "sidecar",
        "stt" to "model",
        "chat" to "chat",
        "tts" to "tts",
        "cosy" to "cosy_model",
        "image" to "image",
    )

    fun label(key: String): String = labels.getValue(key)

    fun extra(key: String): String = extras.getValue(key)

    fun effective(context: Context, intent: Intent): Map<String, String> {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return fields.associate { (key, fallback) ->
            val saved = prefs.getString(key, null)?.trim()?.takeIf { it.isNotEmpty() }
            val fromIntent = intent.getStringExtra(extra(key))?.trim()?.takeIf { it.isNotEmpty() }
            val raw = fromIntent ?: saved ?: fallback
            key to if (key == "lemonade" || key == "sidecar") raw.trimEnd('/') else raw
        }
    }

    fun save(context: Context, values: Map<String, String>) {
        val edit = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
        for ((key, _) in fields) edit.putString(key, values[key]?.trim().orEmpty())
        edit.apply()
    }
}
