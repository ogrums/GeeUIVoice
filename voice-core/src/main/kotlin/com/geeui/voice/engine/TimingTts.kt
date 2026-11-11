package com.geeui.voice.engine

/**
 * Wraps a [TextToSpeech] to record when [speak] starts and when the clip becomes audible.
 * If the inner player never fires [whenAudible] (tests, empty clip), the time is taken at
 * the end of [speak].
 */
class TimingTts(
    private val inner: TextToSpeech,
    private val now: () -> Long = { System.currentTimeMillis() },
) : TextToSpeech {
    var speakAt: Long? = null
        private set
    var firstAudibleAt: Long? = null
        private set

    fun reset() {
        speakAt = null
        firstAudibleAt = null
    }

    /** Milliseconds from [speak] to the first audible sample. */
    fun firstAudioMs(): Long? {
        val start = speakAt ?: return null
        val audible = firstAudibleAt ?: return null
        return (audible - start).coerceAtLeast(0)
    }

    override fun whenAudible(action: (() -> Unit)?) {
        inner.whenAudible {
            if (firstAudibleAt == null) firstAudibleAt = now()
            action?.invoke()
        }
    }

    override fun speak(text: String, language: String) {
        speakAt = now()
        inner.speak(text, language)
        if (firstAudibleAt == null) firstAudibleAt = now()
    }

    override fun stop() = inner.stop()
}
