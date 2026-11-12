package com.music.bitchord.data

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.spotify.LocalPlaylist
import com.music.bitchord.data.spotify.LocalPlaylistStore
import com.music.bitchord.data.stats.Backup
import com.music.bitchord.recognition.RecognitionHistoryEntry
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What a backup file carries beyond settings and listening, and how an older one reads. */
class BackupContentsTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `an identified song survives the trip, apart from its row id`() {
        val entry = RecognitionHistoryEntry(
            id = 42,
            trackKey = "shazam:5551234",
            title = "Paranoid Android",
            artist = "Radiohead",
            album = "OK Computer",
            artworkUrl = "https://img/high.jpg",
            videoId = "fHiGbolFFGw",
            browseId = null,
            recognizedAt = 1_700_000_000_000L,
        )
        assertEquals(entry.copy(id = 0), Backup.Recognised.of(entry).entry())
    }

    @Test
    fun `playlists and identified songs come back out of the file as they went in`() {
        val playlists = listOf(
            LocalPlaylist(
                "sp_local_1",
                "Road trip",
                listOf(Song(videoId = "a", title = "A", artist = "B", thumbnailUrl = null)),
            ),
        )
        val offline = listOf(
            OfflinePlaylist(
                OfflinePlaylists.LIKED_ID,
                "Liked",
                listOf(
                    Song(
                        videoId = "content://media/1",
                        title = "T",
                        artist = "U",
                        thumbnailUrl = null,
                        localUri = "content://media/1",
                    ),
                ),
            ),
        )
        val file = Backup.BackupFile(
            localPlaylists = LocalPlaylistStore.encode(playlists),
            offlinePlaylists = OfflinePlaylists.encode(offline),
            recognised = listOf(Backup.Recognised(trackKey = "name:t|u", title = "T", artist = "U", recognizedAt = 5)),
        )
        val read = json.decodeFromString(
            Backup.BackupFile.serializer(),
            json.encodeToString(Backup.BackupFile.serializer(), file),
        )
        assertEquals(playlists, LocalPlaylistStore.parse(read.localPlaylists!!))
        assertEquals(offline, OfflinePlaylists.parse(read.offlinePlaylists!!))
        assertEquals(file.recognised, read.recognised)
    }

    @Test
    fun `a backup from before playlists were carried has nothing to say about them`() {
        val old = """{"app":"bitchord","version":1,"versionName":"1.9.0","settings":{},"listening":[]}"""
        val read = json.decodeFromString(Backup.BackupFile.serializer(), old)
        // Null, not empty: an import leaves this device's playlists alone.
        assertNull(read.localPlaylists)
        assertNull(read.offlinePlaylists)
        assertNull(read.recognised)
    }
}
