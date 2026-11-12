package com.music.bitchord.data

import com.music.bitchord.ui.tv.auth.TvAuthServer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which of the sign-in server's paths belong to the phone remote. */
class TvAuthServerTest {

    @Test
    fun `the remote's page and its endpoints are the remote's`() {
        listOf("/remote", "/api/status", "/api/action", "/api/search", "/api/play").forEach {
            assertTrue(it, TvAuthServer.isRemotePath(it))
        }
    }

    @Test
    fun `sign-in is not`() {
        listOf("/", "/submit", "/remote-control", "/apis").forEach {
            assertFalse(it, TvAuthServer.isRemotePath(it))
        }
    }

    @Test
    fun `with nothing wired to it the remote is not offered`() {
        assertFalse(TvAuthServer.remoteAvailable)
    }
}
