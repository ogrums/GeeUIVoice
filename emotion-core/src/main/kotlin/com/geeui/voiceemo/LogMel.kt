package com.geeui.voiceemo

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Log-mel window shared with tools/export_emotion_tflite.py.
 * 16 kHz, window 400, hop 160, FFT 512, 64 mels, last 128 frames.
 * The tensor layout is [time, mel], shape [128, 64].
 */
object LogMel {
    const val SAMPLE_RATE = 16_000
    const val N_FFT = 512
    const val WIN = 400
    const val HOP = 160
    const val MELS = 64
    const val FRAMES = 128

    private val window = FloatArray(WIN) { i ->
        (0.5 - 0.5 * cos(2.0 * PI * i / (WIN - 1))).toFloat()
    }
    private val filters: Array<FloatArray> by lazy { filterbank() }

    fun features(pcm: ShortArray): Array<FloatArray> {
        val samples = FloatArray(pcm.size) { i -> pcm[i] / 32768f }
        val hops = if (samples.size <= WIN) 0 else (samples.size - WIN) / HOP + 1
        val keep = hops.coerceAtMost(FRAMES)
        val start = (hops - keep).coerceAtLeast(0)
        val out = Array(FRAMES) { FloatArray(MELS) }
        val spec = FloatArray(N_FFT / 2 + 1)
        for (t in 0 until keep) {
            magnitude(samples, (start + t) * HOP, spec)
            val row = out[FRAMES - keep + t]
            for (m in 0 until MELS) {
                var acc = 0.0
                val f = filters[m]
                for (k in f.indices) acc += f[k] * spec[k]
                row[m] = ln(acc.coerceAtLeast(1e-10)).toFloat()
            }
        }
        return out
    }

    private fun magnitude(samples: FloatArray, offset: Int, dest: FloatArray) {
        val re = FloatArray(N_FFT)
        val im = FloatArray(N_FFT)
        val n = minOf(WIN, samples.size - offset)
        for (i in 0 until n) re[i] = samples[offset + i] * window[i]
        fft(re, im)
        for (k in dest.indices) dest[k] = sqrt(re[k] * re[k] + im[k] * im[k])
    }

    private fun filterbank(): Array<FloatArray> {
        val bins = N_FFT / 2 + 1
        val melMax = hzToMel(SAMPLE_RATE / 2.0)
        val points = DoubleArray(MELS + 2) { i -> melToHz(melMax * i / (MELS + 1)) }
        val edges = IntArray(points.size) { i ->
            floor((N_FFT + 1) * points[i] / SAMPLE_RATE).toInt().coerceIn(0, bins - 1)
        }
        return Array(MELS) { m ->
            val left = edges[m]
            val center = edges[m + 1]
            val right = edges[m + 2]
            FloatArray(bins) { k ->
                when {
                    center > left && k in left..center -> (k - left).toFloat() / (center - left)
                    right > center && k in center..right -> (right - k).toFloat() / (right - center)
                    else -> 0f
                }
            }
        }
    }

    private fun hzToMel(hz: Double): Double = 2595.0 * kotlin.math.log10(1.0 + hz / 700.0)

    private fun melToHz(mel: Double): Double = 700.0 * (10.0.pow(mel / 2595.0) - 1.0)

    private fun Double.pow(exp: Double): Double = kotlin.math.exp(exp * kotlin.math.ln(this))

    private fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = re[i]
                re[i] = re[j]
                re[j] = tr
                val ti = im[i]
                im[i] = im[j]
                im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = (-2.0 * PI / len).toFloat()
            val wlenRe = cos(ang)
            val wlenIm = sin(ang)
            var i = 0
            while (i < n) {
                var wRe = 1f
                var wIm = 0f
                for (k in 0 until len / 2) {
                    val uRe = re[i + k]
                    val uIm = im[i + k]
                    val vRe = re[i + k + len / 2] * wRe - im[i + k + len / 2] * wIm
                    val vIm = re[i + k + len / 2] * wIm + im[i + k + len / 2] * wRe
                    re[i + k] = uRe + vRe
                    im[i + k] = uIm + vIm
                    re[i + k + len / 2] = uRe - vRe
                    im[i + k + len / 2] = uIm - vIm
                    val nextRe = wRe * wlenRe - wIm * wlenIm
                    wIm = wRe * wlenIm + wIm * wlenRe
                    wRe = nextRe
                }
                i += len
            }
            len = len shl 1
        }
    }
}
