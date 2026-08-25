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
        val messages = listOf(system) + history

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
