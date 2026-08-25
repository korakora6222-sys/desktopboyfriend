package com.mengchong.desktop

/** 测试用的内存键值存储。 */
class FakeKV : KVStore {
    private val s = mutableMapOf<String, String>()
    private val b = mutableMapOf<String, Boolean>()
    override fun getString(key: String): String? = s[key]
    override fun putString(key: String, value: String) { s[key] = value }
    override fun getBoolean(key: String, def: Boolean): Boolean = b[key] ?: def
    override fun putBoolean(key: String, value: Boolean) { b[key] = value }
}
