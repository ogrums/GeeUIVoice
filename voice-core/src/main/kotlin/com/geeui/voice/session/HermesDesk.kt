package com.geeui.voice.session

import com.geeui.voice.engine.jsonUnquote
import java.text.Normalizer

/** HTTP answer from the sidecar: status code (0 = no answer) and body. */
data class HermesHttp(val code: Int, val body: String?)

/** Counts from GET /hermes/tasks, for the badge and the light. */
data class HermesState(val enabled: Boolean, val unread: Int, val running: Int)

/**
 * Tasks handed to Hermes Agent through the sidecar (/hermes/…).
 *
 * « Confie à Hermes de … » creates a task, « lis le résultat » reads the last one aloud
 * (a short summary made by the sidecar), « où en est Hermes » gives the progress.
 * The sidecar owns the phrase rules (POST /hermes/intent) and the task list; this class
 * only asks it when a sentence could be about Hermes, so ordinary turns cost no request.
 * Never throws: a network error gives a short spoken excuse or null.
 */
class HermesDesk(
    private val get: (path: String) -> HermesHttp,
    private val post: (path: String, json: String) -> HermesHttp,
) {
    /** Text to say when [text] is a Hermes phrase, or null to let the normal turn run. */
    fun handle(text: String): String? {
        if (!mayConcern(text)) return null
        val intent = post("/hermes/intent", "{\"text\":${quote(text)}}")
        if (intent.code !in 200..299 || intent.body == null) return null
        return when (field(intent.body, "intent")) {
            "create" -> create(field(intent.body, "prompt"))
            "read" -> read(explicit = mentionsHermes(text))
            "status" -> status()
            else -> null
        }
    }

    private fun create(prompt: String?): String {
        if (prompt.isNullOrBlank()) return ASK_WHAT
        val answer = post("/hermes/tasks", "{\"prompt\":${quote(prompt)}}")
        return when {
            answer.code in 200..299 -> CREATED
            answer.code == 503 -> DISABLED
            else -> UNREACHABLE
        }
    }

    /** A bare « lis le résultat » only speaks when there is something from Hermes to read. */
    private fun read(explicit: Boolean): String? {
        if (!explicit) {
            val state = state() ?: return null
            if (!state.enabled || state.unread == 0) return null
        }
        val answer = post("/hermes/tasks/latest/speak", "{}")
        return when {
            answer.code in 200..299 -> field(answer.body ?: "", "text")?.takeIf { it.isNotBlank() } ?: EMPTY
            answer.code == 404 -> NO_TASK
            answer.code == 503 -> DISABLED
            else -> UNREACHABLE
        }
    }

    private fun status(): String {
        val state = state() ?: return UNREACHABLE
        if (!state.enabled) return DISABLED
        return statusLine(state)
    }

    /** GET /hermes/tasks, or null when the sidecar does not answer. */
    fun state(): HermesState? {
        val answer = get("/hermes/tasks")
        if (answer.code == 503) return HermesState(false, 0, 0)
        if (answer.code !in 200..299 || answer.body == null) return null
        return HermesState(true, number(answer.body, "unread"), number(answer.body, "running"))
    }

    companion object {
        const val ASK_WHAT = "Que veux-tu confier à Hermes ? Dis par exemple : confie à Hermes de compter les fichiers du dossier demo."
        const val CREATED = "D'accord, je confie ça à Hermes. Je m'allume quand c'est prêt."
        const val DISABLED = "Hermes n'est pas branché sur mon serveur."
        const val UNREACHABLE = "Je n'arrive pas à joindre Hermes pour l'instant."
        const val NO_TASK = "Hermes n'a encore aucune tâche à me rendre."
        const val EMPTY = "Hermes a terminé, mais sans rien répondre."

        fun statusLine(state: HermesState): String = when {
            state.unread > 0 && state.running > 0 ->
                "Hermes a ${plural(state.unread, "résultat")} prêt et ${plural(state.running, "tâche")} en cours."
            state.unread > 0 -> "Hermes a ${plural(state.unread, "résultat")} prêt. Dis : lis le résultat."
            state.running > 0 -> "Hermes travaille encore sur ${plural(state.running, "tâche")}."
            else -> "Hermes n'a rien en cours."
        }

        private fun plural(n: Int, word: String): String =
            (if (n == 1) (if (word == "tâche") "une" else "un") else n.toString()) + " " + word + if (n > 1) "s" else ""

        private val HERMES = Regex("\\b(hermes|hermesse|erm[e]s|air ?mess)\\b")
        private val RESULT = Regex("\\bresultats?\\b")

        fun fold(text: String): String =
            Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
                .lowercase().replace('’', '\'')

        fun mentionsHermes(text: String): Boolean = HERMES.containsMatchIn(fold(text))

        /** Cheap local filter: only these sentences go to the sidecar. */
        fun mayConcern(text: String): Boolean {
            val t = fold(text)
            return HERMES.containsMatchIn(t) || (RESULT.containsMatchIn(t) && t.length <= 40)
        }

        fun quote(s: String): String = buildString {
            append('"')
            for (c in s) when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append(String.format("\\u%04x", c.code)) else append(c)
            }
            append('"')
        }

        /** First top-level-ish string field "key":"…" (the sidecar answers are flat). */
        fun field(raw: String, key: String): String? {
            val marker = "\"$key\""
            var at = raw.indexOf(marker)
            if (at < 0) return null
            at = raw.indexOf(':', at + marker.length)
            if (at < 0) return null
            val rest = raw.substring(at + 1).trimStart()
            return if (rest.startsWith("\"")) jsonUnquote(rest) else null
        }

        fun number(raw: String, key: String): Int =
            Regex("\"$key\"\\s*:\\s*(\\d+)").find(raw)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }
}
