package com.music.bitchord.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Sing level is how much vocal is cut; the readout is how much is left. */
class SingLevelTest {

    @Test
    fun `no cut reads as full vocals and a full cut as none`() {
        assertEquals(100, singVocalPercent(0f))
        assertEquals(0, singVocalPercent(1f))
        assertEquals(50, singVocalPercent(0.5f))
        assertEquals(25, singVocalPercent(0.75f))
    }

    @Test
    fun `a level outside the slider's range reads as its nearest end`() {
        assertEquals(100, singVocalPercent(-0.4f))
        assertEquals(0, singVocalPercent(3f))
    }

    @Test
    fun `Sing is off at rest and on as soon as the slider moves`() {
        assertFalse(singIsOn(0f))
        assertFalse(singIsOn(0.001f))
        assertTrue(singIsOn(0.01f))
        assertTrue(singIsOn(1f))
    }
}
