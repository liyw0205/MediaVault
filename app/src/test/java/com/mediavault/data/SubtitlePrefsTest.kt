package com.mediavault.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitlePrefsTest {
    @Test
    fun backgroundAlphaFromPercent_matchesExpectedOpacitySteps() {
        assertEquals(0x1A, SubtitlePrefs.backgroundAlphaFromPercent(10))
        assertEquals(0x4D, SubtitlePrefs.backgroundAlphaFromPercent(30))
        assertEquals(0x80, SubtitlePrefs.backgroundAlphaFromPercent(50))
        assertEquals(0xFF, SubtitlePrefs.backgroundAlphaFromPercent(100))
    }

    @Test
    fun backgroundAlphaFromPercent_clampsOutOfRangeValues() {
        assertEquals(0x1A, SubtitlePrefs.backgroundAlphaFromPercent(0))
        assertEquals(0x1A, SubtitlePrefs.backgroundAlphaFromPercent(-20))
        assertEquals(0xFF, SubtitlePrefs.backgroundAlphaFromPercent(101))
        assertEquals(0xFF, SubtitlePrefs.backgroundAlphaFromPercent(200))
    }

    @Test
    fun subtitleBackgroundColorForPercent_preservesBaseRgb() {
        assertEquals(0x4DF5F7FA, SubtitlePrefs.subtitleBackgroundColorForPercent(30, 0xFFF5F7FA.toInt()))
        assertEquals(0x1A112233, SubtitlePrefs.subtitleBackgroundColorForPercent(10, 0x00112233))
    }
}
