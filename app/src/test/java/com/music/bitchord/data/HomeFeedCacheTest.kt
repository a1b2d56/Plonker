package com.music.bitchord.data

import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.ShelfLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HomeFeedCacheTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val now = 1_800_000_000_000L

    private val snapshot = HomeFeedCache.Snapshot(
        shelves = listOf(
            HomeShelf(
                title = "Quick picks",
                subtitle = "Start radio from a song",
                items = listOf(
                    ShelfItem("Song", "Artist", "https://img/w120-h120", videoId = "v1", browseId = null, isVideo = false),
                    ShelfItem("Album", "Artist · 2024", null, videoId = null, browseId = "MPRE1", isVideo = false),
                ),
                moreBrowseId = "FEmusic_more",
                layout = ShelfLayout.LIST,
            ),
            HomeShelf(
                title = "Popular",
                items = listOf(ShelfItem("Hit", "Band", null, null, "VL1", isVideo = true)),
                layout = ShelfLayout.VIDEO,
            ),
        ),
    )

    @Test
    fun aKeptPageComesBackAsItWasKept() {
        val text = HomeFeedCache.encode(snapshot, "account:profile", now)
        assertEquals(snapshot, HomeFeedCache.decode(text, "account:profile", now + 60_000))
    }

    @Test
    fun anotherListenersPageIsNeverDrawn() {
        val text = HomeFeedCache.encode(snapshot, "account:profile", now)
        assertNull(HomeFeedCache.decode(text, "other:profile", now))
        assertNull(HomeFeedCache.decode(text, null, now))
    }

    @Test
    fun theListenerIsNotWrittenDown() {
        assertFalse(HomeFeedCache.encode(snapshot, "account:profile", now).contains("account:profile"))
    }

    @Test
    fun aPageOlderThanADayIsNotDrawn() {
        val text = HomeFeedCache.encode(snapshot, null, now)
        assertEquals(snapshot, HomeFeedCache.decode(text, null, now + HomeFeedCache.MAX_AGE_MS - 1))
        assertNull(HomeFeedCache.decode(text, null, now + HomeFeedCache.MAX_AGE_MS))
    }

    @Test
    fun savesAndLoadsPerCatalogue() {
        val folder = tempFolder.root
        HomeFeedCache.save(folder, "YOUTUBE", null, snapshot, now)
        assertEquals(snapshot, HomeFeedCache.load(folder, "YOUTUBE", null, now))
        assertNull(HomeFeedCache.load(folder, "SPOTIFY", null, now))
    }
}
