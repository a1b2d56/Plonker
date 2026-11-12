package com.music.bitchord.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** A saved package is only ever replaced by a finished one. */
class OfflinePackageTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun savedPackage(): File = File(folder.root, "1a2b3c").apply {
        mkdirs()
        File(this, "playlist.m3u8").writeText("old playlist")
        File(this, "segment-00000.m4s").writeText("old audio")
    }

    private fun staging(root: File) = File(root.parentFile, root.name + OfflinePackage.STAGING_SUFFIX)

    @Test
    fun `a download that fails leaves the copy saved before it`() {
        val root = savedPackage()
        try {
            OfflinePackage.replace(root) { staging ->
                File(staging, "segment-00000.m4s").writeText("half of the new audio")
                error("HLS fragment failed (HTTP 503)")
            }
            fail("the failure should reach the caller")
        } catch (e: IllegalStateException) {
            assertEquals("HLS fragment failed (HTTP 503)", e.message)
        }
        assertEquals("old playlist", File(root, "playlist.m3u8").readText())
        assertEquals("old audio", File(root, "segment-00000.m4s").readText())
        assertFalse(staging(root).exists())
    }

    @Test
    fun `a finished download takes the old copy's place whole`() {
        val root = savedPackage()
        OfflinePackage.replace(root) { staging ->
            File(staging, "playlist.m3u8").writeText("new playlist")
        }
        assertEquals("new playlist", File(root, "playlist.m3u8").readText())
        // Nothing of the old package is left mixed in with the new one.
        assertFalse(File(root, "segment-00000.m4s").exists())
        assertFalse(staging(root).exists())
    }

    @Test
    fun `a first download needs nothing to be there already`() {
        val root = File(File(folder.root, "offline-hls"), "9f8e7d")
        OfflinePackage.replace(root) { staging ->
            File(staging, "playlist.m3u8").writeText("playlist")
        }
        assertTrue(File(root, "playlist.m3u8").isFile)
    }

    @Test
    fun `what an interrupted download left behind is cleared before the next`() {
        val root = savedPackage()
        staging(root).apply { mkdirs(); File(this, "segment-00007.m4s").writeText("stale") }
        OfflinePackage.replace(root) { staging ->
            assertEquals(emptyList<String>(), staging.list()?.toList())
            File(staging, "playlist.m3u8").writeText("new playlist")
        }
        assertEquals(listOf("playlist.m3u8"), root.list()?.toList())
    }
}
