package com.geeui.voice.audio

/** 16-bit mono PCM to a WAV blob Lemonade STT accepts as a file. */
fun pcm16ToWav(pcm: ShortArray, sampleRate: Int): ByteArray {
    val dataBytes = pcm.size * 2
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
    le16(22, 1)
    le32(24, sampleRate)
    le32(28, sampleRate * 2)
    le16(32, 2)
    le16(34, 16)
    "data".encodeToByteArray().copyInto(out, 36)
    le32(40, dataBytes)
    var i = 44
    for (s in pcm) {
        out[i++] = (s.toInt() and 0xff).toByte()
        out[i++] = (s.toInt() shr 8 and 0xff).toByte()
    }
    return out
}
