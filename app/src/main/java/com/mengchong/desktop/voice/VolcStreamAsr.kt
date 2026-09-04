package com.mengchong.desktop.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * 火山引擎豆包"流式"语音识别 —— 边说边传，由火山判断"你这句说完没"（智能断句）。
 *
 * 相比 VolcAsr（录完整段再传），这里一边录一边把音频送过去；火山用 VAD 判停
 * （end_window_size ≈ 1 秒静音即判停），一旦它标记 definite=true，就认为你这句说完了。
 * 这样：你说完很快接、你还没说完它会等，不抢答也不傻等。
 */
class VolcStreamAsr(
    private val appId: String,
    private val token: String,
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    val isConfigured: Boolean get() = appId.isNotBlank() && token.isNotBlank()

    /**
     * 听你说一句话。边录边传，火山判停后返回这句文字。
     * 一直没人说话（约6秒）或超时，返回空串。
     * @param shouldAbort 外部可随时中止（比如退出通话）
     */
    @SuppressLint("MissingPermission")
    suspend fun listen(shouldAbort: () -> Boolean): String {
        if (!isConfigured) return ""
        val result = CompletableDeferred<String>()
        val lastText = AtomicReference("")
        val stop = AtomicBoolean(false)
        val speaking = AtomicBoolean(false)

        val req = Request.Builder()
            .url("wss://openspeech.bytedance.com/api/v3/sauc/bigmodel")
            .header("X-Api-App-Key", appId)
            .header("X-Api-Access-Key", token)
            .header("X-Api-Resource-Id", "volc.seedasr.sauc.duration")
            .header("X-Api-Connect-Id", UUID.randomUUID().toString())
            .build()

        var recorder: AudioRecord? = null

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "流式ASR已连接 logid=${response.header("X-Tt-Logid")}")
                // 1) 发配置：判停约1秒
                val cfg = JSONObject().apply {
                    put("user", JSONObject().put("uid", "mengchong"))
                    put("audio", JSONObject().apply {
                        put("format", "pcm"); put("codec", "raw")
                        put("rate", 16000); put("bits", 16); put("channel", 1)
                    })
                    put("request", JSONObject().apply {
                        put("model_name", "bigmodel")
                        put("enable_punc", true)
                        put("result_type", "full")
                        put("show_utterances", true)
                        put("end_window_size", 1000)   // 静音约1秒即判停
                    })
                }
                webSocket.send(fullClientRequest(1, cfg.toString()).toByteString())

                // 2) 开录音，边录边送
                Thread {
                    val rec = createRecorder()
                    recorder = rec
                    if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
                        Log.e(TAG, "录音初始化失败")
                        if (!result.isCompleted) result.complete("")
                        return@Thread
                    }
                    var seq = 2
                    val buf = ByteArray(3200) // 100ms @16k16bit
                    val startAt = System.currentTimeMillis()
                    try {
                        rec.startRecording()
                        while (!stop.get() && !shouldAbort()) {
                            val n = rec.read(buf, 0, buf.size)
                            if (n > 0) {
                                try {
                                    webSocket.send(audioRequest(seq, buf.copyOf(n), false).toByteString())
                                } catch (e: Exception) { break }
                                seq++
                            }
                            val elapsed = System.currentTimeMillis() - startAt
                            if (!speaking.get() && elapsed > NO_SPEECH_TIMEOUT_MS) break  // 一直没说话
                            if (elapsed > MAX_LISTEN_MS) break
                        }
                        // 送最后一包
                        try { webSocket.send(audioRequest(seq, ByteArray(0), true).toByteString()) } catch (_: Exception) {}
                    } catch (e: Exception) {
                        Log.e(TAG, "录音/发送异常", e)
                    } finally {
                        try { rec.stop() } catch (_: Exception) {}
                        rec.release()
                    }
                    // 录音结束后，给服务端一点时间回最终结果；没有就用已识别的
                    Thread.sleep(400)
                    if (!result.isCompleted) result.complete(lastText.get())
                }.start()
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                try {
                    val (text, definite, err) = parseFrame(bytes.toByteArray())
                    if (err != null) {
                        Log.e(TAG, "流式ASR错误: $err")
                        stop.set(true)
                        if (!result.isCompleted) result.complete(lastText.get())
                        return
                    }
                    if (text.isNotEmpty()) { lastText.set(text); speaking.set(true) }
                    if (definite && text.isNotEmpty()) {
                        Log.d(TAG, "判停(说完): $text")
                        stop.set(true)
                        if (!result.isCompleted) result.complete(text)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "解析失败", e)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "流式ASR连接失败 code=${response?.code}", t)
                stop.set(true)
                if (!result.isCompleted) result.complete(lastText.get())
            }
        }

        val ws = http.newWebSocket(req, listener)
        val text = withTimeoutOrNull(MAX_LISTEN_MS + 5000L) { result.await() } ?: lastText.get()
        stop.set(true)
        try { recorder?.stop() } catch (_: Exception) {}
        ws.close(1000, null)
        return text
    }

    @SuppressLint("MissingPermission")
    private fun createRecorder(): AudioRecord? = try {
        val min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(min, 16000 * 2)
        )
    } catch (e: Exception) {
        Log.e(TAG, "创建录音失败", e); null
    }

    // ---------- 二进制协议（与 VolcAsr 相同）----------

    private fun header(msgType: Int, flags: Int, ser: Int, comp: Int): ByteArray =
        byteArrayOf(
            (((1 shl 4) or 1).toByte()),
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

    private data class Parsed(val text: String, val definite: Boolean, val error: String?)

    private fun parseFrame(b: ByteArray): Parsed {
        if (b.size < 4) return Parsed("", false, null)
        val headerSize = b[0].toInt() and 0x0f
        val msgType = (b[1].toInt() shr 4) and 0x0f
        val flags = b[1].toInt() and 0x0f
        val comp = b[2].toInt() and 0x0f
        var off = headerSize * 4
        if (flags and 0x01 != 0) off += 4

        fun readU32(): Int {
            val v = ((b[off].toInt() and 0xff) shl 24) or ((b[off + 1].toInt() and 0xff) shl 16) or
                    ((b[off + 2].toInt() and 0xff) shl 8) or (b[off + 3].toInt() and 0xff)
            off += 4
            return v
        }

        return when (msgType) {
            0b1001 -> {
                val size = readU32()
                var payload = b.copyOfRange(off, minOf(off + size, b.size))
                if (comp == 0b0001 && payload.isNotEmpty()) payload = gunzip(payload)
                parseResult(String(payload, Charsets.UTF_8))
            }
            0b1111 -> {
                val code = readU32()
                val size = readU32()
                var payload = b.copyOfRange(off, minOf(off + size, b.size))
                if (comp == 0b0001 && payload.isNotEmpty()) payload = gunzip(payload)
                Parsed("", false, "code=$code ${String(payload, Charsets.UTF_8)}")
            }
            else -> Parsed("", false, null)
        }
    }

    private fun parseResult(json: String): Parsed = try {
        val result = JSONObject(json).optJSONObject("result")
        val text = result?.optString("text") ?: ""
        var definite = false
        val utts = result?.optJSONArray("utterances")
        if (utts != null) {
            for (i in 0 until utts.length()) {
                if (utts.getJSONObject(i).optBoolean("definite", false)) { definite = true; break }
            }
        }
        Parsed(text, definite, null)
    } catch (e: Exception) {
        Parsed("", false, null)
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
        private const val NO_SPEECH_TIMEOUT_MS = 6000L
        private const val MAX_LISTEN_MS = 20000L
    }
}
