package com.mengchong.desktop.persona

import com.mengchong.desktop.FakeKV
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryTest {
    @Test
    fun `追加去重与摘要`() {
        val m = Memory(FakeKV())
        m.addFact("喜欢猫")
        m.addFact("喜欢猫")
        m.addFact("最近压力大")
        val s = m.summary()
        assertTrue(s.contains("喜欢猫"))
        assertTrue(s.contains("最近压力大"))
        assertEquals(1, Regex("喜欢猫").findAll(s).count())
    }

    @Test
    fun `超过上限保留最新`() {
        val m = Memory(FakeKV())
        repeat(35) { m.addFact("事实$it") }
        val list = m.summary().split("；")
        assertEquals(30, list.size)          // 上限 30 条
        assertTrue(list.contains("事实34"))   // 最新的在
        assertTrue(list.contains("事实5"))    // 第 6 条起保留
        assertFalse(list.contains("事实4"))   // 最旧的 0~4 被丢
        assertFalse(list.contains("事实0"))
    }
}
