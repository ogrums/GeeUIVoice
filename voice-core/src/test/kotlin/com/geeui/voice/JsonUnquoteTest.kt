package com.geeui.voice

import com.geeui.voice.engine.LemonadeClient
import com.geeui.voice.engine.jsonUnquote
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JsonUnquoteTest {
    @Test
    fun decodesEscapesInsteadOfDroppingTheBackslash() {
        assertEquals("voiture\n", jsonUnquote("\"voiture\\n\"}"))
        assertEquals("a\tb\"c\\d/e", jsonUnquote("\"a\\tb\\\"c\\\\d\\/e\""))
        assertEquals("café ça", jsonUnquote("\"caf\\u00e9 \\u00e7a\""))
        assertEquals("😀", jsonUnquote("\"\\ud83d\\ude00\""))
        assertEquals("null}", jsonUnquote("null}"))
    }

    @Test
    fun transcriptionDropsWhisperSpaceAndNewline() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            ex.requestBody.readBytes()
            val body = if (ex.requestURI.path.endsWith("/models")) {
                """{"data":[{"id":"Whisper-Small"}]}"""
            } else {
                """{"text":" Avance la voiture\n","emotion":"happy","emotion_confidence":0.8,"speaker":"helene","speaker_name":"H\u00e9l\u00e8ne","speaker_score":0.71}"""
            }
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val client = LemonadeClient("http://127.0.0.1:${server.address.port}/api/v1")
            assertEquals("Avance la voiture", client.transcribe(ByteArray(44)))
            assertEquals("happy", client.lastAudioEmotion)
            assertEquals("Hélène", client.lastSpeakerName)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun transcriptionForcesFrench() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var lastBody = ""
        server.createContext("/") { ex ->
            val raw = ex.requestBody.readBytes()
            if (ex.requestURI.path.endsWith("/transcriptions")) {
                lastBody = raw.toString(Charsets.ISO_8859_1)
            }
            val body = if (ex.requestURI.path.endsWith("/models")) {
                """{"data":[{"id":"Whisper-Small"}]}"""
            } else {
                """{"text":"bonjour"}"""
            }
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val client = LemonadeClient("http://127.0.0.1:${server.address.port}/api/v1")
            assertEquals("bonjour", client.transcribe(ByteArray(44)))
            assertTrue(lastBody.contains("name=\"language\""), lastBody)
            assertTrue(lastBody.contains("\r\n\r\nfr\r\n"), lastBody)
        } finally {
            server.stop(0)
        }
    }
}
