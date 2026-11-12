package com.music.bitchord.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/** Where a USB drive's music is looked for, given the app's own folder on it. */
class TvUsbMusicTest {

    @Test
    fun `the volume is the folder that holds Android, not Android's data folder`() {
        assertEquals(
            File("/storage/1A2B-3C4D"),
            TvUsbMusic.volumeRootOf(File("/storage/1A2B-3C4D/Android/data/com.app.plonker.tv/files")),
        )
        assertEquals(
            File("/storage/emulated/0"),
            TvUsbMusic.volumeRootOf(File("/storage/emulated/0/Android/data/com.app.plonker.tv/files")),
        )
        assertEquals(
            File("/mnt/media_rw/usb"),
            TvUsbMusic.volumeRootOf(File("/mnt/media_rw/usb/Android/data/com.app.plonker.tv/files")),
        )
    }

    @Test
    fun `a folder that is under no Android directory has no volume to scan`() {
        assertNull(TvUsbMusic.volumeRootOf(File("/data/user/0/com.app.plonker.tv/files")))
    }
}
