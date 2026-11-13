package com.music.bitchord.data.settings

import android.net.Network
import java.net.InetSocketAddress

/**
 * Whether a connected network can be streamed over.
 *
 * The system's own answer is `NET_CAPABILITY_VALIDATED`, set once its probe
 * has fetched a page from Google. That is the right answer almost everywhere,
 * and the wrong one on a network where that one probe is blocked while
 * everything else works: a filtered office or campus network, some regional
 * ISPs, a Wi-Fi the owner has marked "no internet check". There the bit is
 * never set, and the app sat in offline mode on a working connection.
 *
 * So an unvalidated network gets a second opinion: one TCP connection to the
 * host the app streams from, made over that network. A captive portal is still
 * offline whatever the connection says, because a portal answers every
 * connection itself.
 */
internal object Reachability {

    /** The host a stream starts from; reaching it is what "online" means to this app. */
    private const val HOST = "music.youtube.com"
    private const val PORT = 443
    private const val CONNECT_TIMEOUT_MS = 3_000

    /** How long an unreachable verdict stands before the network is asked again. */
    const val RETRY_MS = 15_000L

    /**
     * The verdict. [reachable] is the probe's answer for this network, or null
     * while it has none, which reads as "not yet" rather than as a yes.
     */
    fun online(internet: Boolean, validated: Boolean, captivePortal: Boolean, reachable: Boolean?): Boolean =
        internet && !captivePortal && (validated || reachable == true)

    /** Whether [online] needs the probe to decide: a network the system has not vouched for. */
    fun needsProbe(internet: Boolean, validated: Boolean, captivePortal: Boolean): Boolean =
        internet && !validated && !captivePortal

    /** One connection to [HOST] over [network]. Blocking; call off the main thread. */
    fun probe(network: Network): Boolean = runCatching {
        val address = network.getAllByName(HOST).firstOrNull() ?: return false
        network.socketFactory.createSocket().use { socket ->
            socket.connect(InetSocketAddress(address, PORT), CONNECT_TIMEOUT_MS)
        }
        true
    }.getOrDefault(false)
}
