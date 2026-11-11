package com.music.bitchord.data

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.spotify.LocalPlaylist
import com.music.bitchord.data.spotify.LocalPlaylistStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What the two on-device playlist stores make of what they find on disk. The
 * rule both follow: a row that can't be read costs that row, never the rest.
 */
class PlaylistStoresTest {

    private fun song(id: String, title: String, album: String? = null) =
        Song(videoId = id, title = title, artist = "Artist", thumbnailUrl = null, albumName = album)

    // ---- Imported (local) playlists ----------------------------------------

    @Test
    fun `local playlists come back as they were saved`() {
        val saved = listOf(
            LocalPlaylist("sp_local_2", "Second", listOf(song("b", "B", album = "Album"))),
            LocalPlaylist("sp_local_1", "First", listOf(song("a", "A"), song("c", "C"))),
        )
        assertEquals(saved, LocalPlaylistStore.parse(LocalPlaylistStore.encode(saved)))
    }

    @Test
    fun `one unreadable local playlist does not take the others with it`() {
        val raw = """
            [{"id":"sp_local_1","title":"Kept","songs":[{"videoId":"a","title":"A","artist":"Artist"}]},
             {"title":"No id","songs":[]},
             "not a playlist",
             {"id":"sp_local_3","title":"Also kept","songs":[]}]
        """.trimIndent()
        val parsed = LocalPlaylistStore.parse(raw)
        assertEquals(listOf("sp_local_1", "sp_local_3"), parsed?.map { it.id })
        assertEquals(listOf("a"), parsed?.first()?.songs?.map { it.videoId })
    }

    @Test
    fun `a local playlist with no song list is an empty playlist`() {
        val parsed = LocalPlaylistStore.parse("""[{"id":"sp_local_1","title":"Empty"}]""")
        assertEquals(emptyList<Song>(), parsed?.single()?.songs)
    }

    @Test
    fun `an optional field an old build wrote as the text null is absent`() {
        val raw = """[{"id":"x","title":"T","songs":[
            {"videoId":"a","title":"A","artist":"B","albumName":"null","artistId":null,"thumbnailUrl":""}]}]"""
        val song = LocalPlaylistStore.parse(raw)!!.single().songs.single()
        assertNull(song.albumName)
        assertNull(song.artistId)
        assertNull(song.thumbnailUrl)
    }

    @Test
    fun `a document that is not a list at all is reported, not read as empty`() {
        assertNull(LocalPlaylistStore.parse("{\"id\":\"x\"}"))
        assertNull(LocalPlaylistStore.parse("[{\"id\":"))
        assertNotNull(LocalPlaylistStore.parse("[]"))
    }

    // ---- Offline playlists -------------------------------------------------

    @Test
    fun `one unreadable offline playlist does not take Liked with it`() {
        val raw = """
            [{"id":"liked","title":"Liked","songs":[
                {"videoId":"v","title":"A","artist":"B","localUri":"content://media/1"},
                {"title":"no file"},
                7]},
             {"title":"No id","songs":[]},
             {"id":"p1","title":"Mine"}]
        """.trimIndent()
        val parsed = OfflinePlaylists.parse(raw)
        assertEquals(listOf("liked", "p1"), parsed?.map { it.id })
        assertEquals(listOf("content://media/1"), parsed?.first()?.songs?.map { it.localUri })
        assertEquals(emptyList<Song>(), parsed?.last()?.songs)
    }

    @Test
    fun `offline playlists that are not a list at all are reported, not read as empty`() {
        assertNull(OfflinePlaylists.parse("not json"))
        assertNotNull(OfflinePlaylists.parse("[]"))
    }
}
