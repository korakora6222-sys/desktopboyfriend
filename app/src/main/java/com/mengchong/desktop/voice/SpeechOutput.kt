package com.mengchong.desktop.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * 选语言的纯逻辑：文本里有中文字就用中文发声，否则用英文。
 * 单独抽出来方便单元测试。
 */
object LanguagePick {
    fun localeFor(text: String): Locale {
        val hasChinese = text.any { it in '一'..'鿿' }
        return if (hasChinese) Locale.CHINESE else Locale.ENGLISH
    }
}

/**
 * 语音合成：用手机自带的 TextToSpeech 把文字念出来。
 *
 * 兼容处理：有些国产手机没有设"默认语音引擎"，直接 new TextToSpeech 会初始化失败。
 * 这里会在失败时自动挑一个手机上已安装的引擎重试。
 */
class SpeechOutput(context: Context) {

    private val appCtx = context.applicationContext
    private var tts: TextToSpeech? = null
    private var ready = false
    private var engineList: List<String> = emptyList()
    private var engineIdx = -1

    /** 供外部查询是否可用（不可用时界面可给文字提示）。 */
    val isReady: Boolean get() = ready

    init {
        initTts(null)
    }

    private fun initTts(engine: String?) {
        tts = if (engine == null) {
            TextToSpeech(appCtx, ::onInit)
        } else {
            TextToSpeech(appCtx, ::onInit, engine)
        }
    }

    private fun onInit(status: Int) {
        val t = tts
        if (status == TextToSpeech.SUCCESS) {
            ready = true
            Log.d(TAG, "TTS 就绪，引擎=${t?.defaultEngine}")
            return
        }
        // 初始化失败：把手机上所有可用引擎一个个试过去
        if (engineList.isEmpty()) {
            engineList = t?.engines?.map { it.name } ?: emptyList()
            Log.d(TAG, "默认引擎不可用，可选引擎=$engineList")
        }
        engineIdx++
        if (engineIdx < engineList.size) {
            val e = engineList[engineIdx]
            Log.d(TAG, "尝试引擎[$engineIdx]: $e")
            t?.shutdown()
            initTts(e)
        } else {
            Log.w(TAG, "所有引擎都初始化失败，TTS 不可用")
        }
    }

    fun speak(text: String) {
        val t = tts
        if (t == null || !ready || text.isBlank()) {
            Log.w(TAG, "TTS 未就绪(ready=$ready)，跳过朗读: $text")
            return
        }
        val loc = LanguagePick.localeFor(text)
        val r = t.setLanguage(loc)
        Log.d(TAG, "setLanguage=$loc 结果=$r ; 朗读: $text")
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "mengchong-utter")
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
    }

    companion object {
        private const val TAG = "MC-TTS"
    }
}
