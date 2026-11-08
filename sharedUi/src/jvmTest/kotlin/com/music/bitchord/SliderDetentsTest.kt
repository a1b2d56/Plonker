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

    @Test fun aDragThatStopsJustShortOfAnEndStillReachesIt() {
        // Where a finger dragged into the end of the bar actually lands.
        assertEquals(-1, SliderDetents.edge(0.008f))
        assertEquals(1, SliderDetents.edge(0.992f))
        assertEquals(-1, SliderDetents.edge(SliderDetents.EDGE_ENTER))
        assertEquals(1, SliderDetents.edge(1f - SliderDetents.EDGE_ENTER))
    }

    @Test fun everywhereBetweenIsOnePlace() {
        assertEquals(0, SliderDetents.edge(0.02f))
        assertEquals(0, SliderDetents.edge(0.5f))
        assertEquals(0, SliderDetents.edge(0.98f))
    }

    @Test fun anEndIsHeldUntilTheDragComesBackOutOfIt() {
        // Resting on the threshold: inside it stays at the end, so no second buzz.
        assertEquals(1, SliderDetents.edge(0.982f, held = 1))
        assertEquals(-1, SliderDetents.edge(0.02f, held = -1))
        // Back past the release distance it has left.
        assertEquals(0, SliderDetents.edge(0.96f, held = 1))
        assertEquals(0, SliderDetents.edge(0.04f, held = -1))
        // The hold only ever keeps an end, never invents the other one.
        assertEquals(0, SliderDetents.edge(0.982f, held = -1))
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
