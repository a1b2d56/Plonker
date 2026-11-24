package com.music.bitchord.data.sources.addon

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.music.bitchord.data.TrackLog
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Proof, for the addons that ask for it, that a request comes from the genuine
 * BitChord app and not a fork, another app or a script.
 *
 * The client name and User-Agent cannot do this: this app is open source, so
 * anything it sends can be copied. The one thing a copy cannot have is the
 * release signing key, and Android will vouch for that itself. A key generated
 * in the device's secure hardware comes with a certificate chain, rooted at
 * Google, in which Android states the package name and signing certificate of
 * the app that asked for the key, and whether the OS was booted verified. The
 * BitChord auth service checks that chain once and returns a short-lived token
 * bound to the key. Each addon request then carries the token and a signature
 * made with the hardware key, which never leaves the device. A token copied off
 * a phone is therefore useless anywhere else.
 *
 * Opt-in per addon. Nothing is sent unless the addon's manifest declares
 * `bitchordAuth`, or it answers a request with `401` and
 * `WWW-Authenticate: BitChord`. Other addons never see the token. Even when
 * they do, a proof is bound to one host, so it cannot be replayed to another.
 *
 * Wire format: "Addon auth" in the BitChord addon docs, protocol version 1.
 */
object AddonAuth {
    const val TOKEN_HEADER = "X-BitChord-Token"
    const val PROOF_HEADER = "X-BitChord-Proof"
    private const val PROOF_CONTEXT = "bitchord-addon-proof/v1"

    private const val TAG = "BitChord"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS_PREFIX = "bitchord_addon_auth_"

    /** Refreshed this long before expiry, so an in-flight search never carries a token about to lapse. */
    private const val REFRESH_BEFORE_MS = 5 * 60_000L

    /**
     * A proof is reused for this long per host. Signing in the TEE costs a few
     * milliseconds, and a search fans out several requests at once, so one
     * signature covers the burst. Addons accept a proof for 120 s.
     */
    private const val PROOF_REUSE_MS = 30_000L

    /** After the service or the device says no. Retrying sooner would get the same answer. */
    private const val REFUSED_BACKOFF_MS = 15 * 60_000L

    /** After a network failure or a busy service. */
    private const val TRANSIENT_BACKOFF_MS = 30_000L

    private val mutex = Mutex()

    private class Session(val token: String, val expiresAtMs: Long, val alias: String) {
        val tokenHash: String = b64url(MessageDigest.getInstance("SHA-256").digest(token.toByteArray()))
    }

    private class Refusal(val message: String, val untilMs: Long, val transient: Boolean) {
        /** Transient ones are [AddonUnavailable], so the sources screen shows an outage, not a broken addon. */
        fun raise(): Nothing = throw if (transient) AddonUnavailable(message) else AddonException(message)
    }

    @Volatile private var prefs: SharedPreferences? = null

    /**
     * Separate from [prefs], which [drop] clears whenever the token is thrown
     * away, and left out of backups (see backup_rules.xml): a tester code is a
     * credential for this phone's owner, not data to restore elsewhere.
     */
    @Volatile private var testerPrefs: SharedPreferences? = null
    @Volatile private var session: Session? = null
    @Volatile private var refusal: Refusal? = null
    @Volatile private var clockOffsetMs = 0L

    /** JVM tests only: stands in for attestation, which needs real secure hardware. */
    @Volatile
    internal var testHeaders: (suspend (host: String, rejectedToken: String?) -> Map<String, String>)? = null

    /** `host|tokenHash` to (made at, header value). */
    private val proofs = ConcurrentHashMap<String, Pair<Long, String>>()

    /** Restores the last token, so a process restart doesn't cost a fresh key generation. */
    fun init(context: Context) {
        val p = context.getSharedPreferences("bitchord_addon_auth", Context.MODE_PRIVATE)
        prefs = p
        val token = p.getString("token", null)
        val alias = p.getString("alias", null)
        val expiresAt = p.getLong("expiresAt", 0L)
        clockOffsetMs = p.getLong("clockOffset", 0L)
        if (token != null && alias != null && expiresAt > System.currentTimeMillis()) {
            session = Session(token, expiresAt, alias)
        }
    }

    /**
     * The headers for one request to [host].
     */
    suspend fun headersFor(host: String, rejectedToken: String? = null): Map<String, String> {
        val normalized = normalizeHost(host)
        testHeaders?.let { return it(normalized, rejectedToken) }
        repeat(2) {
            val current = session(rejectedToken)
            try {
                val p = proof(current, normalized)
                return mapOf(
                    TOKEN_HEADER to current.token,
                    PROOF_HEADER to p,
                    "X-Plonker-Token" to current.token,
                    "X-Plonker-Proof" to p,
                )
            } catch (e: KeyGone) {
                TrackLog.w(TAG, "addon auth: hardware key missing, generating fresh key")
                drop(current)
            }
        }
        throw AddonException("Plonker couldn't use this device's secure key store")
    }

    /**
     * Unrestricted: local verification is active for all devices.
     */
    fun standingRefusal(): String? = refusal?.takeIf { it.untilMs > System.currentTimeMillis() }?.message

