package com.geeui.voice

import com.geeui.voice.bus.IDLE_FACE
import com.geeui.voice.bus.RecordingBus
import com.geeui.voice.bus.RestFace
import com.geeui.voice.bus.SPEAK_FACE
import com.geeui.voice.bus.dialogueFace
import com.geeui.voice.bus.standAtAttention
import com.geeui.voice.session.InitiativePoller
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RestFaceTest {
    @AfterTest
    fun reset() {
        RestFace.current = IDLE_FACE
    }

    @Test
    fun moodLabelsMapToFaces() {
        assertEquals(IDLE_FACE, RestFace.forMood("calme"))
        assertEquals("h0042", RestFace.forMood("ennuye"))
        assertEquals("h0039", RestFace.forMood(" Fatigue "))
        assertEquals(IDLE_FACE, RestFace.forMood("inconnu"))
        assertEquals(7, RestFace.BY_MOOD.size)
    }

    @Test
    fun standAtAttentionUsesTheMoodFace() {
        RestFace.current = "h0119"
        val bus = RecordingBus()
        bus.standAtAttention()
        assertEquals("face h0119", bus.events.first())
    }

    @Test
    fun moodFaceIsRestSoTheMouthStillShowsWhileSpeaking() {
        RestFace.current = "h0063"
        assertEquals(SPEAK_FACE, dialogueFace("talk", true, "h0063"))
        assertEquals("h0001", dialogueFace("talk", true, "h0001"))
    }

    @Test
    fun pollerReadsMoodAndIgnoresItAsAnItem() {
        val raw = """{"items": [], "mood": {"label": "boudeur", "content": -0.4, "energy": 0.6}}"""
        assertEquals("boudeur", InitiativePoller.parseMood(raw))
        assertEquals(emptyList(), InitiativePoller.parse(raw))
        assertNull(InitiativePoller.parseMood("""{"items": []}"""))
        val moods = mutableListOf<String>()
        val said = mutableListOf<String>()
        val poller = InitiativePoller({ raw }, { _, _ -> }, { true }, { said += it }, onMood = { moods += it })
        assertNull(poller.poll())
        assertEquals(listOf("boudeur"), moods)
        assertEquals(emptyList(), said)
    }
}
