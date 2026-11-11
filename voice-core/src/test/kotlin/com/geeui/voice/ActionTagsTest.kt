package com.geeui.voice

import com.geeui.voice.bus.RecordingBus
import com.geeui.voice.engine.Chat
import com.geeui.voice.engine.EchoTts
import com.geeui.voice.session.ActionTags
import com.geeui.voice.session.VoiceSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ActionTagsTest {
    private class Says(var reply: String) : Chat {
        override fun reply(userText: String) = reply
    }

    private class Clock(var t: Long = 1_000_000L)

    @Test
    fun splitRemovesKnownTagsOnly() {
        val (text, actions) = ActionTags.split("[happy] D'accord, j'avance. [move:forward]")
        assertEquals("[happy] D'accord, j'avance.", text)
        assertEquals(listOf("move"), actions.map { it.kind })
        assertTrue(actions[0].needsConfirmation)
        val (t2, a2) = ActionTags.split("Bleu ! [light:blue] et [light:pink] [shell:ls]")
        assertEquals("Bleu ! et [shell:ls]", t2) // unknown color: dropped, not spoken, not run
        assertEquals(listOf(3), a2.map { it.light })
        assertEquals(0, ActionTags.split("[light:off]").second[0].light)
    }

    @Test
    fun lightRunsAtOnceAndIsNotSpoken() {
        val bus = RecordingBus()
        val tts = EchoTts()
        VoiceSession(bus, tts, Says("Voilà, en bleu. [light:blue]"), pause = {}).onUserText("mets-toi en bleu")
        assertEquals(listOf("fr:Voilà, en bleu."), tts.spoken)
        assertTrue(bus.events.any { it.startsWith("light") && it.contains("3") }, bus.events.toString())
    }

    @Test
    fun walkAsksFirstThenYesWalks() {
        val bus = RecordingBus()
        val tts = EchoTts()
        val clock = Clock()
        val session = VoiceSession(bus, tts, Says("[happy] J'avance. [move:forward]"), pause = {}, now = { clock.t })
        session.onUserText("avance un peu s'il te plaît")
        assertEquals("fr:${VoiceSession.ASK}", tts.spoken.last())
        assertFalse(bus.events.any { it.startsWith("motion 98") })
        clock.t += 3_000
        session.onUserText("oui vas-y")
        assertTrue(bus.events.any { it.startsWith("motion 98") }, bus.events.toString())
    }

    @Test
    fun noOrTimeoutCancels() {
        val bus = RecordingBus()
        val tts = EchoTts()
        val clock = Clock()
        val chat = Says("Je rentre. [home]")
        val session = VoiceSession(bus, tts, chat, pause = {}, now = { clock.t })
        session.onUserText("rentre à la base s'il te plaît")
        session.onUserText("non attends")
        assertEquals("fr:${VoiceSession.CANCELLED}", tts.spoken.last())
        session.onUserText("rentre à la base s'il te plaît")
        clock.t += 11_000
        chat.reply = "Bonjour."
        session.onUserText("oui")
        assertFalse(bus.events.any { it.contains("自动回充") }, bus.events.toString())
    }

    @Test
    fun childCannotWalkEvenWithLocalPhrase() {
        val bus = RecordingBus()
        val tts = EchoTts()
        val allowed = setOf("volume", "light", "photo")
        val session = VoiceSession(bus, tts, Says("J'avance ! [move:forward]"), pause = {}, allowedActions = { allowed })
        session.onUserText("avance")
        assertEquals("fr:${VoiceSession.REFUSED}", tts.spoken.last())
        session.onUserText("va tout droit")
        assertEquals("fr:${VoiceSession.REFUSED}", tts.spoken.last())
        assertFalse(bus.events.any { it.startsWith("motion") })
        session.onUserText("plus fort")
        assertTrue(bus.events.any { it.contains("SetVolume") })
    }
}
