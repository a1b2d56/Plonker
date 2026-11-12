package com.music.bitchord.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** What a recording's answer is remembered under. */
class ShazamCacheKeyTest {

    @Test
    fun `two recordings with the same 32-bit hash are not each other's answer`() {
        // "Aa" and "BB" are the classic pair: String.hashCode() gives 2112 for both.
        val one = "data:audio/vnd.shazam.sig;base64,AaAa"
        val other = "data:audio/vnd.shazam.sig;base64,BBBB"
        assertEquals(one.hashCode(), other.hashCode())
        assertNotEquals(Shazam.generateCacheKey(one), Shazam.generateCacheKey(other))
    }

    @Test
    fun `the same recording is found again`() {
        val signature = "data:audio/vnd.shazam.sig;base64,c2lnbmF0dXJl"
        assertEquals(Shazam.generateCacheKey(signature), Shazam.generateCacheKey(signature))
        assertEquals(64, Shazam.generateCacheKey(signature).length)
    }
}
