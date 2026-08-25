package com.mengchong.desktop.chat

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DeepSeekClientTest {

    @Test
    fun `解析正常回复并带上鉴权头`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setBody(
                """{"choices":[{"message":{"role":"assistant","content":"我在呢宝贝~"}}]}"""
            )
        )
        server.start()

        val client = DeepSeekClient(
            baseUrl = server.url("/").toString(),
            apiKey = "k",
        )
        val out = client.reply(listOf(ChatMessage("user", "在吗")))
        assertEquals("我在呢宝贝~", out)

        val req = server.takeRequest()
        assertEquals("Bearer k", req.getHeader("Authorization"))
        server.shutdown()
    }

    @Test
    fun `错误状态抛异常`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(401))
        server.start()

        val client = DeepSeekClient(
            baseUrl = server.url("/").toString(),
            apiKey = "bad",
        )
        assertThrows(DeepSeekException::class.java) {
            runBlocking { client.reply(listOf(ChatMessage("user", "hi"))) }
        }
        server.shutdown()
    }
}
