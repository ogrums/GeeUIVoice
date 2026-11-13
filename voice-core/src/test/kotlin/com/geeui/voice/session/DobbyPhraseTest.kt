package com.geeui.voice.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DobbyPhraseTest {
    @Test
    fun acceptsTunedPhrases() {
        for (phrase in listOf("hey dobby", "Hé Dobby", "ET DOBBY", "salut dobby")) {
            val v = DobbyPhrase.consider(phrase, dobbyConf = 1.0f)
            assertTrue(v.accepted, phrase)
            assertEquals("accepted", v.reason)
        }
    }

    @Test
    fun acceptsBareDobbyOnlyWhenConfident() {
        assertTrue(DobbyPhrase.consider("dobby", dobbyConf = 0.97f).accepted)
        val low = DobbyPhrase.consider("dobby", dobbyConf = 0.70f)
        assertFalse(low.accepted)
        assertEquals("low-conf", low.reason)
    }

    @Test
    fun rejectsUnknownAndPartial() {
        for (text in listOf("", "[unk]", "hey", "hé", "hey [unk]", "hé [unk]", "[unk] dobby", "bonjour", "eric")) {
            val v = DobbyPhrase.consider(text, dobbyConf = 1.0f)
            assertFalse(v.accepted, text)
        }
    }

    @Test
    fun twoWordPhraseUsesDobbyConfidence() {
        // First word (« hé ») can be weak; the word dobby is what matters.
        assertTrue(DobbyPhrase.consider("hé dobby", dobbyConf = 1.0f, minWordConf = 0.45f).accepted)
        val weakDobby = DobbyPhrase.consider("hey dobby", dobbyConf = 0.40f)
        assertFalse(weakDobby.accepted)
        assertEquals("low-conf", weakDobby.reason)
    }

    @Test
    fun normalizeCollapsesSpaceAndCase() {
        assertEquals("hey dobby", DobbyPhrase.normalize("  Hey   Dobby. "))
        assertEquals("hé dobby", DobbyPhrase.normalize("HÉ  DOBBY"))
    }
}
