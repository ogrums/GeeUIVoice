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
    private val chatModel: String = "",
    private val sttModel: String = "whisper-base",
    private val ttsModel: String = "kokoro",
    private val systemPrompt: String = SPOKEN,
) {
    @Volatile private var resolvedStt: String? = null
    @Volatile private var resolvedChat: String? = null
    @Volatile private var resolvedTts: String? = null

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

    /**
     * Chat model from `-e chat`. Empty means the first installed model that is
     * not whisper and not kokoro. Never sends the placeholder name "llama".
     */
    fun resolveChat(): String {
        resolvedChat?.let { return it }
        val names = listModels()
        val asked = chatModel.trim()
        val hit = if (asked.isEmpty()) null else match(asked, names)
        val picked = hit
            ?: names.firstOrNull { isChat(it) }
            ?: ""
        resolvedChat = picked
        return picked
    }

    private fun match(asked: String, names: List<String>): String? =
        names.firstOrNull { it.equals(asked, ignoreCase = true) }
            ?: names.firstOrNull {
                it.endsWith("/$asked", ignoreCase = true) || it.endsWith(":$asked", ignoreCase = true)
            }

    private fun isChat(id: String): Boolean {
        val n = id.lowercase()
        return !n.contains("whisper") && !n.contains("kokoro") && !n.contains("tts") && !isTts(id)
    }

    /** TTS model from `-e tts`. Unknown id falls back to kokoro, or the first speech model. */
    fun resolveTts(): String {
        resolvedTts?.let { return it }
        val names = listModels()
        val asked = ttsModel.ifBlank { FALLBACK_TTS }
        val hit = match(asked, names)
        val picked = when {
            hit != null -> hit
            names.isEmpty() -> asked
            else -> names.firstOrNull { isTts(it) } ?: FALLBACK_TTS
        }
        resolvedTts = picked
        return picked
    }

    private fun isTts(id: String): Boolean {
        val n = id.lowercase()
        return n.contains("kokoro") || n.contains("piper") || n.contains("tts") || n.contains("speech")
    }
    fun chat(userText: String): String {
        val body = messages(userText, stream = false)
        val raw = post("/chat/completions", "application/json", body.toByteArray())
        val marker = "\"content\":"
        val at = raw.indexOf(marker)
        if (at < 0) return raw
        return contentText(raw.substring(at + marker.length)) ?: ""
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
        val model = resolveTts()
        if (model.isBlank()) return ByteArray(0)
        val mp3Body = """{"model":"$model","input":${json(text)},"voice":${json(voice)},"response_format":"mp3"}"""
        return try {
            postBytes("/audio/speech", "application/json", mp3Body.toByteArray())
        } catch (e: IllegalStateException) {
            val msg = e.message.orEmpty()
            if (!msg.contains("response_format") && !msg.contains("400")) throw e
            val plain = """{"model":"$model","input":${json(text)},"voice":${json(voice)}}"""
            postBytes("/audio/speech", "application/json", plain.toByteArray())
        }
    }

    /** SSE chat. [onDelta] fires per token so TTS can start on the first sentence. */
    fun chatStream(userText: String, onDelta: (String) -> Unit) {
        val model = resolveChat()
        if (model.isBlank()) {
            onDelta("Pas de modèle de chat sur le serveur.")
            return
        }
        val body = messages(userText, stream = true)
        val conn = open("/chat/completions", "application/json")
        conn.doOutput = true
        conn.outputStream.use { it.write(body.toByteArray()) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        if (code !in 200..299) {
            val err = stream?.readBytes()?.let { String(it) } ?: ""
            onDelta(err.ifBlank { "lemonade $code" })
            return
        }
        BufferedReader(InputStreamReader(stream)).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                val data = line.removePrefix("data:").trim()
                if (data.isEmpty()) continue
                if (data == "[DONE]") break
                val marker = "\"content\":"
                var from = 0
                while (from < data.length) {
                    val at = data.indexOf(marker, from)
                    if (at < 0) break
                    val piece = contentText(data.substring(at + marker.length))
                    if (piece != null) onDelta(piece)
                    from = at + marker.length
                }
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
        const val FALLBACK_TTS = "kokoro"
        const val SPOKEN = "Tu es la voix d'un petit robot. Réponds en français parlé, en une ou deux phrases courtes. Pas de markdown, pas d'étoiles, pas de listes, pas de titres."
    }

    private fun messages(userText: String, stream: Boolean): String {
        val sys = systemPrompt.trim()
        val system = if (sys.isEmpty()) "" else """{"role":"system","content":${json(sys)}},"""
        val flag = if (stream) ""","stream":true""" else ""
        return """{"model":"${resolveChat()}"$flag,"messages":[$system{"role":"user","content":${json(userText)}}]}"""
    }

    /** Only a quoted message. `null`, finish_reason and token counts are not speech. */
    private fun contentText(raw: String): String? {
        val s = raw.trimStart()
        if (s.isEmpty() || s[0] != '"') return null
        val text = unquote(s)
        if (text.isBlank()) return null
        if (text.contains("finish_reason") || text.contains("completion_tokens") || text.contains("prompt_tokens")) {
            return null
        }
        return text
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

class LemonadeTts(
    private val client: LemonadeClient,
    private val voice: String = "",
) : TextToSpeech {
    var lastAudio: ByteArray = ByteArray(0)
        private set

    override fun speak(text: String, language: String) {
        val chosen = voice.ifBlank { if (language == "en") "af_heart" else "ff_siwis" }
        lastAudio = client.speech(text, chosen)
    }

    override fun stop() {
        lastAudio = ByteArray(0)
    }
}

class LemonadeStt(private val client: LemonadeClient) : SpeechToText {
    override fun transcribe(pcm16le: ByteArray, sampleRate: Int): String =
        client.transcribe(pcm16le)
}
