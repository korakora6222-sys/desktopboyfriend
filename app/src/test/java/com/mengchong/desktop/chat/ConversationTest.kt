package com.mengchong.desktop.chat

import com.mengchong.desktop.FakeKV
import com.mengchong.desktop.SettingsStore
import com.mengchong.desktop.persona.Memory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationTest {

    /** 假的回话者：记录收到的消息，固定回一句。 */
    class FakeReplier(var last: List<ChatMessage>? = null) : Replier {
        override suspend fun reply(messages: List<ChatMessage>): String {
            last = messages
            return "抱抱你~"
        }
    }

    @Test
    fun `首条消息带上人设与用户话`() = runBlocking {
        val settings = SettingsStore(FakeKV()).apply { nickname = "宝贝" }
        val mem = Memory(FakeKV()).apply { addFact("喜欢猫") }
        val r = FakeReplier()
        val conv = Conversation(r, mem, settings)

        val out = conv.say("今天好累")
        assertEquals("抱抱你~", out)

        val sent = r.last!!
        assertEquals("system", sent.first().role)
        assertTrue(sent.first().content.contains("宝贝"))
        assertTrue(sent.first().content.contains("喜欢猫"))
        assertEquals("今天好累", sent.last().content)
        assertEquals("user", sent.last().role)
    }

    @Test
    fun `历史累积包含上一轮回复`() = runBlocking {
        val r = FakeReplier()
        val conv = Conversation(r, Memory(FakeKV()), SettingsStore(FakeKV()))
        conv.say("一")
        conv.say("二")

        val sent = r.last!!
        // 第二轮发送的消息里，应包含上一轮的 assistant 回复
        assertTrue(sent.any { it.role == "assistant" && it.content == "抱抱你~" })
        // 也应包含第一轮的 user "一" 和本轮 user "二"
        assertTrue(sent.any { it.role == "user" && it.content == "一" })
        assertEquals("二", sent.last().content)
    }
}
