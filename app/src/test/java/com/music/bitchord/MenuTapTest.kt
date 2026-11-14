package com.music.bitchord

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import com.music.bitchord.ui.components.tapStillHolds
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MenuTapTest {
    private val row = IntSize(600, 120)
    private val slop = 24f
    private val down = Offset(300f, 60f)

    @Test
    fun aFingerThatStaysPutIsATap() {
        assertTrue(tapStillHolds(down, down, slop, row))
        assertTrue(tapStillHolds(down, Offset(310f, 70f), slop, row))
    }

    @Test
    fun aFingerThatTravelsIsTheListsToScroll() {
        assertFalse(tapStillHolds(down, Offset(300f, 90f), slop, row))
        assertFalse(tapStillHolds(down, Offset(330f, 60f), slop, row))
    }

    @Test
    fun aFingerThatLeavesTheRowIsNotATapOnIt() {
        val nearEdge = Offset(300f, 115f)
        assertTrue(tapStillHolds(nearEdge, Offset(300f, 120f), slop, row))
        assertFalse(tapStillHolds(nearEdge, Offset(300f, 125f), slop, row))
        assertFalse(tapStillHolds(Offset(5f, 60f), Offset(-3f, 60f), slop, row))
    }
}
