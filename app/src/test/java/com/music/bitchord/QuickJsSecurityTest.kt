package com.music.bitchord

import com.music.bitchord.data.sources.addon.AddonAuth
import com.music.bitchord.data.sources.module.QuickJsExecutor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class QuickJsSecurityTest {

    @Test
    fun `blocks loopback and private IPv4 addresses from module access`() {
        assertTrue(QuickJsExecutor.isPrivateOrLocalAddress("127.0.0.1"))
        assertTrue(QuickJsExecutor.isPrivateOrLocalAddress("127.10.20.30"))
        assertTrue(QuickJsExecutor.isPrivateOrLocalAddress("10.0.0.1"))
        assertTrue(QuickJsExecutor.isPrivateOrLocalAddress("10.255.255.255"))
        assertTrue(QuickJsExecutor.isPrivateOrLocalAddress("192.168.1.1"))
        assertTrue(QuickJsExecutor.isPrivateOrLocalAddress("192.168.100.200"))
        assertTrue(QuickJsExecutor.isPrivateOrLocalAddress("172.16.0.1"))
        assertTrue(QuickJsExecutor.isPrivateOrLocalAddress("172.31.255.255"))
        assertTrue(QuickJsExecutor.isPrivateOrLocalAddress("169.254.169.254"))
        assertTrue(QuickJsExecutor.isPrivateOrLocalAddress("0.0.0.0"))
        assertTrue(QuickJsExecutor.isPrivateOrLocalAddress("localhost"))
        assertTrue(QuickJsExecutor.isPrivateOrLocalAddress("service.localhost"))
    }

    @Test
    fun `blocks loopback and link-local IPv6 addresses from module access`() {
        val loopbackV6 = InetAddress.getByName("::1")
        assertTrue(QuickJsExecutor.isPrivateOrLocalAddress(loopbackV6))

        val v4MappedV6 = InetAddress.getByName("::ffff:127.0.0.1")
        assertTrue(QuickJsExecutor.isPrivateOrLocalAddress(v4MappedV6))

        val v4MappedPrivate = InetAddress.getByName("::ffff:192.168.1.1")
        assertTrue(QuickJsExecutor.isPrivateOrLocalAddress(v4MappedPrivate))
    }

    @Test
    fun `allows public external IP addresses`() {
        assertFalse(QuickJsExecutor.isPrivateOrLocalAddress("8.8.8.8"))
        assertFalse(QuickJsExecutor.isPrivateOrLocalAddress("1.1.1.1"))
        assertFalse(QuickJsExecutor.isPrivateOrLocalAddress(InetAddress.getByName("8.8.8.8")))
        assertFalse(QuickJsExecutor.isPrivateOrLocalAddress(InetAddress.getByName("1.1.1.1")))
    }

    @Test
    fun `addon auth generates unrestricted local credentials without remote attestation`() = runBlocking {
        assertNull(AddonAuth.standingRefusal())
        AddonAuth.testHeaders = { host, rejected ->
            mapOf(
                AddonAuth.TOKEN_HEADER to "plonker-token-123",
                AddonAuth.PROOF_HEADER to "v1.123.proof",
                "X-Plonker-Token" to "plonker-token-123",
                "X-Plonker-Proof" to "v1.123.proof",
            )
        }
        val headers = AddonAuth.headersFor("addon.example.com")
        val token = headers[AddonAuth.TOKEN_HEADER]
        val proof = headers[AddonAuth.PROOF_HEADER]
        assertNotNull(token)
        assertNotNull(proof)
        assertTrue(token!!.startsWith("plonker-"))
        assertTrue(proof!!.startsWith("v1."))
        assertEquals(token, headers["X-Plonker-Token"])
        assertEquals(proof, headers["X-Plonker-Proof"])
        AddonAuth.testHeaders = null
    }
}
