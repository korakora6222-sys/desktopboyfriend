package com.mengchong.desktop.chat

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import java.util.concurrent.TimeUnit

/** 一条对话消息。role 取值："system" / "user" / "assistant"。 */
data class ChatMessage(val role: String, val content: String)

/** 调用 DeepSeek 失败时抛出。 */
class DeepSeekException(message: String) : Exception(message)

/**
 * "回话者"接口。DeepSeekClient 实现它；测试里可用假实现替换，方便测对话编排。
 */
interface Replier {
    suspend fun reply(messages: List<ChatMessage>): String
}

/**
 * DeepSeek 客户端。把一串对话消息发过去，拿回 assistant 的回复文本。
 */
class DeepSeekClient(
    baseUrl: String = "https://api.deepseek.com",
    private val apiKey: String,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
    private val model: String = "deepseek-chat",
) : Replier {

    private val base = baseUrl.trimEnd('/')
    private val gson = Gson()
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    override suspend fun reply(messages: List<ChatMessage>): String = withContext(Dispatchers.IO) {
        val body = JsonObject().apply {
            addProperty("model", model)
            add("messages", gson.toJsonTree(messages))
        }.toString().toRequestBody(jsonMedia)

        val request = Request.Builder()
            .url("$base/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body)
            .build()

        http.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw DeepSeekException("DeepSeek 返回错误 ${resp.code}: $text")
            }
            try {
                JsonParser.parseString(text)
                    .asJsonObject
                    .getAsJsonArray("choices")
                    .get(0).asJsonObject
                    .getAsJsonObject("message")
                    .get("content").asString
            } catch (e: Exception) {
                throw DeepSeekException("解析 DeepSeek 回复失败: $text")
            }
        }
    }
}
