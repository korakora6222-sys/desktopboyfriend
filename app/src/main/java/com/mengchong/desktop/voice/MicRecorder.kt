package com.mengchong.desktop.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.io.ByteArrayOutputStream

/**
 * 麦克风录音 + 简单断句（VAD）。
 * 录 16k/16bit/单声道 PCM；检测到你开始说话、又停下来约 1 秒，就自动结束这一句。
 *
 * 返回这一句的 PCM 字节；如果一直没人说话（超时），返回空。
 */
class MicRecorder {

    @Volatile private var recording = false

    val isRecording: Boolean get() = recording

    /**
     * 录一句话。
     * @param shouldAbort 外部可随时让它中止（比如用户点了退出通话）
     * @param onListening 开始监听/听到说话的回调（用于界面提示）
     */
    @SuppressLint("MissingPermission")
    fun recordOneSentence(
        shouldAbort: () -> Boolean = { false },
        onListening: () -> Unit = {},
    ): ByteArray {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = maxOf(minBuf, SAMPLE_RATE * 2) // 至少约 1 秒
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufSize
            )
        } catch (e: Exception) {
            Log.e(TAG, "创建录音失败", e); return ByteArray(0)
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "录音未初始化"); record.release(); return ByteArray(0)
        }

        val out = ByteArrayOutputStream()
        val chunk = ShortArray(CHUNK_SAMPLES)
        var speechStarted = false
        var silenceMs = 0
        var elapsedMs = 0
        val chunkMs = CHUNK_SAMPLES * 1000 / SAMPLE_RATE

        recording = true
        try {
            record.startRecording()
            onListening()
            while (recording && !shouldAbort()) {
                val n = record.read(chunk, 0, chunk.size)
                if (n <= 0) continue
                val amp = avgAbs(chunk, n)

                if (!speechStarted) {
                    if (amp > START_AMP) {
                        speechStarted = true
                        appendPcm(out, chunk, n)
                    } else {
                        elapsedMs += chunkMs
                        if (elapsedMs > INITIAL_TIMEOUT_MS) { Log.d(TAG, "一直没说话，结束"); break }
                    }
                } else {
                    appendPcm(out, chunk, n)
                    elapsedMs += chunkMs
                    silenceMs = if (amp < SILENCE_AMP) silenceMs + chunkMs else 0
                    if (silenceMs > END_SILENCE_MS) { Log.d(TAG, "说完了(静音${silenceMs}ms)"); break }
                    if (elapsedMs > MAX_RECORD_MS) { Log.d(TAG, "到最长时长，结束"); break }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "录音异常", e)
        } finally {
            recording = false
            try { record.stop() } catch (_: Exception) {}
            record.release()
        }
        val bytes = out.toByteArray()
        Log.d(TAG, "本句录到 ${bytes.size} 字节, 说话=${speechStarted}")
        return if (speechStarted) bytes else ByteArray(0)
    }

    fun stop() { recording = false }

    private fun avgAbs(buf: ShortArray, n: Int): Int {
        var sum = 0L
        for (i in 0 until n) sum += kotlin.math.abs(buf[i].toInt())
        return (sum / n).toInt()
    }

    private fun appendPcm(out: ByteArrayOutputStream, buf: ShortArray, n: Int) {
        val b = ByteArray(n * 2)
        for (i in 0 until n) {
            b[i * 2] = (buf[i].toInt() and 0xff).toByte()
            b[i * 2 + 1] = ((buf[i].toInt() shr 8) and 0xff).toByte()
        }
        out.write(b, 0, b.size)
    }

    companion object {
        private const val TAG = "MC-MIC"
        private const val SAMPLE_RATE = 16000
        private const val CHUNK_SAMPLES = 1600      // 100ms
        private const val START_AMP = 700           // 开始说话的音量阈值
        private const val SILENCE_AMP = 500         // 静音阈值
        private const val END_SILENCE_MS = 1200     // 停顿 1.2 秒算说完（折中：不太抢答，也不太肉）
        private const val INITIAL_TIMEOUT_MS = 6000 // 一直没说话就结束
        private const val MAX_RECORD_MS = 15000     // 单句最长
    }
}
