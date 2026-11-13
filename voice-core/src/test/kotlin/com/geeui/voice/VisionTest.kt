package com.geeui.voice

import com.geeui.voice.bus.RecordingBus
import com.geeui.voice.engine.Chat
import com.geeui.voice.engine.EchoTts
import com.geeui.voice.engine.LemonadeClient
import com.geeui.voice.engine.pickJpegSize
import com.geeui.voice.session.VisionIntent
import com.geeui.voice.session.VoiceSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VisionTest {
    private class SeeingChat : Chat {
        val asked = mutableListOf<String>()
        val images = mutableListOf<Int>()
        override fun reply(userText: String): String {
            asked += userText
            return "[neutral] Je vois une tasse."
        }
        override fun attachImage(jpeg: ByteArray) {
            images += jpeg.size
        }
    }

    @Test
    fun intentPhrases() {
        assertTrue(VisionIntent.wants("Qu’est-ce que tu vois ?"))
        assertTrue(VisionIntent.wants("RUX, lis ce papier s'il te plaît"))
        assertTrue(VisionIntent.wants("regarde ça"))
        assertTrue(VisionIntent.wants("c'est quoi ça ?"))
        assertFalse(VisionIntent.wants("quelle heure est-il"))
        assertFalse(VisionIntent.wants("prends une photo"))
    }

    @Test
    fun photoOnlyWhenAsked() {
        var shots = 0
        val chat = SeeingChat()
        val session = VoiceSession(RecordingBus(), EchoTts(), chat, pause = {}, camera = { shots++; ByteArray(1200) })
        session.onUserText("bonjour")
        assertEquals(0, shots)
        session.onUserText("qu'est-ce que tu vois ?")
        assertEquals(1, shots)
        assertEquals(listOf(1200), chat.images)
        assertEquals("qu'est-ce que tu vois ?", chat.asked.last())
    }

    @Test
    fun cameraFailureTellsTheModel() {
        val chat = SeeingChat()
        val session = VoiceSession(RecordingBus(), EchoTts(), chat, pause = {}, camera = { error("busy") })
        session.onUserText("lis ça")
        assertEquals(emptyList(), chat.images)
        assertEquals(VisionIntent.BLIND + "lis ça", chat.asked.last())
    }

    @Test
    fun noCameraNoChange() {
        val chat = SeeingChat()
        VoiceSession(RecordingBus(), EchoTts(), chat, pause = {}).onUserText("qu'est-ce que tu vois ?")
        assertEquals("qu'est-ce que tu vois ?", chat.asked.last())
    }

    @Test
    fun openAiImageContent() {
        val raw = LemonadeClient("http://x").userContent("lis ça", byteArrayOf(-1, -40, -1))
        assertEquals(
            """[{"type":"text","text":"lis ça"},{"type":"image_url","image_url":{"url":"data:image/jpeg;base64,/9j/"}}]""",
            raw,
        )
    }

    @Test
    fun jpegSizeUnderMaxWidth() {
        assertEquals(1280 to 960, pickJpegSize(listOf(2592 to 1944, 1280 to 960, 640 to 480), 1280))
        assertEquals(2592 to 1944, pickJpegSize(listOf(2592 to 1944), 1280))
        assertEquals(null, pickJpegSize(emptyList(), 1280))
    }
}
