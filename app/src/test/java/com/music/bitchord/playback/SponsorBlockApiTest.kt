package com.music.bitchord.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What is sent to the SponsorBlock server, and what is made of its answer. */
class SponsorBlockApiTest {

    @Test
    fun `only a YouTube video id is ever looked up`() {
        assertTrue(SponsorBlockApi.isYouTubeVideoId("fHiGbolFFGw"))
        assertTrue(SponsorBlockApi.isYouTubeVideoId("a-b_c-D_E12"))
        assertFalse(SponsorBlockApi.isYouTubeVideoId(null))
        assertFalse(SponsorBlockApi.isYouTubeVideoId(""))
        assertFalse(SponsorBlockApi.isYouTubeVideoId("content://media/external/audio/media/42"))
        assertFalse(SponsorBlockApi.isYouTubeVideoId("/storage/emulated/0/Music/song.flac"))
        assertFalse(SponsorBlockApi.isYouTubeVideoId("src:hires:12"))
        assertFalse(SponsorBlockApi.isYouTubeVideoId("fHiGbolFFGw1"))
        assertFalse(SponsorBlockApi.isYouTubeVideoId("fHiGbol FGw"))
    }

    @Test
    fun `an answer is remembered for the categories it was asked with`() {
        val all = SponsorBlockApi.cacheKey("fHiGbolFFGw", listOf("sponsor", "intro", "outro"))
        assertEquals(all, SponsorBlockApi.cacheKey("fHiGbolFFGw", listOf("outro", "sponsor", "intro")))
        assertNotEquals(all, SponsorBlockApi.cacheKey("fHiGbolFFGw", listOf("sponsor")))
        assertNotEquals(all, SponsorBlockApi.cacheKey("aaaaaaaaaaa", listOf("sponsor", "intro", "outro")))
    }

    @Test
    fun `segments come back earliest first, in milliseconds`() {
        val body = """
            [{"category":"outro","actionType":"skip","segment":[200.5,215.0],"UUID":"b"},
             {"category":"sponsor","actionType":"skip","segment":[12.25,30.0],"UUID":"a"}]
        """.trimIndent()
        val segments = SponsorBlockApi.parseSegments(body)
        assertEquals(listOf("a", "b"), segments.map { it.uuid })
        assertEquals(12_250L, segments[0].startMs)
        assertEquals(30_000L, segments[0].endMs)
        assertEquals("sponsor", segments[0].category)
    }

    @Test
    fun `a malformed entry is skipped and the rest are kept`() {
        val body = """
            [{"category":"sponsor","segment":[40.0,10.0],"UUID":"backwards"},
             {"category":"sponsor","segment":[5.0],"UUID":"short"},
             {"category":"sponsor","UUID":"none"},
             "not an object",
             {"category":"intro","segment":[0.0,8.0],"UUID":"ok"}]
        """.trimIndent()
        assertEquals(listOf("ok"), SponsorBlockApi.parseSegments(body).map { it.uuid })
    }
}
