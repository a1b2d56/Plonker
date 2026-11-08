package com.music.bitchord

import androidx.compose.animation.core.SpringSpec
import com.music.bitchord.ui.player.lyrics.LYRICS_RESUME_MS
import com.music.bitchord.ui.player.lyrics.LyricJumpSpring
import org.junit.Assert.assertEquals
import org.junit.Test

/** The feel of a jump back to the sung line, pinned. */
class LyricsJumpTest {

    @Test
    fun theHandLeavesTheListForFourSecondsBeforeItFollowsAgain() {
        assertEquals(4_000, LYRICS_RESUME_MS)
    }

    @Test
    fun aJumpTravelsOnTheIosScrollSpring() {
        val spring = LyricJumpSpring as SpringSpec<Float>
        assertEquals(0.85f, spring.dampingRatio)
        assertEquals(350f, spring.stiffness)
    }
}
