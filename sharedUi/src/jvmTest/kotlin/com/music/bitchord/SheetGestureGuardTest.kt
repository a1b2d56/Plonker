package com.music.bitchord

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import com.music.bitchord.ui.utils.sheetBoundLeftover
import org.junit.Assert.assertEquals
import org.junit.Test

class SheetGestureGuardTest {
    private val leftover = Offset(0f, -130f)

    @Test
    fun aFingersLeftoverIsKeptFromTheSheet() {
        assertEquals(leftover, sheetBoundLeftover(leftover, NestedScrollSource.UserInput))
    }

    // Taken, it reads to the fling as "still moving", and a fling that has
    // reached the end of its list then runs on with nothing moving.
    @Test
    fun aFlingsLeftoverIsHandedBack() {
        assertEquals(Offset.Zero, sheetBoundLeftover(leftover, NestedScrollSource.SideEffect))
    }
}
