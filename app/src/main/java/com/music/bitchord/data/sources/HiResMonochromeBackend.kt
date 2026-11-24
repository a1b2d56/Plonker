package com.music.bitchord.data.sources

import com.music.bitchord.data.Http
import com.music.bitchord.data.TrackLog
import java.io.IOException
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.max

/**
 * Direct-FLAC backend owned by the Hi-Res source — not a source itself.
 *
 * The sealed Tidal/Monochrome source already speaks `tracks.monochrome.st`;
 * this backend gives the same host to Hi-Res, whose entire catalogue and
 * stream path is these direct files. No [SourceKind], no registry row, no
 * UI: the Hi-Res source is the only caller.
 *
 * Search and stream state live here rather than on the source instance:
 * instances are rebuilt when configs change, and an instance memo would
 * strand every search row it minted the moment the registry rebuilds.
 */
object HiResMonochromeBackend {

    const val BASE = "https://tracks.monochrome.st"
    /** The host files resolve under — the key stream refusals are recorded by. */
    const val BASE_HOST = "tracks.monochrome.st"
    private const val TAG = "BitChord"
    private const val PACK_PREFIX = "m:"

    val DEFAULT_INSTANCES = listOf(
        "https://tracks.monochrome.st",
        "https://monochrome.tf",
        "https://lossless.wtf",
        "https://eu-central.monochrome.tf",
        "https://us-west.monochrome.tf",
    )

    @Volatile
    private var activeBase: String = BASE

    /** Test hook: point the backend at a MockWebServer; reset after each case. */
    internal var baseOverride: String? = null
    private val base: String get() = baseOverride?.trimEnd('/') ?: activeBase

    fun getCandidateBases(): List<String> {
        baseOverride?.let { return listOf(it.trimEnd('/')) }
        val userServers = com.music.bitchord.data.settings.AppSettings.monochromeServers.value
            .map { it.trimEnd('/') }
            .filter { it.isNotBlank() }
        return (listOf(activeBase) + userServers + DEFAULT_INSTANCES).distinct()
    }

    /** 5xx backoff: one unreachable origin is routinely gone on retry. */
    private val RETRY_DELAYS_MS = listOf(1_000L, 2_000L, 4_000L)

    /**
     * Uncompressed stereo PCM floors: a FLAC never averages above the PCM it
     * encodes, so a file past the first cannot be 16/44.1 and one past the
     * second cannot be 44.1kHz at all.
     */
    const val CD_PCM_BITRATE = 1_411_200
    const val HIRES_PCM_BITRATE = 2_116_800

