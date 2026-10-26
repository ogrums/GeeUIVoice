package com.geeui.voice

/**
 * Valeurs compilées dans l'APK. Le menu (lancement `--es mode debug`) peut les remplacer.
 * Chaque URL porte son hôte et son port : STT, LLM, TTS et image ne partagent rien.
 */
object Defaults {
    const val STT_HOST = "http://nimbus:13305/api/v1"
    const val STT_MODEL = "Whisper-Small"
    const val LLM_HOST = "http://nimbus:13305/api/v1"
    const val LLM_MODEL = "gemma4e-flash-e2b-FLM"
    const val TTS_HOST = "http://nimbus:13305/api/v1"
    const val TTS_MODEL = "kokoro-v1"
    const val IMAGE_HOST = "http://nimbus:13305/api/v1"
    const val IMAGE_MODEL = "SD-Turbo"
    const val VOICE = "ff_siwis"
    const val SIDECAR = "http://nimbus:13306"
    const val COSY_MODEL = "Fun-CosyVoice3-0.5B-2512"
}
