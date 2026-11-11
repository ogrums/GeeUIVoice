package com.geeui.voice

import com.geeui.voice.bus.RecordingBus
import com.geeui.voice.engine.FixedChat
import com.geeui.voice.engine.TextToSpeech
import com.geeui.voice.session.InitiativePoller
import com.geeui.voice.session.VoiceSession
import com.geeui.voiceemo.Mood
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InitiativeTest {
    private class AudibleTts : TextToSpeech {
        val spoken = mutableListOf<String>()
        private var audible: (() -> Unit)? = null
        override fun whenAudible(action: (() -> Unit)?) {
            audible = action
        }
        override fun speak(text: String, language: String) {
            spoken += text
            audible?.invoke()
            audible = null
        }
        override fun stop() = Unit
    }

    private val pending = """{"items": [{"id": "a1b2", "kind": "followup", "person": "paul",
        "text": "[happy] Salut Paul ! Ton entretien, \u00e7a s'est pass\u00e9 comment ?",
        "expires_at": "2026-11-12T12:00:00+01:00"}, {"id": "c3", "kind": "reminder", "text": "[neutral] Rappel"}]}"""

    @Test
    fun parsesItemsInOrder() {
        val items = InitiativePoller.parse(pending)
        assertEquals(listOf("a1b2", "c3"), items.map { it.id })
        assertEquals("followup", items[0].kind)
        assertEquals("[happy] Salut Paul ! Ton entretien, ça s'est passé comment ?", items[0].text)
        assertEquals(emptyList(), InitiativePoller.parse("""{"items": []}"""))
        assertEquals(emptyList(), InitiativePoller.parse("not json"))
    }

    @Test
    fun saysFirstItemThenAcksSpoken() {
        val said = mutableListOf<String>()
        val acks = mutableListOf<String>()
        val poller = InitiativePoller({ pending }, { id, s -> acks += "$id:$s" }, { true }, { said += it })
        assertEquals("a1b2", poller.poll()?.id)
        assertEquals(1, said.size)
        assertEquals(listOf("a1b2:spoken"), acks)
    }

    @Test
    fun busyMeansNoFetchAndBusyAfterFetchMeansSkipped() {
        var fetched = 0
        val acks = mutableListOf<String>()
        assertNull(InitiativePoller({ fetched++; pending }, { _, _ -> }, { false }, {}).poll())
        assertEquals(0, fetched)
        var calls = 0
        val poller = InitiativePoller({ pending }, { id, s -> acks += "$id:$s" }, { calls++ == 0 }, { error("not idle") })
        assertNull(poller.poll())
        assertEquals(listOf("a1b2:skipped"), acks)
    }

    @Test
    fun networkErrorIsSilent() {
        assertNull(InitiativePoller({ throw java.io.IOException("down") }, { _, _ -> }, { true }, {}).poll())
        assertNull(InitiativePoller({ null }, { _, _ -> }, { true }, {}).poll())
    }

    @Test
    fun sessionSaysWithFaceAndWithoutLlm() {
        val bus = RecordingBus()
        val tts = AudibleTts()
        val session = VoiceSession(bus, tts, FixedChat(), mood = Mood(now = 0), pause = {})
        val said = session.say("[happy] Bonjour Paul ! [rappel:2026-11-10T19:30|linge]")
        assertEquals("Bonjour Paul !", said)
        assertEquals(listOf("Bonjour Paul !"), tts.spoken)
        assertTrue(bus.events.contains("face h0006"), bus.events.toString())
    }

    @Test
    fun reminderTagIsNeverSpokenInNormalTurns() {
        val tts = AudibleTts()
        val chat = object : com.geeui.voice.engine.Chat {
            override fun reply(userText: String) = "[happy] D'accord, je te le rappellerai. [rappel:+60|sortir le linge]"
        }
        VoiceSession(RecordingBus(), tts, chat, pause = {}).onUserText("rappelle-moi de sortir le linge dans une heure")
        assertEquals(listOf("D'accord, je te le rappellerai."), tts.spoken)
    }
}
