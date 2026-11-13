package com.music.bitchord.ui.components

import com.music.bitchord.ui.components.backdrop.internal.steppedLayerSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The size a glass layer is recorded at while its surface changes size. */
class SteppedLayerSideTest {

    @Test
    fun `a side is never smaller than what has to be drawn in it`() {
        for (px in 1..500) {
            val side = steppedLayerSide(px, 48)
            assertTrue("$px got $side", side >= px)
            assertTrue("$px got $side", side - px < 48)
            assertEquals(0, side % 48)
        }
    }

    @Test
    fun `a side already on a step stays where it is`() {
        assertEquals(96, steppedLayerSide(96, 48))
        assertEquals(144, steppedLayerSide(97, 48))
    }

    @Test
    fun `a surface growing a pixel a frame changes its layer once a step`() {
        val sides = (200..296).map { steppedLayerSide(it, 48) }.distinct()
        assertEquals(listOf(240, 288, 336), sides)
    }

    @Test
    fun `nothing to draw still gets a layer`() {
        assertEquals(16, steppedLayerSide(0, 16))
        assertEquals(16, steppedLayerSide(-5, 16))
    }
}
