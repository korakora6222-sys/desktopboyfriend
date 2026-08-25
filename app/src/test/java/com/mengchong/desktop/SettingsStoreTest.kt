package com.mengchong.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsStoreTest {
    @Test
    fun `默认值与读写`() {
        val s = SettingsStore(FakeKV())
        assertEquals("", s.apiKey)
        assertEquals("", s.nickname)
        assertTrue(s.petVisible)

        s.apiKey = "sk-123"
        s.nickname = "宝贝"
        s.petVisible = false

        assertEquals("sk-123", s.apiKey)
        assertEquals("宝贝", s.nickname)
        assertFalse(s.petVisible)
    }
}
