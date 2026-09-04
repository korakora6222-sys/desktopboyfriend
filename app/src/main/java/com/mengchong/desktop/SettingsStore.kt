package com.mengchong.desktop

import android.content.Context
import android.content.SharedPreferences

/**
 * 一个极简的键值存储接口。
 * 抽象出来是为了：真机上用 SharedPreferences，单元测试里用内存假实现。
 */
interface KVStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun getBoolean(key: String, def: Boolean): Boolean
    fun putBoolean(key: String, value: Boolean)
}

/** 真机实现：基于 Android SharedPreferences。 */
class SharedPrefKVStore(context: Context) : KVStore {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("mengchong_settings", Context.MODE_PRIVATE)

    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }
    override fun getBoolean(key: String, def: Boolean): Boolean = prefs.getBoolean(key, def)
    override fun putBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }
}

/**
 * 应用设置：DeepSeek 的 API Key、用户昵称、桌宠是否显示。
 */
class SettingsStore(private val kv: KVStore) {

    var apiKey: String
        get() = kv.getString(KEY_API) ?: ""
        set(value) = kv.putString(KEY_API, value)

    var nickname: String
        get() = kv.getString(KEY_NICK) ?: ""
        set(value) = kv.putString(KEY_NICK, value)

    var petVisible: Boolean
        get() = kv.getBoolean(KEY_VISIBLE, true)
        set(value) = kv.putBoolean(KEY_VISIBLE, value)

    // 火山引擎豆包语音的凭证（他的"嘴"和"耳朵"）
    var volcAppId: String
        get() = kv.getString(KEY_VOLC_APPID) ?: ""
        set(value) = kv.putString(KEY_VOLC_APPID, value)

    var volcToken: String
        get() = kv.getString(KEY_VOLC_TOKEN) ?: ""
        set(value) = kv.putString(KEY_VOLC_TOKEN, value)

    // 他的音色（默认：少年梓辛 2.0，小奶狗系男声；2.0 音色是 _uranus_ 结尾）
    var volcVoice: String
        get() = kv.getString(KEY_VOLC_VOICE) ?: "zh_male_shaonianzixin_uranus_bigtts"
        set(value) = kv.putString(KEY_VOLC_VOICE, value)

    companion object {
        private const val KEY_API = "api_key"
        private const val KEY_NICK = "nickname"
        private const val KEY_VISIBLE = "pet_visible"
        private const val KEY_VOLC_APPID = "volc_appid"
        private const val KEY_VOLC_TOKEN = "volc_token"
        private const val KEY_VOLC_VOICE = "volc_voice"
    }
}
