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

    @Test
    fun disgust_is_a_known_tag() {
        assertEquals(
            EmotionText(Emotion.DISGUST, " Beurk."),
            EmotionTagParser().consume("[disgust] Beurk."),
        )
    }

    @Test
    fun bare_leading_emotion_word_is_stripped() {
        // The Gemma slip that made the robot say "neutral" out loud.
        assertEquals(
            EmotionText(Emotion.NEUTRAL, "Je ne peux pas entendre de musique."),
            EmotionTagParser().consume("neutral Je ne peux pas entendre de musique."),
        )
        assertEquals(
            EmotionText(Emotion.HAPPY, "Bonjour"),
            EmotionTagParser().consume("Happy: Bonjour"),
        )
        assertEquals(
            EmotionText(Emotion.SAD, "je suis là"),
            EmotionTagParser().consume("sad, je suis là"),
        )
        assertEquals(
            EmotionText(Emotion.ANGRY, "Non."),
            EmotionTagParser().consume("ANGRY  Non."),
        )
        assertEquals(
            EmotionText(Emotion.DISGUST, "Beurk."),
            EmotionTagParser().consume("disgust Beurk."),
        )
    }

    @Test
    fun wrapped_leading_emotion_is_stripped() {
        assertEquals(
            EmotionText(Emotion.NEUTRAL, "quelque chose"),
            EmotionTagParser().consume("(neutral) quelque chose"),
        )
        assertEquals(
            EmotionText(Emotion.ANGRY, "Non."),
            EmotionTagParser().consume("*angry* Non."),
        )
        assertEquals(
            EmotionText(Emotion.HAPPY, "Salut"),
            EmotionTagParser().consume("*happy*: Salut"),
        )
    }

    @Test
    fun bare_word_stays_when_it_is_part_of_a_real_sentence() {
        // Adverb, not the tag. First token is not exactly an emotion word.
        assertEquals(
            EmotionText(Emotion.NEUTRAL, "Neutralement, je vais bien."),
            EmotionTagParser().consume("Neutralement, je vais bien."),
        )
        // A lone word has no following text: keep it.
        val alone = EmotionTagParser()
        assertEquals(EmotionText(Emotion.NEUTRAL, ""), alone.consume("neutral"))
        assertEquals(EmotionText(Emotion.NEUTRAL, "neutral"), alone.finish())

        val colonOnly = EmotionTagParser()
        assertEquals(EmotionText(Emotion.NEUTRAL, ""), colonOnly.consume("happy:"))
        assertEquals(EmotionText(Emotion.NEUTRAL, "happy:"), colonOnly.finish())

        // Period is not a separator we accept, so a real sentence stays intact.
        assertEquals(
            EmotionText(Emotion.NEUTRAL, "Neutral. D'accord."),
            EmotionTagParser().consume("Neutral. D'accord."),
        )
    }

    @Test
    fun bare_word_is_withheld_until_it_can_be_decided() {
        val parser = EmotionTagParser()
        assertEquals(EmotionText(Emotion.NEUTRAL, ""), parser.consume("neu"))
        assertEquals(EmotionText(Emotion.NEUTRAL, ""), parser.consume("tral"))
        assertEquals(
            EmotionText(Emotion.NEUTRAL, "Je ne peux pas entendre de musique."),
            parser.consume(" Je ne peux pas entendre de musique."),
        )

        val adverb = EmotionTagParser()
        assertEquals(EmotionText(Emotion.NEUTRAL, ""), adverb.consume("Neutral"))
        // One extra letter rules out the tag; the whole adverb is flushed.
        assertEquals(
            EmotionText(Emotion.NEUTRAL, "Neutralement, je vais bien."),
            adverb.consume("ement, je vais bien."),
        )
    }
}
