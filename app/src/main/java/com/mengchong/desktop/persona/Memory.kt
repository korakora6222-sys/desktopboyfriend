package com.mengchong.desktop.persona

import com.mengchong.desktop.KVStore

/**
 * 第一版的"记忆"：一份关于用户的简单事实清单。
 * 例如："喜欢猫"、"最近工作压力大"、"喜欢被叫宝贝"。
 * 不做复杂的向量检索，够用就好。最多保留 MAX 条，超了丢最旧的。
 */
class Memory(private val kv: KVStore) {

    fun facts(): List<String> {
        val raw = kv.getString(KEY) ?: return emptyList()
        return raw.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** 追加一条事实；已存在则不重复；超过上限丢弃最旧的。 */
    fun addFact(fact: String) {
        val clean = fact.trim()
        if (clean.isEmpty()) return
        val list = facts().toMutableList()
        list.remove(clean)          // 去重：若已存在，移到最后（视为最新）
        list.add(clean)
        while (list.size > MAX) list.removeAt(0)
        kv.putString(KEY, list.joinToString("\n"))
    }

    /** 拼成给 Persona 用的摘要。 */
    fun summary(): String = facts().joinToString("；")

    companion object {
        private const val KEY = "memory_facts"
        private const val MAX = 30
    }
}
