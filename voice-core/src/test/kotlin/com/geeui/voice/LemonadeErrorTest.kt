package com.geeui.voice

import com.geeui.voice.engine.LemonadeClient
import com.geeui.voice.engine.isErrorText
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LemonadeErrorTest {
    @Test
    fun detectsHttpErrorPayloads() {
        assertTrue(isErrorText("ERR lemonade 500 boom"))
        assertTrue(isErrorText("  ERR timeout"))
        assertTrue(isErrorText("<!DOCTYPE html><html>404</html>"))
        assertTrue(isErrorText("<html>oops</html>"))
        assertFalse(isErrorText("bonjour"))
        assertFalse(isErrorText(LemonadeClient.SPOKEN_ERROR))
    }

    @Test
    fun chatReturnsSpokenErrorNotRawBody() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            ex.requestBody.readBytes()
            val body = if (ex.requestURI.path.endsWith("/models")) {
                """{"data":[{"id":"gemma"}]}"""
            } else {
                "internal error: stack trace"
            }
            val bytes = body.toByteArray()
            val code = if (ex.requestURI.path.endsWith("/models")) 200 else 500
            ex.sendResponseHeaders(code, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val client = LemonadeClient(
                "http://127.0.0.1:${server.address.port}/api/v1",
                chatModel = "gemma",
            )
            val reply = client.chat("salut")
            assertEquals(LemonadeClient.SPOKEN_ERROR, reply)
            assertFalse(reply.contains("stack"), reply)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun transcribeReturnsEmptyOnHttpError() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            ex.requestBody.readBytes()
            val body = if (ex.requestURI.path.endsWith("/models")) {
                """{"data":[{"id":"Whisper-Small"}]}"""
            } else {
                "ERR upstream"
            }
            val bytes = body.toByteArray()
            val code = if (ex.requestURI.path.endsWith("/models")) 200 else 503
            ex.sendResponseHeaders(code, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val client = LemonadeClient("http://127.0.0.1:${server.address.port}/api/v1")
            assertEquals("", client.transcribe(ByteArray(44)))
        } finally {
            server.stop(0)
        }
    }
}
