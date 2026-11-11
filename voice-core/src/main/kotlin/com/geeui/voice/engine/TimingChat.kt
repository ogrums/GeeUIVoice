package com.geeui.voice.engine

/**
 * Wraps a [Chat] to record the first token time and the raw reply (tags included).
 * VoiceSession stays unchanged: it just talks to this object.
 */
class TimingChat(
    private val inner: Chat,
    private val now: () -> Long = { System.currentTimeMillis() },
) : Chat, StreamingChat {
    var chatStartedAt: Long? = null
        private set
    var firstTokenAt: Long? = null
        private set
    private val rawBuf = StringBuilder()
    val raw: String get() = rawBuf.toString()

    fun reset() {
        chatStartedAt = null
        firstTokenAt = null
        rawBuf.clear()
    }

    /** Milliseconds from the start of the chat call to the first non-empty token. */
    fun firstTokenMs(): Long? {
        val start = chatStartedAt ?: return null
        val first = firstTokenAt ?: return null
        return (first - start).coerceAtLeast(0)
    }

    override fun reply(userText: String): String {
        chatStartedAt = now()
        val text = inner.reply(userText)
        mark(text)
        rawBuf.append(text)
        return text
    }

    override fun stream(userText: String, onDelta: (String) -> Unit) {
        chatStartedAt = now()
        if (inner is StreamingChat) {
            inner.stream(userText) { piece ->
                mark(piece)
                rawBuf.append(piece)
                onDelta(piece)
            }
        } else {
            onDelta(reply(userText))
        }
    }

    private fun mark(piece: String) {
        if (firstTokenAt == null && piece.isNotEmpty()) firstTokenAt = now()
    }
}
