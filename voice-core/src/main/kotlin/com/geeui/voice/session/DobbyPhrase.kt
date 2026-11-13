package com.geeui.voice.session

/**
 * Closed-grammar phrases for Vosk small FR 0.22, tuned on Phonon « Hey Dobby »
 * clips (Noémie / Hélène, 13 Nov 2026). « dobi » is not in the lexicon.
 * Bare « dobby » is accepted only with a high confidence, to limit false wakes.
 */
object DobbyPhrase {
    /** JSON array passed to the Vosk [KaldiRecognizer] grammar constructor. */
    const val GRAMMAR =
        """["hey dobby", "hé dobby", "et dobby", "salut dobby", "dobby", "[unk]"]"""

    val PHRASES: Set<String> = setOf(
        "hey dobby",
        "hé dobby",
        "et dobby",
        "salut dobby",
        "dobby",
    )

    /** Minimum confidence on the word « dobby » for a two-word phrase. */
    const val MIN_DOBBY_CONF = 0.55f

    /** Bare « dobby » needs a higher bar: it is shorter and easier to invent. */
    const val MIN_BARE_CONF = 0.85f

    data class Verdict(val accepted: Boolean, val phrase: String, val reason: String)

    fun normalize(raw: String): String =
        raw.lowercase()
            .replace('\u00c9', 'é')
            .replace('\u00e9', 'é')
            .replace(Regex("[^a-zàâçéèêëîïôùûüÿñæœ\\[\\] ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    /**
     * [dobbyConf] is the confidence of the word « dobby » when Vosk reports
     * word-level scores. [minWordConf] is only used if [dobbyConf] is missing.
     */
    fun consider(
        text: String,
        dobbyConf: Float? = null,
        minWordConf: Float? = null,
    ): Verdict {
        val phrase = normalize(text)
        if (phrase.isEmpty() || phrase == "[unk]") {
            return Verdict(false, phrase, "unk")
        }
        if (phrase !in PHRASES) {
            return Verdict(false, phrase, "not-phrase")
        }
        val conf = dobbyConf ?: minWordConf
        val need = if (phrase == "dobby") MIN_BARE_CONF else MIN_DOBBY_CONF
        if (conf != null && conf < need) {
            return Verdict(false, phrase, "low-conf")
        }
        return Verdict(true, phrase, "accepted")
    }
}
