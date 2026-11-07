package com.music.bitchord

import com.music.bitchord.ui.components.stableRowKeys
import org.junit.Assert.assertEquals
import org.junit.Test

class StableRowKeysTest {

    @Test fun aRepeatedSongStillGetsKeysOfItsOwn() {
        val keys = stableRowKeys(listOf("a", "b", "a", "a"))
        assertEquals(listOf("a", "b", "a#1", "a#2"), keys)
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test fun removingARowLeavesEveryOtherKeyAlone() {
        val before = stableRowKeys(listOf("a", "b", "c", "d"))
        val after = stableRowKeys(listOf("a", "c", "d"))
        assertEquals(before - "b", after)
    }

    @Test fun reorderingCarriesEachKeyWithItsRow() {
        val before = stableRowKeys(listOf("a", "b", "c"))
        val after = stableRowKeys(listOf("c", "a", "b"))
        assertEquals(before.toSet(), after.toSet())
        assertEquals("c", after.first())
    }

    @Test fun anInsertedRowIsTheOnlyNewKey() {
        val before = stableRowKeys(listOf("a", "b"))
        val after = stableRowKeys(listOf("a", "x", "b"))
        assertEquals(setOf("x"), after.toSet() - before.toSet())
    }
}
