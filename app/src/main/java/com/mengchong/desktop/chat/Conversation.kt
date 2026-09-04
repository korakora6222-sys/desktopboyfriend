package com.mengchong.desktop.chat

import com.mengchong.desktop.SettingsStore
import com.mengchong.desktop.persona.Memory
import com.mengchong.desktop.persona.Persona

/**
 * 对话编排器：把「人设 + 记忆 + 最近几轮对话」组装好，交给回话者(DeepSeek)，
 * 拿回回复后记进历史。
 */
class Conversation(
    private val replier: Replier,
    private val memory: Memory,
    private val settings: SettingsStore,
    private val history: MutableList<ChatMessage> = mutableListOf(),
) {

    suspend fun say(userText: String): String {
        history.add(ChatMessage("user", userText))
        trim()

        val system = ChatMessage(
            role = "system",
            content = Persona.systemPrompt(settings.nickname, memory.summary()),
        )
        // 语言由人设的规则决定（默认英文，她要求时自然切中文），不再按输入硬性对齐
        val messages = listOf(system) + history

        val reply = replier.reply(messages)

        history.add(ChatMessage("assistant", reply))
        trim()
        return reply
    }

    /** 主动开口：用户一会儿没出声时，他自己找话说。 */
    suspend fun proactive(): String {
        val call = settings.nickname.ifBlank { "宝贝" }
        val system = ChatMessage(
            role = "system",
            content = Persona.systemPrompt(settings.nickname, memory.summary()),
        )
        val nudge = ChatMessage(
            role = "system",
            content = "现在${call}有一小会儿没出声了。你主动开口说一句话——关心她、或撒个娇、或找个轻松话题、或夸夸她，" +
                "口语一点，符合你粘人小狗男友的人设，并遵守上面的语言规则（默认英文）。",
        )
        val messages = listOf(system) + history + listOf(nudge)
        val reply = replier.reply(messages)
        history.add(ChatMessage("assistant", reply))
        trim()
        return reply
    }


    private fun trim() {
        while (history.size > MAX_MESSAGES) history.removeAt(0)
    }

    companion object {
        // 保留最近 20 轮（一问一答算一轮）= 40 条消息。
        private const val MAX_MESSAGES = 40
    }
}
