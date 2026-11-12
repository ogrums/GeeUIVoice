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

data class PcmClip(val sampleRate: Int, val samples: ShortArray)

/**
 * Reads a PCM WAV (8/16-bit integer, 1 or 2 channels). Other formats fail with an error.
 * The result is always mono; stereo is averaged.
 */
fun readWav(wav: ByteArray): PcmClip {
    if (wav.size < 44 || wav[0] != 'R'.code.toByte() || wav[8] != 'W'.code.toByte()) {
        error("pas un fichier WAV")
    }
    var rate = 0
    var channels = 1
    var bits = 0
    var format = 0
    var pcm: ByteArray? = null
    var i = 12
    while (i + 8 <= wav.size) {
        val id = String(wav, i, 4, Charsets.US_ASCII)
        val size = le32(wav, i + 4)
        val start = i + 8
        if (size < 0 || start > wav.size) break
        if (id == "fmt " && start + 16 <= wav.size) {
            format = le16(wav, start)
            channels = le16(wav, start + 2)
            rate = le32(wav, start + 4)
            bits = le16(wav, start + 14)
            if (format == 0xFFFE && start + 26 <= wav.size) format = le16(wav, start + 24)
        } else if (id == "data" && size > 0) {
            pcm = wav.copyOfRange(start, (start + size).coerceAtMost(wav.size))
            break
        }
        i = start + size + (size and 1)
    }
    val data = pcm ?: error("WAV sans données")
    if (channels !in 1..2 || rate !in 8_000..48_000) error("WAV non supporté ($rate Hz, $channels ch)")
    if (format != 1 || bits !in setOf(8, 16)) error("WAV non PCM 8/16 bits")
    val mono = toMono16(data, channels, bits)
    if (mono.isEmpty()) error("WAV vide")
    return PcmClip(rate, mono)
}

/** Linear resample to [targetRate] (16 kHz for the voice loop). */
fun PcmClip.resample(targetRate: Int): ShortArray {
    if (sampleRate == targetRate) return samples
    if (samples.isEmpty()) return ShortArray(0)
    val outLen = ((samples.size.toLong() * targetRate) / sampleRate).toInt().coerceAtLeast(1)
    val out = ShortArray(outLen)
    val step = sampleRate.toDouble() / targetRate
    for (i in 0 until outLen) {
        val src = i * step
        val a = src.toInt().coerceIn(0, samples.lastIndex)
        val b = (a + 1).coerceAtMost(samples.lastIndex)
        val t = src - a
        out[i] = (samples[a] * (1.0 - t) + samples[b] * t).toInt().coerceIn(-32768, 32767).toShort()
    }
    return out
}

fun PcmClip.toMono16k(): ShortArray = resample(16_000)

private fun toMono16(data: ByteArray, channels: Int, bits: Int): ShortArray {
    val frame = channels * (bits / 8)
    if (frame <= 0) return ShortArray(0)
    val n = data.size / frame
    val out = ShortArray(n)
    var i = 0
    for (s in 0 until n) {
        var acc = 0
        for (c in 0 until channels) {
            acc += when (bits) {
                8 -> ((data[i++].toInt() and 0xff) - 128) shl 8
                else -> {
                    val lo = data[i++].toInt() and 0xff
                    val hi = data[i++].toInt()
                    (lo or (hi shl 8)).toShort().toInt()
                }
            }
        }
        out[s] = (acc / channels).coerceIn(-32768, 32767).toShort()
    }
    return out
}

private fun le16(b: ByteArray, at: Int): Int {
    if (at + 1 >= b.size) return 0
    return (b[at].toInt() and 0xff) or ((b[at + 1].toInt() and 0xff) shl 8)
}

private fun le32(b: ByteArray, at: Int): Int {
    if (at + 3 >= b.size) return 0
    return (b[at].toInt() and 0xff) or
        ((b[at + 1].toInt() and 0xff) shl 8) or
        ((b[at + 2].toInt() and 0xff) shl 16) or
        ((b[at + 3].toInt() and 0xff) shl 24)
}
