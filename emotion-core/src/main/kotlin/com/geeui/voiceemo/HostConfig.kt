package com.geeui.voiceemo

/**
 * Same names as the sidecar. Blank means the default.
 * Android has no process env: pass these as service extras, or leave them empty.
 */
data class HostConfig(
    val sidecar: String,
    val lemonadeHost: String,
    val kokoroModel: String,
    val kokoroVoiceFr: String,
    val kokoroVoiceEn: String,
    val cosyVoiceHost: String,
    val cosyVoiceModel: String,
    val audioModel: String,
    val chatModel: String,
) {
    val cosyEnabled: Boolean get() = cosyVoiceHost.isNotBlank()

    companion object {
        const val SIDECAR = "http://nimbus:13306"
        const val LEMONADE = "http://nimbus:13305/api/v1"
        const val KOKORO = "kokoro-v1"
        const val VOICE_FR = "ff_siwis"
        const val VOICE_EN = "af_heart"
        const val COSY = "Fun-CosyVoice3-0.5B-2512"
        const val AUDIO = "emotion2vec"
        const val STT = "whisper-small"
        const val CHAT = "gemma4e-flash-e2b-FLM"

        fun orDefault(raw: String?, fallback: String): String {
            val value = raw?.trim().orEmpty()
            return if (value.isEmpty()) fallback else value.trimEnd('/')
        }

        fun from(values: Map<String, String?>): HostConfig = HostConfig(
            sidecar = orDefault(values["sidecar"], SIDECAR),
            lemonadeHost = orDefault(values["lemonade"], LEMONADE),
            kokoroModel = orDefault(values["kokoro"], KOKORO),
            kokoroVoiceFr = orDefault(values["voice_fr"], VOICE_FR),
            kokoroVoiceEn = orDefault(values["voice_en"], VOICE_EN),
            cosyVoiceHost = values["cosyvoice"]?.trim()?.trimEnd('/').orEmpty(),
            cosyVoiceModel = orDefault(values["cosy_model"], COSY),
            audioModel = orDefault(values["audio_model"], AUDIO),
            chatModel = orDefault(values["chat_model"], CHAT),
        )
    }
}
