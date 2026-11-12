package com.geeui.voice.session

import com.geeui.voice.engine.jsonUnquote

/** One sentence the sidecar wants the robot to say first (GET /v1/robot/pending). */
data class Initiative(val id: String, val kind: String, val text: String)

/**
 * Asks the sidecar, while the robot is idle, whether it has something to say:
 * GET /v1/robot/pending, say the first item, then POST /v1/robot/ack.
 * An item that cannot be said now is acked "skipped" so the sidecar can drop or retry it.
 * The sidecar owns every rule (quiet hours, limits, "ne me dérange pas"); the app only says.
 */
class InitiativePoller(
    private val fetch: () -> String?,
    private val ack: (id: String, status: String) -> Unit,
    private val idle: () -> Boolean,
    private val say: (String) -> Unit,
    private val onMood: (String) -> Unit = {},
) {
    /** Returns the item spoken, or null. Never throws: a network error just means nothing to say. */
    fun poll(): Initiative? {
        if (!idle()) return null
        val raw = try {
            fetch()
        } catch (_: Exception) {
            null
        } ?: return null
        parseMood(raw)?.let { label ->
            try {
                onMood(label)
            } catch (_: Exception) {
            }
        }
        val item = parse(raw).firstOrNull() ?: return null
        if (!idle()) {
            safeAck(item.id, "skipped")
            return null
        }
        say(item.text)
        safeAck(item.id, "spoken")
        return item
    }

    private fun safeAck(id: String, status: String) {
        try {
            ack(id, status)
        } catch (_: Exception) {
        }
    }

    companion object {
        /** Reads {"items":[{"id":…,"kind":…,"text":…}, …]} without a JSON library. */
        fun parse(raw: String): List<Initiative> {
            val start = raw.indexOf("\"items\"")
            if (start < 0) return emptyList()
            val out = mutableListOf<Initiative>()
            var i = raw.indexOf('[', start)
            if (i < 0) return emptyList()
            while (true) {
                val open = raw.indexOf('{', i)
                if (open < 0) break
                // Stop at the end of the items array: "mood" and other fields come after it.
                if (raw.substring(i, open).contains(']')) break
                val close = objectEnd(raw, open)
                if (close < 0) break
                val obj = raw.substring(open, close + 1)
                val id = field(obj, "id")
                val text = field(obj, "text")
                if (!id.isNullOrBlank() && !text.isNullOrBlank()) {
                    out += Initiative(id, field(obj, "kind") ?: "", text)
                }
                i = close + 1
            }
            return out
        }

        /** The robot mood label ({"mood":{"label":"boudeur",…}}), or null when the sidecar sends none. */
        fun parseMood(raw: String): String? {
            val at = raw.indexOf("\"mood\"")
            if (at < 0) return null
            val open = raw.indexOf('{', at)
            if (open < 0) return null
            val close = objectEnd(raw, open)
            if (close < 0) return null
            return field(raw.substring(open, close + 1), "label")?.takeIf { it.isNotBlank() }
        }

        private fun field(obj: String, key: String): String? {
            val marker = "\"$key\""
            var at = obj.indexOf(marker)
            if (at < 0) return null
            at = obj.indexOf(':', at + marker.length)
            if (at < 0) return null
            val rest = obj.substring(at + 1).trimStart()
            return if (rest.startsWith("\"")) jsonUnquote(rest) else null
        }

        /** Index of the brace closing the object at [open], skipping strings. */
        private fun objectEnd(s: String, open: Int): Int {
            var depth = 0
            var inString = false
            var i = open
            while (i < s.length) {
                val c = s[i]
                if (inString) {
                    if (c == '\\') i++ else if (c == '"') inString = false
                } else when (c) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> if (--depth == 0) return i
                }
                i++
            }
            return -1
        }
    }
}
