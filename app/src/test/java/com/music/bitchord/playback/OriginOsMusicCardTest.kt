package com.music.bitchord.playback

import androidx.media3.common.Player
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OriginOsMusicCardTest {

    private val song = Song(
        videoId = "abcdefghijk",
        title = "Song",
        artist = "Artist",
        thumbnailUrl = null,
    )

    // --- loop mode ---------------------------------------------------------------

    @Test
    fun shuffleShowsAsRandomWhateverTheRepeatMode() {
        assertEquals(
            OriginOsMusicCard.LOOP_MODE_RANDOM,
            OriginOsMusicCard.loopModeOf(shuffleEnabled = true, repeatMode = Player.REPEAT_MODE_ONE),
        )
    }

    @Test
    fun repeatOneShowsAsSingle() {
        assertEquals(
            OriginOsMusicCard.LOOP_MODE_SINGLE,
            OriginOsMusicCard.loopModeOf(shuffleEnabled = false, repeatMode = Player.REPEAT_MODE_ONE),
        )
    }

    @Test
    fun repeatAllAndRepeatOffShowAsList() {
        assertEquals(
            OriginOsMusicCard.LOOP_MODE_LIST,
            OriginOsMusicCard.loopModeOf(shuffleEnabled = false, repeatMode = Player.REPEAT_MODE_ALL),
        )
        assertEquals(
            OriginOsMusicCard.LOOP_MODE_LIST,
            OriginOsMusicCard.loopModeOf(shuffleEnabled = false, repeatMode = Player.REPEAT_MODE_OFF),
        )
    }

    @Test
    fun everyLoopModeOfTheCardRoundTrips() {
        listOf(
            OriginOsMusicCard.LOOP_MODE_LIST,
            OriginOsMusicCard.LOOP_MODE_SINGLE,
            OriginOsMusicCard.LOOP_MODE_RANDOM,
        ).forEach { loopMode ->
            val (shuffleEnabled, repeatMode) = OriginOsMusicCard.playModesOf(loopMode)!!
            assertEquals(loopMode, OriginOsMusicCard.loopModeOf(shuffleEnabled, repeatMode))
        }
    }

    @Test
    fun anUnknownLoopModeChangesNothing() {
        assertNull(OriginOsMusicCard.playModesOf(0))
        assertNull(OriginOsMusicCard.playModesOf(4))
    }

    // --- media ids ---------------------------------------------------------------

    @Test
    fun aNewQueueStartingAtTheSameIndexStillChangesTheMediaId() {
        val other = song.copy(title = "Other song", videoId = "kjihgfedcba")
        assertNotEquals(OriginOsMusicCard.mediaIdOf(0, song), OriginOsMusicCard.mediaIdOf(0, other))
    }

    @Test
    fun aMediaIdOfTheCardPlaysTheQueueEntryAtItsIndex() {
        assertEquals("queue:12", OriginOsMusicCard.queueMediaIdOf(OriginOsMusicCard.mediaIdOf(12, song)))
    }

    @Test
    fun otherMediaIdsArePlayedAsTheyAre() {
        assertEquals("queue:3", OriginOsMusicCard.queueMediaIdOf("queue:3"))
        assertEquals("playlist:42", OriginOsMusicCard.queueMediaIdOf("playlist:42"))
        assertEquals("download:7", OriginOsMusicCard.queueMediaIdOf("download:7"))
    }

    // --- lyrics ------------------------------------------------------------------

    @Test
    fun lyricsOfTheLyricsScreenAreMatchedToTheTrack() {
        assertEquals(OriginOsMusicCard.trackKeyOf(song), OriginOsMusicCard.trackKeyOf(song.copy()))
        assertNotEquals(OriginOsMusicCard.trackKeyOf(song), OriginOsMusicCard.trackKeyOf(song.copy(videoId = "kjihgfedcba")))
        assertEquals("Song|Artist", OriginOsMusicCard.trackKeyOf("Song", "Artist", null))
    }

    @Test
    fun syncedLinesBecomeWholeSongLrc() {
        val lines = listOf(
            LyricLine(timeMs = 5_230L, text = "First line"),
            LyricLine(timeMs = 65_004L, text = "Second\nline"),
        )
        assertEquals(
            "[00:05.23]First line\n[01:05.00]Second line",
            OriginOsMusicCard.lrcOf(lines),
        )
    }

    @Test
    fun unsyncedLyricsAreNotSentAsLrc() {
        val plain = listOf(
            LyricLine(timeMs = 0L, text = "a"),
            LyricLine(timeMs = 0L, text = "b"),
        )
        assertNull(OriginOsMusicCard.lrcOf(plain))
        assertNull(OriginOsMusicCard.lrcOf(emptyList()))
    }

    @Test
    fun cardLyricsOnlyOnVivoAndIqoo() {
        assertTrue(OriginOsMusicCard.isSupportedDevice("vivo"))
        assertTrue(OriginOsMusicCard.isSupportedDevice("VIVO"))
        assertTrue(OriginOsMusicCard.isSupportedDevice("iQOO"))
        assertFalse(OriginOsMusicCard.isSupportedDevice("samsung"))
        assertFalse(OriginOsMusicCard.isSupportedDevice("google"))
    }
}
