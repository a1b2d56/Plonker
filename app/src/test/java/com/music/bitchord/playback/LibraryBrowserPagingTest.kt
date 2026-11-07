package com.music.bitchord.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryBrowserPagingTest {

    private val items = (0 until 120).toList()

    @Test
    fun `pages count from zero and follow on from each other`() {
        assertEquals((0 until 20).toList(), LibraryBrowser.paginate(items, page = 0, pageSize = 20))
        assertEquals((20 until 40).toList(), LibraryBrowser.paginate(items, page = 1, pageSize = 20))
    }

    @Test
    fun `the last page is short and the one after it empty`() {
        assertEquals((100 until 120).toList(), LibraryBrowser.paginate(items, page = 2, pageSize = 50))
        assertTrue(LibraryBrowser.paginate(items, page = 3, pageSize = 50).isEmpty())
    }

    @Test
    fun `a browser asking for more than the cap gets the cap`() {
        val page = LibraryBrowser.paginate(items, page = 0, pageSize = 1_000)
        assertEquals(LibraryBrowser.MAX_AUTO_PAGE_SIZE, page.size)
    }

    @Test
    fun `nonsense requests get nothing rather than a crash`() {
        assertTrue(LibraryBrowser.paginate(items, page = -1, pageSize = 20).isEmpty())
        assertTrue(LibraryBrowser.paginate(items, page = 0, pageSize = 0).isEmpty())
        assertTrue(LibraryBrowser.paginate(emptyList<Int>(), page = 0, pageSize = 20).isEmpty())
        // A page index far past the end must not overflow into a valid range.
        assertTrue(LibraryBrowser.paginate(items, page = Int.MAX_VALUE, pageSize = 50).isEmpty())
    }
}
