package com.music.bitchord.playback

import android.net.TestUri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

class MusicLinkTest {

    @Before
    fun setUp() {
        MusicLink.handled()
    }

    @Test
    fun `LinkRequest LocalFiles holds list of URIs`() {
        val uri1 = TestUri("content://media/1")
        val uri2 = TestUri("content://media/2")
        val request = LinkRequest.LocalFiles(listOf(uri1, uri2))

        assertEquals(2, request.uris.size)
        assertSame(uri1, request.uris[0])
        assertSame(uri2, request.uris[1])
    }

    @Test
    fun `LinkRequest LocalFile holds single URI`() {
        val uri = TestUri("content://media/42")
        val request = LinkRequest.LocalFile(uri)

        assertSame(uri, request.uri)
    }

    @Test
    fun `handled resets pending request to null`() {
        MusicLink.handled()
        assertNull(MusicLink.pending.value)
    }

    @Test
    fun `firstUrl extracts first http or https link from shared text`() {
        assertEquals("https://music.youtube.com/watch?v=123", MusicLink.firstUrl("Check out this song: https://music.youtube.com/watch?v=123"))
        assertEquals("http://youtu.be/abc", MusicLink.firstUrl("http://youtu.be/abc\nAwesome song!"))
        assertNull(MusicLink.firstUrl("No links here at all"))
    }
}
