package com.music.bitchord.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParametricEqTextTest {

    private val sampleApoText = """
        Preamp: -2.5 dB
        Filter 1: ON LSC Fc 60 Hz Gain 4.0 dB Q 1.41
        Filter 2: ON PK Fc 150 Hz Gain 2.0 dB Q 1.41
        Filter 3: ON PK Fc 400 Hz Gain 0.0 dB Q 1.41
        Filter 4: ON PK Fc 1000 Hz Gain -1.5 dB Q 1.41
        Filter 5: ON PK Fc 2500 Hz Gain 1.0 dB Q 1.41
        Filter 6: ON PK Fc 6000 Hz Gain 3.0 dB Q 1.41
        Filter 7: ON HSC Fc 14000 Hz Gain 3.5 dB Q 1.41
    """.trimIndent()

    private val sampleGraphicEqText = """
        GraphicEQ: 60 4.0; 150 2.0; 400 0.0; 1000 -1.5; 2500 1.0; 6000 3.0; 14000 3.5
    """.trimIndent()

    @Test
    fun parsesEqualizerApoFormat() {
        val parsed = ParametricEqText.parse(sampleApoText)
        assertNotNull(parsed)
        assertEquals(-2.5f, parsed!!.preampDb, 0.01f)
        assertEquals(7, parsed.filters.size)
        assertEquals(7, parsed.bands.size)
        // Check near 60Hz and 14000Hz
        assertTrue("60Hz band should have positive gain", parsed.bands[0] > 3f)
        assertTrue("1000Hz band should have negative gain", parsed.bands[3] < 0f)
    }

    @Test
    fun parsesGraphicEqFormat() {
        val parsed = ParametricEqText.parse(sampleGraphicEqText)
        assertNotNull(parsed)
        assertEquals(7, parsed!!.bands.size)
        assertEquals(4.0f, parsed.bands[0], 0.05f)
        assertEquals(2.0f, parsed.bands[1], 0.05f)
        assertEquals(0.0f, parsed.bands[2], 0.05f)
        assertEquals(-1.5f, parsed.bands[3], 0.05f)
        assertEquals(1.0f, parsed.bands[4], 0.05f)
        assertEquals(3.0f, parsed.bands[5], 0.05f)
        assertEquals(3.5f, parsed.bands[6], 0.05f)
    }

    @Test
    fun formatsBandsToValidApoTextAndRoundTrips() {
        val originalBands = listOf(3.0f, 1.5f, 0.0f, 1.5f, 2.5f, 2.0f, 1.0f)
        val formatted = ParametricEqText.format(originalBands)
        assertTrue(formatted.contains("Preamp: 0.0 dB"))
        assertTrue(formatted.contains("Filter 1: ON LSC Fc 60 Hz Gain 3.0 dB"))

        val reparsed = ParametricEqText.parse(formatted)
        assertNotNull(reparsed)
        assertEquals(7, reparsed!!.bands.size)
    }

    @Test
    fun emptyOrInvalidTextReturnsNull() {
        assertNull(ParametricEqText.parse(""))
        assertNull(ParametricEqText.parse("This is just some random text with no filters."))
    }
}
