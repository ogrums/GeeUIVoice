package com.geeui.voice.engine

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * LAN client for a Lemonade server (OpenAI-compatible).
 * Uses HttpURLConnection so it runs on Android 11 (no java.net.http).
 * Base is http://HOST:13305/api/v1 on a PC, not on the robot.
 */
class LemonadeClient(
    val baseUrl: String,
    private val apiKey: String = "lemonade",
    private val chatModel: String = "llama",
    private val sttModel: String = "whisper-base",
    private val ttsModel: String = "kokoro",
) {
    @Volatile private var resolvedStt: String? = null

    /** Model id actually sent to /audio/transcriptions. Falls back to whisper-base. */
    fun resolveStt(): String {
        resolvedStt?.let { return it }
        val names = listModels()
        val asked = sttModel.ifBlank { FALLBACK_STT }
        val hit = names.firstOrNull { it.equals(asked, ignoreCase = true) }
            ?: names.firstOrNull { it.endsWith("/$asked", ignoreCase = true) || it.endsWith(":$asked", ignoreCase = true) }
        val picked = when {
            names.isEmpty() -> asked
            hit != null -> hit
            else -> names.firstOrNull { it.contains(FALLBACK_STT, ignoreCase = true) } ?: FALLBACK_STT
        }
        resolvedStt = picked
        return picked
    }
    fun chat(userText: String): String {
        val body = """{"model":"$chatModel","messages":[{"role":"user","content":${json(userText)}}]}"""
        val raw = post("/chat/completions", "application/json", body.toByteArray())
        val marker = "\"content\":"
        val at = raw.indexOf(marker)
        if (at < 0) return raw
        return unquote(raw.substring(at + marker.length).trimStart())
    }

    fun transcribe(wav: ByteArray): String {
        val boundary = "----geeui${System.nanoTime()}"
        val head = (
            "--$boundary\r\n" +
                "Content-Disposition: form-data; name=\"model\"\r\n\r\n${resolveStt()}\r\n" +
                "--$boundary\r\n" +
                "Content-Disposition: form-data; name=\"file\"; filename=\"turn.wav\"\r\n" +
                "Content-Type: audio/wav\r\n\r\n"
            ).toByteArray()
        val tail = "\r\n--$boundary--\r\n".toByteArray()
        val payload = ByteArray(head.size + wav.size + tail.size)
        head.copyInto(payload)
        wav.copyInto(payload, head.size)
        tail.copyInto(payload, head.size + wav.size)
        val raw = post("/audio/transcriptions", "multipart/form-data; boundary=$boundary", payload)
        if (raw.startsWith("ERR ") && resolveStt() != FALLBACK_STT) {
            resolvedStt = FALLBACK_STT
            return transcribe(wav)
        }
        val marker = "\"text\":"
        val at = raw.indexOf(marker)
        if (at < 0) return raw
        return unquote(raw.substring(at + marker.length).trimStart())
    }

    fun speech(text: String, voice: String): ByteArray {
        val body = """{"model":"$ttsModel","input":${json(text)},"voice":${json(voice)}}"""
        return postBytes("/audio/speech", "application/json", body.toByteArray())
    }

    /** SSE chat. [onDelta] fires per token so TTS can start on the first sentence. */
    fun chatStream(userText: String, onDelta: (String) -> Unit) {
        val body = """{"model":"$chatModel","stream":true,"messages":[{"role":"user","content":${json(userText)}}]}"""
        val conn = open("/chat/completions", "application/json")
        conn.doOutput = true
        conn.outputStream.use { it.write(body.toByteArray()) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        if (code !in 200..299) {
            val err = stream?.readBytes()?.let { String(it) } ?: ""
            throw IllegalStateException("lemonade $code $err")
        }
        BufferedReader(InputStreamReader(stream)).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                val data = line.removePrefix("data:").trim()
                if (data.isEmpty()) continue
                if (data == "[DONE]") break
                val marker = "\"content\":"
                val at = data.indexOf(marker)
                if (at < 0) continue
                val piece = unquote(data.substring(at + marker.length).trimStart())
                if (piece.isNotEmpty() && piece != "null") onDelta(piece)
            }
        }
    }

    private fun post(path: String, contentType: String, body: ByteArray): String {
        return try {
            String(postBytes(path, contentType, body))
        } catch (e: IllegalStateException) {
            "ERR " + (e.message ?: "")
        }
    }

    private fun listModels(): List<String> {
        return try {
            val conn = open("/models", "application/json", "GET")
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else return emptyList()
            val raw = stream.readBytes().toString(Charsets.UTF_8)
            val out = ArrayList<String>()
            var at = 0
            val key = "\"id\":"
            while (true) {
                val i = raw.indexOf(key, at)
                if (i < 0) break
                out += unquote(raw.substring(i + key.length).trimStart())
                at = i + key.length
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun postBytes(path: String, contentType: String, body: ByteArray): ByteArray {
        val conn = open(path, contentType)
        conn.doOutput = true
        conn.outputStream.use { it.write(body) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val bytes = stream?.readBytes() ?: ByteArray(0)
        if (code !in 200..299) throw IllegalStateException("lemonade $code ${String(bytes)}")
        return bytes
    }

    private fun open(path: String, contentType: String, method: String = "POST"): HttpURLConnection {
        val conn = URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 5_000
        conn.readTimeout = 120_000
        conn.setRequestProperty("Authorization", "Bearer $apiKey")
        conn.setRequestProperty("Content-Type", contentType)
        return conn
    }

    companion object {
        const val FALLBACK_STT = "whisper-base"
    }

    private fun json(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    private fun unquote(raw: String): String {
        if (raw.isEmpty() || raw[0] != '"') return raw.take(200)
        val out = StringBuilder()
        var i = 1
        while (i < raw.length) {
            val c = raw[i]
            if (c == '\\' && i + 1 < raw.length) {
                out.append(raw[i + 1])
                i += 2
                continue
            }
            if (c == '"') break
            out.append(c)
            i++
        }
        return out.toString()
    }
}

class LemonadeChat(private val client: LemonadeClient) : Chat, StreamingChat {
    override fun reply(userText: String): String = client.chat(userText)

    override fun stream(userText: String, onDelta: (String) -> Unit) {
        client.chatStream(userText, onDelta)
    }
}

class LemonadeTts(private val client: LemonadeClient) : TextToSpeech {
    var lastAudio: ByteArray = ByteArray(0)
        private set

    override fun speak(text: String, language: String) {
        val voice = if (language == "en") "af_heart" else "ff_siwis"
        lastAudio = client.speech(text, voice)
    }

    override fun stop() {
        lastAudio = ByteArray(0)
    }
}

class LemonadeStt(private val client: LemonadeClient) : SpeechToText {
    override fun transcribe(pcm16le: ByteArray, sampleRate: Int): String =
        client.transcribe(pcm16le)
}
