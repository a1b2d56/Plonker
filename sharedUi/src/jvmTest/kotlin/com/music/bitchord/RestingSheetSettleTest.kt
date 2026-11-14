package com.music.bitchord

import androidx.compose.ui.unit.Velocity
import com.music.bitchord.ui.utils.RestingSheetSettle
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RestingSheetSettleTest {
    private var now = 1_000L
    private val settle = RestingSheetSettle { now }

    private fun flingEnds() = runBlocking {
        settle.connection.onPostFling(Velocity.Zero, Velocity.Zero)
    }

    @Test
    fun theSettleAFlingsEndSetsOffInARestingSheetIsTheOne() {
        flingEnds()
        now += 16
        assertTrue(settle.nothingToSettle(atRest = true))
    }

    @Test
    fun onlyThatOne() {
        flingEnds()
        assertTrue(settle.nothingToSettle(atRest = true))
        assertFalse(settle.nothingToSettle(atRest = true))
    }

    @Test
    fun aSheetOffItsAnchorHasSomewhereToGo() {
        flingEnds()
        assertFalse(settle.nothingToSettle(atRest = false))
        // And the fling is spent on it: the next question is not its.
        assertFalse(settle.nothingToSettle(atRest = true))
    }

    @Test
    fun aQuestionWithNoFlingBehindItIsSomebodyElses() {
        assertFalse(settle.nothingToSettle(atRest = true))
        flingEnds()
        now += 500
        assertFalse(settle.nothingToSettle(atRest = true))
    }
}
