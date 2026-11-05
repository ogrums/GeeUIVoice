package com.geeui.voiceemo

import kotlin.test.Test
import kotlin.test.assertEquals

class EmotionTagParserTest {
    @Test
    fun split_tag_emits_emotion_and_clean_text() {
        val parser = EmotionTagParser()
        assertEquals(EmotionText(Emotion.NEUTRAL, ""), parser.consume("[s"))
        assertEquals(EmotionText(Emotion.SAD, "Bonjour."), parser.consume("ad]Bonjour."))
        assertEquals(EmotionText(Emotion.NEUTRAL, " Suite"), parser.consume(" Suite"))
    }

    @Test
    fun missing_or_unknown_tag_keeps_text_unchanged() {
        assertEquals(EmotionText(Emotion.NEUTRAL, "Bonjour"), EmotionTagParser().consume("Bonjour"))
        assertEquals(EmotionText(Emotion.NEUTRAL, "[bored] Salut"), EmotionTagParser().consume("[bored] Salut"))
    }

    @Test
    fun unfinished_and_long_candidates_are_flushed() {
        val parser = EmotionTagParser()
        assertEquals("", parser.consume("[happy").text)
        assertEquals("[happy", parser.finish().text)

        val long = EmotionTagParser()
        val text = "[" + "x".repeat(20)
        assertEquals(EmotionText(Emotion.NEUTRAL, text), long.consume(text))
    }

    @Test
    fun french_prompt_names_every_supported_tag_once() {
        Emotion.entries.forEach { emotion ->
            assertEquals(1, Regex("\\[${emotion.name.lowercase()}]").findAll(FRENCH_EMOTION_SYSTEM_PROMPT).count())
        }
    }
}
