package com.geeui.voice

import com.geeui.voice.audio.pcm16ToWav
import com.geeui.voice.audio.readWav
import com.geeui.voice.audio.toMono16k
import com.geeui.voice.bus.RecordingBus
import com.geeui.voice.engine.Chat
import com.geeui.voice.engine.EchoTts
import com.geeui.voice.engine.SpeechToText
import com.geeui.voice.engine.StreamingChat
import com.geeui.voice.engine.TimingChat
import com.geeui.voice.engine.TimingTts
import com.geeui.voice.session.VoiceSession
import com.geeui.voice.session.WavTestAccess
import com.geeui.voice.session.WavTestTurn
import com.geeui.voice.session.extractTags
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WavTestTurnTest {
    @Test
    fun wavRoundTripMono16k() {
        val src = ShortArray(1600) { i -> if (i % 2 == 0) 4000 else -4000 }
        val bytes = pcm16ToWav(src, 16_000)
        val clip = readWav(bytes)
        assertEquals(16_000, clip.sampleRate)
        assertEquals(src.toList(), clip.samples.toList())
    }

    @Test
    fun stereo48kBecomesMono16k() {
        val left = ShortArray(480) { 3000 }
        val right = ShortArray(480) { 1000 }
        val interleaved = ShortArray(960)
        for (i in left.indices) {
            interleaved[i * 2] = left[i]
            interleaved[i * 2 + 1] = right[i]
        }
        val wav = stereoWav(interleaved, 48_000)
        val mono = readWav(wav).toMono16k()
        assertEquals(160, mono.size)
        assertTrue(mono.all { it in 1900..2100 }, "expected average ~2000, got ${mono.firstOrNull()}")
    }

    @Test
    fun pathAllowlist() {
        assertTrue(WavTestAccess.allowedPath("/sdcard/geeui/in.wav"))
        assertTrue(WavTestAccess.allowedPath("/storage/emulated/0/Android/data/com.geeui.voice/files/voicetest/in.WAV"))
        assertTrue(WavTestAccess.allowedPath("/data/local/tmp/geeui/phrase.wav"))
        assertFalse(WavTestAccess.allowedPath("/sdcard/other.wav"))
        assertFalse(WavTestAccess.allowedPath("/sdcard/geeui/../other.wav"))
        assertFalse(WavTestAccess.allowedPath("/sdcard/geeui/in.mp3"))
        assertFalse(WavTestAccess.allowedPath("/sdcard/geeui/in.wav\n/etc/passwd"))
        assertFalse(WavTestAccess.allowedPath(""))
    }

    @Test
    fun extractEmotionAndActionTags() {
        val tags = extractTags("[happy] Bonjour ! [rappel:boire] 😊", face = "h0020")
        assertEquals("h0020", tags.face)
        assertEquals("happy", tags.emotion)
        assertEquals(listOf("rappel:boire"), tags.actions)
    }

    @Test
    fun jsonEscapesAndReportsTimings() {
        val chat = TimingChat(TaggedChat(), now = tick(1000L))
        val tts = TimingTts(EchoTts(), now = tick(1000L))
        val session = VoiceSession(RecordingBus(), tts, chat, pause = {})
        val turn = WavTestTurn(
            stt = FixedStt("bonjour"),
            session = session,
            chat = chat,
            tts = tts,
            now = tick(1000L),
        )
        val wav = pcm16ToWav(ShortArray(320) { 1000 }, 16_000)
        val result = turn.run(wav, path = "/sdcard/geeui/bonjour.wav", face = { "h0020" }, audio = { com.geeui.voice.session.WavTestAudio("happy", 0.82) })
        assertTrue(result.ok)
        assertEquals("bonjour", result.transcript)
        assertTrue(result.reply.contains("Bonjour"), result.reply)
        assertFalse(result.reply.contains("[happy]"), result.reply)
        assertEquals("happy", result.tags.emotion)
        assertEquals("h0020", result.tags.face)
        assertEquals(listOf("rappel:boire"), result.tags.actions)
        assertTrue(result.timings.total >= 0)
        val json = result.toJson()
        assertTrue(json.contains("\"transcript\": \"bonjour\""))
        assertTrue(json.contains("\"emotion\": \"happy\""))
        assertTrue(json.contains("\"stt\":"))
        assertTrue(json.contains("\"first_token\":"))
        assertTrue(json.contains("\"tts_first_audio\":"))
        assertTrue(json.contains("\"audio\":"), json)
        assertTrue(json.contains("\"faces\":"), json)
        assertTrue(json.contains("\"face_during_speech\":"), json)
        assertEquals("happy", result.audio.emotion)
        assertEquals(0.82, result.audio.confidence)
        assertTrue(json.contains("\"faces\":"), json)
        assertTrue(json.contains("face_during_speech"), json)
        assertFalse(result.reply.contains("[happy]"), "spoken reply must not include the emotion tag")
        assertTrue(result.rawReply.contains("[happy]"), result.rawReply)
    }

    @Test
    fun emptyTranscriptIsAnError() {
        val chat = TimingChat(TaggedChat())
        val tts = TimingTts(EchoTts())
        val session = VoiceSession(RecordingBus(), tts, chat, pause = {})
        val result = WavTestTurn(FixedStt("  "), session, chat, tts)
            .run(pcm16ToWav(ShortArray(16) { 1 }, 16_000))
        assertFalse(result.ok)
        assertEquals("transcription vide", result.error)
    }

    @Test
    fun badWavIsAnError() {
        val chat = TimingChat(TaggedChat())
        val tts = TimingTts(EchoTts())
        val session = VoiceSession(RecordingBus(), tts, chat, pause = {})
        val result = WavTestTurn(FixedStt("x"), session, chat, tts).run("not a wav".toByteArray())
        assertFalse(result.ok)
        assertTrue(result.error!!.contains("WAV"), result.error)
    }
}