    /** Below this a "FLAC" is a transcode in a lossless container: refuse it. */
    const val MIN_GENUINE_KBPS = 300

    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    private val client: OkHttpClient by lazy {
        Http.client.newBuilder()
            .callTimeout(20, TimeUnit.SECONDS)
            .connectTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    @Serializable
    data class SearchResponse(
        @SerialName("tracks") val tracks: List<MonoTrack> = emptyList(),
    )

    /**
     * The catalogue quotes large ids (`"id":"154032361934163968"`) while
     * older rows arrive bare (`"id":777`): one shape per era, both live.
     * A strict `Long` parses the second and throws on the first, which is
     * how the whole direct catalogue read empty in production while every
     * unit test — all written with bare ids — stayed green. The sibling
     * client for this host family ([TidalApi][com.music.bitchord.data.tidal.TidalApi])
     * reads ids as strings for the same reason; this matches it.
     */
    private object FlexibleId : KSerializer<String> {
        override val descriptor: SerialDescriptor =
            PrimitiveSerialDescriptor("FlexibleId", PrimitiveKind.STRING)

        override fun deserialize(decoder: Decoder): String {
            val element = (decoder as? JsonDecoder)?.decodeJsonElement()
            return (element as? JsonPrimitive)?.content.orEmpty()
        }

        override fun serialize(encoder: Encoder, value: String) {
            encoder.encodeString(value)
        }
    }

    @Serializable
    data class MonoTrack(
        @SerialName("id") @Serializable(with = FlexibleId::class) val id: String = "",
        @SerialName("title") val title: String = "",
        @SerialName("artistNames") val artistNames: List<String> = emptyList(),
        /** Usually milliseconds; normalized rather than trusted. */
        @SerialName("duration") val durationMs: Long? = null,
        @SerialName("artwork") val artwork: String? = null,
        @SerialName("albumTitle") val albumTitle: String? = null,
        @SerialName("releaseTitle") val releaseTitle: String? = null,
        @SerialName("isrc") val isrc: String? = null,
        @SerialName("explicit") val explicit: Boolean? = null,
        /** A catalogue entry with no file behind it. */
        @SerialName("playable") val playable: Boolean? = null,
    ) {
        val credits: String
            get() = artistNames.mapNotNull { it.takeIf(String::isNotBlank) }
                .distinct().joinToString(", ").ifBlank { "Unknown artist" }
        val album: String?
            get() = albumTitle?.takeIf { it.isNotBlank() }
                ?: releaseTitle?.takeIf { it.isNotBlank() }
    }

    /** What a Hi-Res search row needs later at queue time, by its packed id. */
    data class Origin(val id: String, val durationMs: Long?)

    private val origins = Collections.synchronizedMap(
        object : LinkedHashMap<String, Origin>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, Origin>) = size > 128
        },
    )

    fun packId(id: String): String = "$PACK_PREFIX$id"

    fun memoizeOrigin(packed: String, origin: Origin) {
        origins[packed] = origin
    }

    fun originOf(packed: String): Origin? {
        origins[packed]?.let { return it }
        if (packed.startsWith(PACK_PREFIX)) {
            val id = packed.removePrefix(PACK_PREFIX)
            if (id.isNotBlank()) {
                return Origin(id, durationMs = null)
            }
        }
        return null
    }

    internal fun clearOrigins() {
        origins.clear()
    }

    internal fun resetForTests() {
        baseOverride = null
        activeBase = BASE
        origins.clear()
    }

    /** A verified file: its URL, measured bitrate, and implied resolution. */
    data class ResolvedFile(
        val url: String,
        val kbps: Int,
        val bitDepth: Int,
        val sampleRateHz: Int,
    )

    data class FlacInfo(val bitDepth: Int, val sampleRateHz: Int)

    fun parseFlacStreamInfo(bytes: ByteArray): FlacInfo? {
        if (bytes.size < 4) return null
        if (bytes[0] != 0x66.toByte() || bytes[1] != 0x4C.toByte() ||
            bytes[2] != 0x61.toByte() || bytes[3] != 0x43.toByte()) {
            return null
        }
        if (bytes.size < 26) return FlacInfo(16, 44_100)
        val b18 = bytes[18].toInt() and 0xFF
        val b19 = bytes[19].toInt() and 0xFF
        val b20 = bytes[20].toInt() and 0xFF
        val b21 = bytes[21].toInt() and 0xFF

        val sampleRate = (b18 shl 12) or (b19 shl 4) or (b20 shr 4)
        val bitsPerSample = (((b20 and 0x01) shl 4) or (b21 shr 4)) + 1

        val validRate = if (sampleRate in 8_000..768_000) sampleRate else 44_100
        val validBits = if (bitsPerSample in 8..32) bitsPerSample else 16
        return FlacInfo(validBits, validRate)
    }

    /** Checks whether a specific host answers a search. Swallows, never throws. */
    suspend fun probeHost(host: String): Boolean {
        val base = if (host.startsWith("http://") || host.startsWith("https://")) host.trimEnd('/') else "https://$host"
        return try {
            get("$base/search/tracks?q=a&limit=1")
            true
        } catch (e: Exception) {
            kotlin.coroutines.coroutineContext.ensureActive()
            false
        }
    }

    /** True when any candidate host answers a shape-checked search. Swallows, never throws. */
    suspend fun probe(): Boolean {
        for (candidate in getCandidateBases()) {
            val ok = try {
                get("$candidate/search/tracks?q=a&limit=1")
                activeBase = candidate
                true
            } catch (e: IOException) {
                coroutineContext.ensureActive()
                false
            }
            if (ok) return true
        }
        return false
    }

    suspend fun searchTracks(query: String, limit: Int = 25): List<MonoTrack> {
        val q = query.trim().takeIf { it.isNotEmpty() } ?: return emptyList()
        for (candidate in getCandidateBases()) {
            val result = try {
                val body = get("$candidate/search/tracks?q=${encode(q)}&limit=${limit.coerceIn(1, 50)}")
                val tracks = json.decodeFromString<SearchResponse>(body).tracks
                    .filter { it.playable != false && it.id.isNotBlank() }
                activeBase = candidate
                tracks
            } catch (e: Exception) {
                coroutineContext.ensureActive()
                TrackLog.d(TAG, "  ✗ hires direct catalogue search on $candidate failed: ${e.message}")
                null
            }
            if (result != null) return result
        }
        return emptyList()
    }

    /**
     * Verifies the file answers and measures it, following 5xx backoff. Null
     * when the host lacks the file (4xx), stays down, or hands over a
     * below-lossless file in a FLAC container: all are misses to walk past.
     */
    suspend fun resolveFile(id: String, durationMs: Long?): ResolvedFile? {
        val duration = normalizeDurationMs(durationMs)
        for (candidate in getCandidateBases()) {
            val url = "$candidate/track/$id"
            val res = resolveFileOn(url, duration)
            if (res != null) {
                activeBase = candidate
                return res
            }
        }
        return null
    }

    private suspend fun resolveFileOn(url: String, duration: Long?): ResolvedFile? {
        repeat(RETRY_DELAYS_MS.size + 1) { attempt ->
            coroutineContext.ensureActive()
            val probe = try {
                probeStream(url)
            } catch (e: IOException) {
                coroutineContext.ensureActive()
                return null
            }
            if (probe.code == 200 || probe.code == 206) {
                val measured = measuredKbps(probe.lengthBytes, duration)
                if (measured != null && measured < MIN_GENUINE_KBPS) {
                    TrackLog.w(TAG, "  ✗ hires direct file below lossless floor; refusing")
                    return null
                }
                return fileFrom(url, probe.lengthBytes, duration, probe.flacInfo)
            }
            if (probe.code < 500 || attempt >= RETRY_DELAYS_MS.size) return null
            delay(RETRY_DELAYS_MS[attempt])
        }
        return null
    }

    private data class StreamProbe(val code: Int, val lengthBytes: Long, val flacInfo: FlacInfo? = null)

    private fun probeStream(url: String): StreamProbe {
        val rangeReq = Request.Builder().url(url)
            .header("User-Agent", BROWSER_UA)
            .header("Range", "bytes=0-41")
            .build()
        try {
            client.newCall(rangeReq).execute().use { response ->
                if (response.code == 206 || response.code == 200) {
                    val cr = response.header("Content-Range")
                    val total = cr?.substringAfterLast('/')?.toLongOrNull()
                        ?: response.header("Content-Length")?.toLongOrNull()
                        ?: 0L
                    val bytes = response.body?.bytes() ?: ByteArray(0)
                    val flac = parseFlacStreamInfo(bytes)
                    return StreamProbe(response.code, total, flac)
                }
            }
        } catch (_: Exception) {}

        val headReq = Request.Builder().url(url).head()
            .header("User-Agent", BROWSER_UA)
            .build()
        client.newCall(headReq).execute().use { response ->
            val length = response.header("Content-Length")?.toLongOrNull() ?: 0L
            return StreamProbe(response.code, length, null)
        }
    }

    /** Seconds and milliseconds arrive interchangeably; milliseconds win. */
    fun normalizeDurationMs(durationMs: Long?): Long? {
        val duration = durationMs?.takeIf { it > 0 } ?: return null
        return if (duration <= 10_000) duration * 1000 else duration
    }

    /** Average bitrate from measured size, or null when either side is unknown. */
    fun measuredKbps(sizeBytes: Long, durationMs: Long?): Int? {
        val duration = durationMs?.takeIf { it > 0 } ?: return null
        if (sizeBytes <= 0) return null
        return (((sizeBytes * 8L * 1000L) / duration) / 1000L).toInt()
    }

    fun fileFrom(url: String, sizeBytes: Long, durationMs: Long?): ResolvedFile =
        fileFrom(url, sizeBytes, durationMs, null)

    /**
     * File size over duration to average bitrate, then the exact resolution
     * from STREAMINFO or least resolution that bitrate implies.
     */
    fun fileFrom(url: String, sizeBytes: Long, durationMs: Long?, flacInfo: FlacInfo? = null): ResolvedFile {
        val kbps = measuredKbps(sizeBytes, durationMs) ?: (CD_PCM_BITRATE / 1000)
        val bitDepth = flacInfo?.bitDepth ?: if (kbps > CD_PCM_BITRATE / 1000) 24 else 16
        val sampleRateHz = flacInfo?.sampleRateHz ?: if (kbps > HIRES_PCM_BITRATE / 1000) 96_000 else 44_100
        return ResolvedFile(
            url = url,
            kbps = kbps,
            bitDepth = bitDepth,
            sampleRateHz = sampleRateHz,
        )
    }

    /** Whether two runtimes name the same recording within tolerance. */
    fun durationsMatch(expectedSec: Int?, actualMs: Long?, toleranceSec: Int = 5): Boolean {
        if (expectedSec == null || expectedSec <= 0) return true
        val actual = normalizeDurationMs(actualMs)?.div(1000)?.toInt() ?: return true
        return abs(expectedSec - actual) <= max(toleranceSec, expectedSec / 10)
    }

    // ── Matching ──────────────────────────────────────────────────────
    // ISRC-exact first; anything else must share an artist credit, because
    // the search returns covers next to originals.
    //
    // Title matching lives here rather than in the source so every caller
    // ranks rows the same way: strip featured/remaster/version noise and
    // refuse a result carrying a modifier the request lacks, so a live cut
    // never stands in for the studio recording.

    private val MODIFIERS = listOf(
        "karaoke", "remix", "live", "instrumental", "version", "edit",
        "acoustic", "reprise", "demo", "session", "cover", "tribute",
        "orchestral", "piano",
    )

    private val MODIFIER_REGEX = Regex(
        """\b(?:${MODIFIERS.joinToString("|")})\b""",
        RegexOption.IGNORE_CASE
    )

    fun normalizeTitle(title: String): String {
        var t = title.replace(Regex("[áàäâã]"), "a")
            .replace(Regex("[éèëê]"), "e")
            .replace(Regex("[íìïî]"), "i")
            .replace(Regex("[óòöôõ]"), "o")
            .replace(Regex("[úùüû]"), "u")
            .replace("ñ", "n").replace("Ñ", "N")
            .trim()
        t = t.substringBefore("(").trim()
        t = t.substringBefore("[").trim()
        t = t.substringBefore(" - ").trim()
        val lower = t.lowercase()
        t = t.substringBefore(" feat", t).trim().let {
            if (lower.contains(" feat")) it.substring(0, lower.indexOf(" feat")).trim() else it
        }
        val lower2 = t.lowercase()
        t = if (" ft." in lower2) t.substring(0, lower2.indexOf(" ft.")).trim() else t
        val lower3 = t.lowercase()
        t = if ("remaster" in lower3) t.substring(0, lower3.indexOf("remaster")).trim() else t
        return t
    }

    fun titlesMatch(search: String, result: String): Boolean {
        val s = normalizeTitle(search).lowercase()
        val r = normalizeTitle(result).lowercase()
        if (s.isEmpty() || r.isEmpty()) return false
        return r.contains(s) || s.contains(r)
    }

    fun hasModifier(title: String): Boolean {
        return MODIFIER_REGEX.containsMatchIn(title)
    }

    /** A result is playable for a request when titles match and it adds no new modifier. */
    fun isPlayableMatch(request: String, result: String): Boolean {
        if (!titlesMatch(request, result)) return false
        return !(hasModifier(result) && !hasModifier(request))
    }

    private const val KEEP = "abcdefghijklmnopqrstuvwxyz0123456789 "

    fun simplify(value: String?): String {
        if (value.isNullOrBlank()) return ""
        val kept = StringBuilder()
        for (ch in value.lowercase()) {
            if (ch in KEEP) kept.append(ch)
        }
        return kept.toString().trim()
    }

    /** ISRC hits first, everything else in catalogue order. */
    fun rankByIsrc(items: List<MonoTrack>, isrc: String?): List<MonoTrack> {
        if (items.isEmpty()) return items
        val wanted = isrc?.takeIf { it.isNotBlank() }?.uppercase() ?: return items
        return items.filter { it.isrc?.uppercase() == wanted } +
            items.filter { it.isrc?.uppercase() != wanted }
    }

    /**
     * Drops results that only share the title: an ISRC hit always survives,
     * anything else has to be credited to one of the track's artists.
     */
    fun plausible(items: List<MonoTrack>, artists: List<String>, isrc: String?): List<MonoTrack> {
        if (items.isEmpty()) return items
        val wanted = artists.map { simplify(it) }.filter { it.isNotEmpty() }
        if (wanted.isEmpty()) return items
        val exact = isrc?.takeIf { it.isNotBlank() }?.uppercase().orEmpty()
        return items.filter { item ->
            if (exact.isNotEmpty() && item.isrc?.uppercase() == exact) return@filter true
            item.artistNames.any { credit ->
                val simplified = simplify(credit)
                simplified.isNotEmpty() && wanted.any { simplified.contains(it) || it.contains(simplified) }
            }
        }
    }

    private fun get(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("User-Agent", BROWSER_UA)
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            response.body.string().takeIf { it.isNotBlank() }
                ?: throw IOException("Empty response")
        }
    }

    private const val BROWSER_UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

    private fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")
}
