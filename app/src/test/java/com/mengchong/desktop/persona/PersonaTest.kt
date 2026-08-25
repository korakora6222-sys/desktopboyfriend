package com.mengchong.desktop.persona

import org.junit.Assert.assertTrue
import org.junit.Test

class PersonaTest {
    @Test
    fun `包含关键人设与双语指令`() {
        val p = Persona.systemPrompt(nickname = "宝贝", memorySummary = "喜欢猫；最近工作压力大")
        assertTrue(p.contains("宝贝"))
        assertTrue(p.contains("情感陪伴") || p.contains("安抚"))
        assertTrue(p.contains("小狗") || p.contains("粘人") || p.contains("撒娇"))
        assertTrue((p.contains("中文") && p.contains("英文")) || p.contains("English"))
        assertTrue(p.contains("喜欢猫"))
    }

    @Test
    fun `无昵称时用默认称呼且非空`() {
        val p = Persona.systemPrompt(nickname = "", memorySummary = "")
        assertTrue(p.isNotBlank())
        assertTrue(p.contains("宝贝"))
    }
}
