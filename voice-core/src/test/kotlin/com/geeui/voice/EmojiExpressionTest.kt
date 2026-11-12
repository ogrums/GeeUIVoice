package com.geeui.voice

import com.geeui.voice.bus.RecordingBus
import com.geeui.voice.engine.Chat
import com.geeui.voice.engine.EchoTts
import com.geeui.voice.engine.StreamingChat
import com.geeui.voice.session.VoiceSession
import com.geeui.voiceemo.EmojiEmotion
import com.geeui.voiceemo.Emotion
import com.geeui.voiceemo.EmotionFusion
import com.geeui.voiceemo.Mood
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EmojiExpressionTest {
    /** Streams [pieces] like SSE deltas; a pair of surrogates may be split across two of them. */
    private class Pieces(private vararg val pieces: String) : Chat, StreamingChat {
        override fun reply(userText: String) = pieces.joinToString("")
        override fun stream(userText: String, onDelta: (String) -> Unit) = pieces.forEach(onDelta)
    }

    private fun run(chat: Chat): Pair<String, String> {
        val tts = EchoTts()
        var face = ""
        val session = VoiceSession(
            RecordingBus(), tts, chat,
            mood = Mood(now = 0),
            onEmotion = { face = it.pose.faceId },
            now = { 1_000L },
            fusion = EmotionFusion(clock = { 1_000L }),
            pause = {},
        )
        session.onUserText("bonjour")
        return face to (tts.spoken.singleOrNull() ?: "fr:")
    }

    @Test
    fun mapsAndStrips() {
        assertEquals(Emotion.HAPPY, EmojiEmotion.of("Salut 😊"))
        assertEquals(Emotion.SAD, EmojiEmotion.of("Oh 😢 dommage"))
        assertEquals(Emotion.SURPRISE, EmojiEmotion.of("Waouh 😲"))
        assertEquals(Emotion.ANGRY, EmojiEmotion.of("Grr 😡"))
        assertEquals(Emotion.FEAR, EmojiEmotion.of("Au secours 😱"))
        assertEquals(Emotion.HAPPY, EmojiEmotion.of("Je t'aime ❤️"))
        assertNull(EmojiEmotion.of("Il fait 12 °C à Paris."))
        assertEquals("Salut ! À 12 °C, c'est bien.", EmojiEmotion.strip("Salut ! 👋🏽 À 12 °C, c'est bien. 😊❤️👨‍👩‍👧"))
    }

    @Test
    fun trailingEmojiBecomesTheHappyFaceAndIsNotSpoken() {
        val (face, said) = run(Pieces("Bonjour", " Olivier !", " \uD83D", "\uDE0A"))
        assertEquals("h0020", face)
        assertEquals("fr:Bonjour Olivier !", said)
    }

    @Test
    fun leadingTagWinsOverEmoji() {
        val (face, said) = run(Pieces("[sad] Oh non", " 😊"))
        assertEquals("fr:Oh non", said)
        assertEquals("h0119", face)
    }

    @Test
    fun happyTagPlusSmileEmojiShowsTheHappyFace() {
        // Regression: a leading [happy] used to skip the emoji cue, and the tag
        // alone stayed under the old fusion threshold, so the face stayed h0059.
        val (face, said) = run(Pieces("[happy] ", "\uD83D\uDE04"))
        assertEquals("h0020", face)
        assertEquals("fr:", said) // only the emoji, stripped
    }

    @Test
    fun disgustEmojiMaps() {
        assertEquals(Emotion.DISGUST, EmojiEmotion.of("Beurk \uD83E\uDD22"))
    }

    @Test
    fun noEmojiNoTagStaysNeutral() {
        val (face, said) = run(Pieces("Il fait beau."))
        assertEquals("fr:Il fait beau.", said)
        assertEquals(com.geeui.voiceemo.SdkMap.pose(Emotion.NEUTRAL).faceId, face)
    }

    @Test
    fun nuancedEmojiPicksItsOwnFace() {
        assertEquals(com.geeui.voiceemo.EmojiCue(Emotion.HAPPY, "h0027"), EmojiEmotion.cue("Merci 🥰"))
        assertEquals(com.geeui.voiceemo.EmojiCue(Emotion.HAPPY, "h0006"), EmojiEmotion.cue("Trop drôle 😂"))
        assertEquals(com.geeui.voiceemo.EmojiCue(Emotion.HAPPY, null), EmojiEmotion.cue("Salut 😊"))
        assertEquals("h0027", run(Pieces("Je t'adore", " 🥰")).first)
        assertEquals("h0006", run(Pieces("Haha", " 😂")).first)
        assertEquals("h0036", EmojiEmotion.cue("Coucou \uD83D\uDE09")?.face)
        val (face, said) = run(Pieces("Hmm, bonne question", " 🤔"))
        assertEquals("h0181", face)
        assertEquals("fr:Hmm, bonne question", said)
    }

    @Test
    fun nuanceTagPicksItsFace() {
        val (face, said) = run(Pieces("[love] Moi aussi"))
        assertEquals("h0027", face)
        assertEquals("fr:Moi aussi", said)
        assertEquals("h0181", run(Pieces("[thinking] Voyons")).first)
    }
}