private class FixedStt(private val text: String) : SpeechToText {
    override fun transcribe(pcm16le: ByteArray, sampleRate: Int) = text
}

private class TaggedChat : Chat, StreamingChat {
    override fun reply(userText: String) = "[happy] Bonjour ! [rappel:boire]"
    override fun stream(userText: String, onDelta: (String) -> Unit) {
        onDelta("[happy] ")
        onDelta("Bonjour ! ")
        onDelta("[rappel:boire]")
    }
}

/** Each call advances the clock by 5 ms so stage timings are non-zero and deterministic. */
private fun tick(start: Long): () -> Long {
    var t = start
    return { t += 5; t }
}

private fun stereoWav(interleaved: ShortArray, rate: Int): ByteArray {
    val dataBytes = interleaved.size * 2
    val out = ByteArray(44 + dataBytes)
    fun le32(at: Int, v: Int) {
        out[at] = (v and 0xff).toByte()
        out[at + 1] = (v shr 8 and 0xff).toByte()
        out[at + 2] = (v shr 16 and 0xff).toByte()
        out[at + 3] = (v shr 24 and 0xff).toByte()
    }
    fun le16(at: Int, v: Int) {
        out[at] = (v and 0xff).toByte()
        out[at + 1] = (v shr 8 and 0xff).toByte()
    }
    "RIFF".encodeToByteArray().copyInto(out, 0)
    le32(4, 36 + dataBytes)
    "WAVE".encodeToByteArray().copyInto(out, 8)
    "fmt ".encodeToByteArray().copyInto(out, 12)
    le32(16, 16)
    le16(20, 1)
    le16(22, 2)
    le32(24, rate)
    le32(28, rate * 4)
    le16(32, 4)
    le16(34, 16)
    "data".encodeToByteArray().copyInto(out, 36)
    le32(40, dataBytes)
    var i = 44
    for (s in interleaved) {
        out[i++] = (s.toInt() and 0xff).toByte()
        out[i++] = (s.toInt() shr 8).toByte()
    }
    return out
}
