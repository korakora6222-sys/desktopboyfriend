package com.mengchong.desktop.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * 语音输入：用安卓系统自带的 SpeechRecognizer 把用户说的话转成文字。
 * 系统会依据设备已装的语言包识别中文/英文，无需第三方服务。
 * 使用前需已获得 RECORD_AUDIO 运行时权限。
 */
class SpeechInput(private val context: Context) {

    private var recognizer: SpeechRecognizer? = null

    fun listen(onResult: (String) -> Unit, onError: (String) -> Unit) {
        val available = SpeechRecognizer.isRecognitionAvailable(context)
        Log.d("MC-STT", "isRecognitionAvailable=$available")
        if (!available) {
            onError("这台手机没有可用的语音识别服务")
            return
        }
        release()
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle) {
                val list = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = list?.firstOrNull().orEmpty()
                if (text.isBlank()) onError("没听清呀") else onResult(text)
            }
            override fun onError(error: Int) = onError("识别出错（代码 $error）")
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            // 用设备当前语言；用户切中/英输入法或系统语言即可切换识别语言。
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, java.util.Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        r.startListening(intent)
    }

    fun release() {
        recognizer?.destroy()
        recognizer = null
    }
}
