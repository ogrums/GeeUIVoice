package com.geeui.voice.engine

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * LAN client for a Lemonade server (OpenAI-compatible).
 * Default base is http://HOST:13305/api/v1 — not the robot, a PC on the LAN.
 * Lex stays installed and keeps the wake word. This client never opens the mic.
 */
class LemonadeClient(
    val baseUrl: String,
    private val apiKey: String = "lemonade",
    private val chatModel: String = "llama",
    private val sttModel: String = "whisper",
    private val ttsModel: String = "kokoro",
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build(),
) {
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
                "Content-Disposition: form-data; name=\"model\"\r\n\r\n$sttModel\r\n" +
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
        val request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl.trimEnd('/') + "/chat/completions"))
            .timeout(Duration.ofSeconds(120))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofLines())
        if (response.statusCode() !in 200..299) {
            throw IllegalStateException("lemonade ${response.statusCode()}")
        }
        response.body().forEach { line ->
            val data = line.removePrefix("data:").trim()
            if (data.isEmpty() || data == "[DONE]") return@forEach
            val marker = "\"content\":"
            val at = data.indexOf(marker)
            if (at < 0) return@forEach
            val piece = unquote(data.substring(at + marker.length).trimStart())
            if (piece.isNotEmpty() && piece != "null") onDelta(piece)
        }
    }

    private fun post(path: String, contentType: String, body: ByteArray): String =
        String(postBytes(path, contentType, body))

    private fun postBytes(path: String, contentType: String, body: ByteArray): ByteArray {
        val request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl.trimEnd('/') + path))
            .timeout(Duration.ofSeconds(60))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", contentType)
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofByteArray())
        if (response.statusCode() !in 200..299) {
            throw IllegalStateException("lemonade ${response.statusCode()} ${String(response.body())}")
        }
        return response.body()
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
