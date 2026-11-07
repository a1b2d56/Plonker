package com.music.bitchord

import com.music.bitchord.ui.player.SliderDetents
import org.junit.Test
import org.junit.Assert.assertEquals

class SliderDetentsTest {

    @Test fun theEndsAreTheirOwnPlaces() {
        assertEquals(-1, SliderDetents.edge(0f))
        assertEquals(-1, SliderDetents.edge(-0.2f))
        assertEquals(1, SliderDetents.edge(1f))
        assertEquals(1, SliderDetents.edge(1.3f))
    }

    @Test fun everywhereBetweenIsOnePlace() {
        assertEquals(0, SliderDetents.edge(0.001f))
        assertEquals(0, SliderDetents.edge(0.5f))
        assertEquals(0, SliderDetents.edge(0.999f))
    }

    @Test fun aSnappedValueCountsAsItsStepEvenAHairUnder() {
        // The crossfade slider: 0..12 seconds, 11 steps between.
        assertEquals(3, SliderDetents.step(3f / 12f - 1e-6f, steps = 11))
        assertEquals(3, SliderDetents.step(3f / 12f, steps = 11))
        assertEquals(0, SliderDetents.step(0f, steps = 11))
        assertEquals(12, SliderDetents.step(1f, steps = 11))
    }

    @Test fun eachStepOfTheSpeedSliderIsDistinct() {
        // 0.5x..2.0x in 0.05x steps: 29 steps between the ends.
        val seen = (0..30).map { SliderDetents.step(it / 30f, steps = 29) }
        assertEquals((0..30).toList(), seen)
    }
}
