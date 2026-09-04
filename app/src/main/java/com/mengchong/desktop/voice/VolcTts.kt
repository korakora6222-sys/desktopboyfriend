package com.mengchong.desktop.voice

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 火山引擎豆包语音合成 2.0（他的"嘴"）。
 *
 * 走 V3 HTTP 单向流式接口：POST 一段文本，服务端按行返回 JSON，
 * 每行里带一段 base64 的 PCM 音频；我们边收边用 AudioTrack 播放。
 *
 * 鉴权用旧版控制台凭证：X-Api-App-Key(APP ID) + X-Api-Access-Key(Access Token)。
 * X-Api-Resource-Id = seed-tts-2.0（选择 2.0 音色）。
 */
class VolcTts(
    private val appId: String,
    private val token: String,
    private val voiceType: String,
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var currentJob: Job? = null
    @Volatile private var track: AudioTrack? = null

    val isConfigured: Boolean get() = appId.isNotBlank() && token.isNotBlank()

    fun init() { /* HTTP 方式无需预初始化 */ }

    /** 念一段话（不等它念完就返回）。会打断上一段未念完的。 */
    fun speak(text: String) {
        if (!isConfigured) { Log.w(TAG, "火山凭证未配置"); return }
        val clean = clean(text)
        if (clean.isBlank()) return

        stop() // 打断上一句
        currentJob = scope.launch {
            try {
                synthesizeAndPlay(clean)
            } catch (e: Exception) {
                Log.e(TAG, "合成/播放异常", e)
            }
        }
    }

    /** 念一段话，念完（含尾音播完）才返回。用于"通话模式"一句一句轮流。 */
    suspend fun speakBlocking(text: String) {
        if (!isConfigured) return
        val c = clean(text)
        if (c.isBlank()) return
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            try { synthesizeAndPlay(c) } catch (e: Exception) { Log.e(TAG, "合成/播放异常", e) }
        }
    }

    private fun synthesizeAndPlay(text: String) {
        val bodyJson = JSONObject().apply {
            put("user", JSONObject().put("uid", "mengchong_${System.currentTimeMillis()}"))
            put("req_params", JSONObject().apply {
                put("text", text)
                put("speaker", voiceType)
                put("audio_params", JSONObject().apply {
                    put("format", "pcm")
                    put("sample_rate", SAMPLE_RATE)
                })
                // additions 是个 JSON 字符串：不把 ** 当"星星"念
                put("additions", JSONObject().put("disable_markdown_filter", true).toString())
            })
        }.toString()

        val req = Request.Builder()
            .url("https://openspeech.bytedance.com/api/v3/tts/unidirectional")
            .post(bodyJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("X-Api-App-Key", appId)
            .header("X-Api-Access-Key", token)
            .header("X-Api-Resource-Id", "seed-tts-2.0")
            .header("Content-Type", "application/json")
            .build()

        Log.d(TAG, "请求火山TTS voice=$voiceType 文本长度=${text.length}")
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.e(TAG, "HTTP ${resp.code}: ${resp.body?.string()?.take(300)}")
                return
            }
            val logId = resp.header("X-Tt-Logid")
            Log.d(TAG, "已连上，X-Tt-Logid=$logId")

            val at = newTrack()
            track = at
            at.play()

            var started = false
            resp.body?.source()?.let { source ->
                while (!source.exhausted() && scope.isActive) {
                    val line = source.readUtf8Line() ?: break
                    if (line.isBlank()) continue
                    val json = try { JSONObject(line) } catch (e: Exception) { continue }
                    val code = json.optInt("code", -999)
                    when {
                        code == 0 && json.has("data") -> {
                            val b64 = json.optString("data")
                            if (b64.isNotBlank()) {
                                val pcm = Base64.decode(b64, Base64.DEFAULT)
                                if (pcm.isNotEmpty()) {
                                    if (!started) { started = true; Log.d(TAG, "开始播放") }
                                    at.write(pcm, 0, pcm.size)
                                }
                            }
                        }
                        code == 20000000 -> { Log.d(TAG, "合成完成"); break }
                        code > 0 -> { Log.e(TAG, "火山TTS错误 code=$code msg=${json.optString("message")}"); break }
                    }
                }
            }
            // 收尾：等内部缓冲里的尾音放完再停，避免话没说完就切去听
            try { Thread.sleep(700) } catch (_: Exception) {}
            try { at.stop() } catch (_: Exception) {}
            at.release()
            if (track === at) track = null
        }
    }

    private fun newTrack(): AudioTrack {
        val min = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val buf = maxOf(min, SAMPLE_RATE) // 至少约 0.5 秒缓冲
        @Suppress("DEPRECATION")
        return AudioTrack(
            AudioManager.STREAM_MUSIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            buf,
            AudioTrack.MODE_STREAM
        )
    }

    fun stop() {
        currentJob?.cancel()
        currentJob = null
        track?.let {
            try { it.pause(); it.flush(); it.stop() } catch (_: Exception) {}
            it.release()
        }
        track = null
    }

    fun destroy() {
        stop()
        scope.cancel()
    }

    /** 去掉 emoji、压掉多余空白，避免朗读怪异。 */
    private fun clean(text: String): String {
        val noEmoji = text.replace(EMOJI, "")
        return noEmoji.trim()
    }

    companion object {
        private const val TAG = "MC-VOLCTTS"
        private const val SAMPLE_RATE = 24000
        // 常见 emoji 区段
        private val EMOJI = Regex(
            "[\\uD83C-\\uDBFF\\uDC00-\\uDFFF\\u2600-\\u27BF\\uFE0F\\u2000-\\u206F]"
        )
    }
}
