package com.geeui.voice

import com.geeui.voice.bus.DialogueFaces
import com.geeui.voice.bus.FaceBackend
import com.geeui.voice.bus.FaceDirector
import com.geeui.voice.bus.IDLE_FACE
import com.geeui.voice.bus.LISTEN_FACE
import com.geeui.voice.bus.SPEAK_FACE
import com.geeui.voice.bus.SpeechEnvelope
import com.geeui.voice.bus.THINK_FACE
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RuxFaceTest {
    @AfterTest fun reset() = DialogueFaces.reset()

    @Test fun backendParse() {
        assertEquals(FaceBackend.GEEUIFACE, FaceBackend.parse(null))
        assertEquals(FaceBackend.GEEUIFACE, FaceBackend.parse(""))
        assertEquals(FaceBackend.GEEUIFACE, FaceBackend.parse("geeuiface"))
        assertEquals(FaceBackend.RUXFACE, FaceBackend.parse(" RuxFace "))
        assertEquals(FaceBackend.RUXFACE, FaceBackend.parse("gl"))
    }

    @Test fun directorFollowsTheTurn() {
        val d = FaceDirector(afterglowMs = 3_000)
        assertEquals(IDLE_FACE, d.next("idle", false, "", IDLE_FACE, 0))
        assertEquals(LISTEN_FACE, d.next("hear", false, IDLE_FACE, IDLE_FACE, 100))
        assertEquals(THINK_FACE, d.next("think", false, LISTEN_FACE, IDLE_FACE, 200))
        // answer with an emotion: the mouth while speaking (talk_face=speak), then the emotion, then rest
        assertEquals(SPEAK_FACE, d.next("talk", true, "h0006", IDLE_FACE, 300))
        assertEquals("h0006", d.next("idle", false, "h0006", IDLE_FACE, 1_000))
        assertEquals("h0006", d.next("idle", false, IDLE_FACE, IDLE_FACE, 3_000))
        assertEquals(IDLE_FACE, d.next("idle", false, IDLE_FACE, IDLE_FACE, 4_000))
    }

    @Test fun directorTalkFaceEmotion() {
        DialogueFaces.speakDuringTalk = false
        val d = FaceDirector()
        assertEquals("h0006", d.next("talk", true, "h0006", IDLE_FACE, 0))
        assertEquals(SPEAK_FACE, d.next("talk", true, IDLE_FACE, IDLE_FACE, 10))
    }

    private fun wav(pcm: ShortArray, rate: Int): ByteArray {
        val data = ByteArray(pcm.size * 2)
        for (i in pcm.indices) { data[2 * i] = (pcm[i].toInt() and 0xff).toByte(); data[2 * i + 1] = (pcm[i].toInt() shr 8).toByte() }
        val h = ByteArray(44)
        fun put(at: Int, s: String) = s.toByteArray().copyInto(h, at)
        fun le32(at: Int, v: Int) { for (k in 0..3) h[at + k] = (v shr (8 * k)).toByte() }
        fun le16(at: Int, v: Int) { h[at] = v.toByte(); h[at + 1] = (v shr 8).toByte() }
        put(0, "RIFF"); le32(4, 36 + data.size); put(8, "WAVE"); put(12, "fmt "); le32(16, 16)
        le16(20, 1); le16(22, 1); le32(24, rate); le32(28, rate * 2); le16(32, 2); le16(34, 16)
        put(36, "data"); le32(40, data.size)
        return h + data
    }

    @Test fun envelopeFollowsLoudness() {
        val rate = 24_000
        // 0.5 s silence, then 0.5 s of a loud 220 Hz tone
        val pcm = ShortArray(rate) { i -> if (i < rate / 2) 0 else (12_000 * sin(2 * PI * 220 * i / rate)).toInt().toShort() }
        val env = SpeechEnvelope.fromWav(wav(pcm, rate))!!
        assertEquals(1_000, env.durationMs)
        assertEquals(0f, env.at(100))
        assertTrue(env.at(800) > 0.8f, "loud tone → mouth open (${env.at(800)})")
        assertEquals(0f, env.at(5_000))
        assertEquals(0f, env.at(-5))
    }

    @Test fun envelopeRejectsEncodedAudio() {
        assertNull(SpeechEnvelope.fromWav("ID3xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx".toByteArray()))
        assertNull(SpeechEnvelope.fromWav(ByteArray(10)))
    }
}
