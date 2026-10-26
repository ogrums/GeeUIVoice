package com.geeui.voice

import android.content.Context
import android.content.Intent

/** Menu fields. Defaults come from [Defaults]. A launch extra is saved once, then the menu wins. */
object VoiceSettings {
    private const val FILE = "geeui_voice"

    val fields = listOf(
        "stt_host" to Defaults.STT_HOST,
        "stt" to Defaults.STT_MODEL,
        "llm_host" to Defaults.LLM_HOST,
        "chat" to Defaults.LLM_MODEL,
        "tts_host" to Defaults.TTS_HOST,
        "tts" to Defaults.TTS_MODEL,
        "image_host" to Defaults.IMAGE_HOST,
        "image" to Defaults.IMAGE_MODEL,
        "voice" to Defaults.VOICE,
        "sidecar" to Defaults.SIDECAR,
        "cosy" to Defaults.COSY_MODEL,
    )

    private val labels = mapOf(
        "stt_host" to "STT hôte",
        "stt" to "STT modèle",
        "llm_host" to "LLM hôte",
        "chat" to "LLM modèle",
        "tts_host" to "TTS hôte",
        "tts" to "TTS modèle",
        "image_host" to "Image hôte",
        "image" to "Image modèle",
        "voice" to "Voix Kokoro",
        "sidecar" to "CosyVoice hôte",
        "cosy" to "CosyVoice modèle",
    )

    private val extras = mapOf(
        "stt_host" to "stt_host",
        "stt" to "model",
        "llm_host" to "llm_host",
        "chat" to "chat",
        "tts_host" to "tts_host",
        "tts" to "tts",
        "image_host" to "image_host",
        "image" to "image",
        "voice" to "voice",
        "sidecar" to "sidecar",
        "cosy" to "cosy_model",
    )

    private val urls = setOf("stt_host", "llm_host", "tts_host", "image_host", "sidecar")

    fun label(key: String): String = labels.getValue(key)

    fun extra(key: String): String = extras.getValue(key)

    /** Copy a fresh launch extra into the saved settings, then drop it so it cannot override a later edit. */
    fun absorb(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val edit = prefs.edit()
        var hit = false
        val shared = intent.getStringExtra("host")?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }
        if (shared != null) {
            for (key in urls) {
                if (key == "sidecar") continue
                edit.putString(key, shared)
            }
            intent.removeExtra("host")
            hit = true
        }
        for ((key, _) in fields) {
            val name = extra(key)
            val from = intent.getStringExtra(name)?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            val value = if (key in urls) from.trimEnd('/') else from
            edit.putString(key, value)
            intent.removeExtra(name)
            hit = true
        }
        if (hit) edit.commit()
    }

    fun effective(context: Context): Map<String, String> {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return fields.associate { (key, fallback) ->
            val saved = prefs.getString(key, null)?.trim()?.takeIf { it.isNotEmpty() }
            val raw = saved ?: fallback
            key to if (key in urls) raw.trimEnd('/') else raw
        }
    }

    fun save(context: Context, values: Map<String, String>) {
        val edit = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
        for ((key, _) in fields) edit.putString(key, values[key]?.trim().orEmpty())
        edit.apply()
    }
}
