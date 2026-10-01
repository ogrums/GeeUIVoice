package com.geeui.voice.engine

/**
 * Splits a token stream into clauses so TTS can start before the LLM finishes.
 * Flushes on . ! ? or a newline, and on [finish] for the tail.
 */
class SentenceSplitter {
    private val buf = StringBuilder()

    fun push(delta: String): List<String> {
        buf.append(delta)
        val out = ArrayList<String>()
        while (true) {
            val cut = cutAt()
            if (cut < 0) break
            val piece = buf.substring(0, cut + 1).trim()
            buf.delete(0, cut + 1)
            if (piece.isNotEmpty()) out += piece
        }
        return out
    }

    fun finish(): String {
        val tail = buf.toString().trim()
        buf.clear()
        return tail
    }

    private fun cutAt(): Int {
        for (i in buf.indices) {
            val c = buf[i]
            if (c == '.' || c == '!' || c == '?' || c == '\n') return i
        }
        return -1
    }
}
