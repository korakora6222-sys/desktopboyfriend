package com.mengchong.desktop.voice

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * 火山引擎豆包流式语音识别（他的"耳朵"）。
 *
 * 走 V3 二进制 WebSocket 协议（bigmodel_nostream：一次把录好的音频发过去，拿最终文本）。
 * 鉴权用旧版凭证：X-Api-App-Key + X-Api-Access-Key；Resource-Id = volc.seedasr.sauc.duration。
 *
 * 协议帧：4字节 header + 4字节 seq + 4字节 payload长度 + payload(gzip)。整数一律大端。
 */
class VolcAsr(
    private val appId: String,
    private val token: String,
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    val isConfigured: Boolean get() = appId.isNotBlank() && token.isNotBlank()

    /** 把一段 PCM(16k/16bit/单声道) 音频识别成文字。失败或没听清返回空串。 */
    suspend fun recognize(pcm: ByteArray): String {
        if (!isConfigured || pcm.isEmpty()) return ""
        val result = CompletableDeferred<String>()

        val req = Request.Builder()
            .url("wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_nostream")
            .header("X-Api-App-Key", appId)
            .header("X-Api-Access-Key", token)
            .header("X-Api-Resource-Id", "volc.seedasr.sauc.duration")
            .header("X-Api-Connect-Id", UUID.randomUUID().toString())
            .build()

        val listener = object : WebSocketListener() {
            private var finalText = ""

            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "ASR 已连接 logid=${response.header("X-Tt-Logid")}")
                try {
                    // 1) full client request（配置）
                    val cfg = JSONObject().apply {
                        put("user", JSONObject().put("uid", "mengchong"))
                        put("audio", JSONObject().apply {
                            put("format", "pcm"); put("codec", "raw")
                            put("rate", 16000); put("bits", 16); put("channel", 1)
                        })
                        put("request", JSONObject().apply {
                            put("model_name", "bigmodel")
                            put("enable_itn", true)
                            put("enable_punc", true)
                            put("result_type", "full")
                        })
                    }
                    var seq = 1
                    webSocket.send(fullClientRequest(seq, cfg.toString()).toByteString())
                    seq++
                    // 2) 音频分片发送，最后一片打"结束"标记
                    val seg = 16000 * 2 / 1000 * 200 // 每片约 200ms
                    var off = 0
                    while (off < pcm.size) {
                        val end = minOf(off + seg, pcm.size)
                        val last = end >= pcm.size
                        webSocket.send(audioRequest(seq, pcm.copyOfRange(off, end), last).toByteString())
                        seq++
                        off = end
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "发送失败", e)
                    result.completeExceptionally(e)
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                try {
                    val (text, isLast, err) = parseFrame(bytes.toByteArray())
                    if (err != null) {
                        Log.e(TAG, "ASR 错误: $err")
                        if (!result.isCompleted) result.complete(finalText)
                        webSocket.close(1000, null)
                        return
                    }
                    if (text.isNotEmpty()) finalText = text
                    if (isLast) {
                        Log.d(TAG, "ASR 最终结果: $finalText")
                        if (!result.isCompleted) result.complete(finalText)
                        webSocket.close(1000, null)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "解析失败", e)
                    if (!result.isCompleted) result.complete(finalText)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "ASR 连接失败 code=${response?.code} body=${response?.body?.string()?.take(200)}", t)
                if (!result.isCompleted) result.complete(finalText)
            }
        }

        val ws = http.newWebSocket(req, listener)
        val text = withTimeoutOrNull(20000) { result.await() } ?: ""
        ws.close(1000, null)
        return text
    }

    // ---------- 二进制协议 ----------

    private fun header(msgType: Int, flags: Int, ser: Int, comp: Int): ByteArray =
        byteArrayOf(
            (((1 shl 4) or 1).toByte()),          // version1 + headerSize1
            (((msgType shl 4) or flags).toByte()),
            (((ser shl 4) or comp).toByte()),
            0
        )

    private fun be(v: Int): ByteArray = byteArrayOf(
        (v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte()
    )

    private fun fullClientRequest(seq: Int, json: String): ByteArray {
        val payload = gzip(json.toByteArray(Charsets.UTF_8))
        return header(0b0001, 0b0001, 0b0001, 0b0001) + be(seq) + be(payload.size) + payload
    }

    private fun audioRequest(seq: Int, chunk: ByteArray, isLast: Boolean): ByteArray {
        val flags = if (isLast) 0b0011 else 0b0001
        val actualSeq = if (isLast) -seq else seq
        val payload = gzip(chunk)
        return header(0b0010, flags, 0b0000, 0b0001) + be(actualSeq) + be(payload.size) + payload
    }

    private data class Parsed(val text: String, val isLast: Boolean, val error: String?)

    private fun parseFrame(b: ByteArray): Parsed {
        if (b.size < 4) return Parsed("", false, null)
        val headerSize = b[0].toInt() and 0x0f
        val msgType = (b[1].toInt() shr 4) and 0x0f
        val flags = b[1].toInt() and 0x0f
        val ser = (b[2].toInt() shr 4) and 0x0f
        val comp = b[2].toInt() and 0x0f
        var off = headerSize * 4
        if (flags and 0x01 != 0) off += 4              // 跳过 sequence
        val isLast = flags and 0x02 != 0

        fun readU32(): Int {
            val v = ((b[off].toInt() and 0xff) shl 24) or ((b[off + 1].toInt() and 0xff) shl 16) or
                    ((b[off + 2].toInt() and 0xff) shl 8) or (b[off + 3].toInt() and 0xff)
            off += 4
            return v
        }

        return when (msgType) {
            0b1001 -> { // server full response
                val size = readU32()
                var payload = b.copyOfRange(off, minOf(off + size, b.size))
                if (comp == 0b0001 && payload.isNotEmpty()) payload = gunzip(payload)
                val text = if (ser == 0b0001 && payload.isNotEmpty()) extractText(String(payload, Charsets.UTF_8)) else ""
                Parsed(text, isLast, null)
            }
            0b1111 -> { // error
                val code = readU32()
                val size = readU32()
                var payload = b.copyOfRange(off, minOf(off + size, b.size))
                if (comp == 0b0001 && payload.isNotEmpty()) payload = gunzip(payload)
                Parsed("", isLast, "code=$code ${String(payload, Charsets.UTF_8)}")
            }
            else -> Parsed("", isLast, null)
        }
    }

    private fun extractText(json: String): String = try {
        JSONObject(json).optJSONObject("result")?.optString("text") ?: ""
    } catch (e: Exception) {
        ""
    }

    private fun gzip(data: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(data) }
        return bos.toByteArray()
    }

    private fun gunzip(data: ByteArray): ByteArray =
        GZIPInputStream(data.inputStream()).use { it.readBytes() }

    companion object {
        private const val TAG = "MC-VOLCASR"
    }
}