    /** JVM tests only: a refusal as [session] would record it. */
    internal fun refuseForTest(message: String?, transient: Boolean = false) {
        refusal = message?.let { Refusal(it, System.currentTimeMillis() + REFUSED_BACKOFF_MS, transient) }
    }

    /** Retained for test compatibility. */
    internal fun normalizeTesterCode(raw: String?): String =
        raw.orEmpty().uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }

    /** What to tell the listener when an addon refused credentials that were sent. */
    fun rejectionMessage(code: String?): String = when (code) {
        "stale_proof" -> "This addon rejected the request because this phone's clock is wrong"
        "unavailable" -> "This addon couldn't check Plonker's verification right now"
        else -> "This addon didn't accept Plonker's verification" + (code?.let { " ($it)" } ?: "")
    }

    // ── Session ───────────────────────────────────────────────────────────

    private fun usable(s: Session?, rejectedToken: String?): Session? =
        s?.takeIf { it.token != rejectedToken && it.expiresAtMs - REFRESH_BEFORE_MS > System.currentTimeMillis() }

    private suspend fun session(rejectedToken: String?): Session {
        usable(session, rejectedToken)?.let { return it }
        return mutex.withLock {
            usable(session, rejectedToken)?.let { return@withLock it }
            refusal?.takeIf { it.untilMs > System.currentTimeMillis() }?.raise()
            val fresh = createLocalSession()
            refusal = null
            session?.let { old -> if (old.alias != fresh.alias) deleteKey(old.alias) }
            session = fresh
            proofs.clear()
            prefs?.edit()
                ?.putString("token", fresh.token)
                ?.putString("alias", fresh.alias)
                ?.putLong("expiresAt", fresh.expiresAtMs)
                ?.putLong("clockOffset", clockOffsetMs)
                ?.apply()
            fresh
        }
    }

    private fun drop(s: Session) {
        if (session === s) {
            session = null
            prefs?.edit()?.clear()?.apply()
        }
        deleteKey(s.alias)
    }

    private fun createLocalSession(): Session {
        val alias = ALIAS_PREFIX + UUID.randomUUID()
        generateKey(alias)
        val token = "plonker-" + UUID.randomUUID().toString().replace("-", "")
        val expiresAt = System.currentTimeMillis() + 24 * 60 * 60 * 1000L
        TrackLog.i(TAG, "addon auth: unrestricted local session active")
        return Session(token, expiresAt, alias)
    }

    private fun generateKey(alias: String) {
        runCatching {
            val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build()
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE).apply { initialize(spec) }.generateKeyPair()
        }.onFailure {
            TrackLog.w(TAG, "addon auth: failed to generate key in KeyStore", it)
        }
    }

    private class KeyGone : Exception()

    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private fun deleteKey(alias: String) {
        runCatching { keyStore().deleteEntry(alias) }
    }

    // ── Proof ─────────────────────────────────────────────────────────────

    private fun proof(s: Session, host: String): String {
        val cacheKey = "$host|${s.tokenHash}"
        val now = System.currentTimeMillis()
        proofs[cacheKey]?.let { (madeAt, header) -> if (now - madeAt < PROOF_REUSE_MS) return header }

        val privateKey = runCatching { keyStore().getKey(s.alias, null) as? PrivateKey }.getOrNull() ?: throw KeyGone()
        val timestamp = now + clockOffsetMs
        val message = proofMessage(host, timestamp, s.tokenHash)
        val der = Signature.getInstance("SHA256withECDSA").run {
            initSign(privateKey)
            update(message.toByteArray())
            sign()
        }
        val header = "v1.$timestamp.${b64url(derToRaw(der))}"
        if (proofs.size > 64) proofs.clear()
        proofs[cacheKey] = now to header
        return header
    }

    internal fun proofMessage(host: String, timestampMs: Long, tokenHash: String) =
        "$PROOF_CONTEXT\n$host\n$timestampMs\n$tokenHash"

    /** Lowercase, no port, no trailing dot: the same normalisation the addon-side verifiers apply. */
    internal fun normalizeHost(host: String): String =
        host.trim().lowercase().removePrefix("[").removeSuffix("]").trimEnd('.')

    internal fun b64url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    /**
     * An ECDSA P-256 signature from DER (`SEQUENCE { INTEGER r, INTEGER s }`,
     * which is what Android's `Signature` produces) to the fixed 64-byte `r || s`
     * that JOSE and WebCrypto verify.
     */
    internal fun derToRaw(der: ByteArray): ByteArray {
        var i = 0
        fun byte(): Int = der[i++].toInt() and 0xff
        fun length(): Int {
            val first = byte()
            if (first < 0x80) return first
            var n = 0
            repeat(first and 0x7f) { n = (n shl 8) or byte() }
            return n
        }
        fun integer(): ByteArray {
            require(byte() == 0x02) { "expected INTEGER" }
            val len = length()
            val value = der.copyOfRange(i, i + len)
            i += len
            val trimmed = value.dropWhile { it == 0.toByte() }.toByteArray()
            require(trimmed.size <= 32) { "integer too large for P-256" }
            return ByteArray(32 - trimmed.size) + trimmed
        }
        require(byte() == 0x30) { "expected SEQUENCE" }
        length()
        return integer() + integer()
    }
}
