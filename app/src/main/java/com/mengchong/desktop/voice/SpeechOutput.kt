package com.mengchong.desktop.voice

import android.content.Context
import android.speech.tts.TextToSpeech
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
 * 语音合成：用安卓系统自带 TextToSpeech 把文字念出来。
 * 第一版用系统声音（免费、中英文都行），不是定制帅哥音。
 */
class SpeechOutput(context: Context) {

    private var ready = false
    private val tts = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
    }

    fun speak(text: String) {
        if (!ready || text.isBlank()) return
        tts.language = LanguagePick.localeFor(text)
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "mengchong-utter")
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
    }
}
