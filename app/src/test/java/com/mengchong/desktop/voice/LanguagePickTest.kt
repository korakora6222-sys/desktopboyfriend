package com.mengchong.desktop.voice

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class LanguagePickTest {
    @Test
    fun `含中文用中文，否则英文`() {
        assertEquals(Locale.CHINESE, LanguagePick.localeFor("抱抱你"))
        assertEquals(Locale.ENGLISH, LanguagePick.localeFor("I miss you"))
    }
}
