package com.music.bitchord.playback

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.music.bitchord.data.NerdStats
import com.music.bitchord.data.TelemetryProvenance
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.download.Downloads
import com.music.bitchord.playback.audio.ContainerBitrate
import com.music.bitchord.playback.audio.LocalBitrateReading
import com.music.bitchord.playback.audio.MeasuredBitrateMeter
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Publishes what the decoder is really being fed to [NerdStats], for stats
 * for nerds and the quality badge: the format measured off the stream in hand,
 * the bitrate from the best evidence there is (the container, a local file's
 * own metadata, PCM arithmetic, the source's figure, or the rendition's size
 * over its length), and the name of whoever is serving it.
 *
 * [player] is read when needed, never captured: the session moves between
 * players at every crossfade.
 */
internal class NerdStatsPublisher(
    private val context: Context,
    private val scope: CoroutineScope,
    private val player: () -> ExoPlayer?,
) {
    /** Local files' bitrates, read once from their own metadata or MediaExtractor. */
    private val localBitrateCache = ConcurrentHashMap<String, LocalBitrateReading>()

    /** The live-measured bitrate meter for audio streams. */
    val measuredBitrate = MeasuredBitrateMeter()

    fun onBytesLoaded(mediaId: String?, bytes: Long, mediaStartMs: Long, mediaEndMs: Long) {
        measuredBitrate.onBytesLoaded(mediaId, bytes, mediaStartMs, mediaEndMs)
    }

    fun onPlayTick(mediaId: String?, positionMs: Long, elapsedRealtimeMs: Long) {
        measuredBitrate.onPlayTick(mediaId, positionMs, elapsedRealtimeMs)
    }

    fun onDiscontinuity(mediaId: String?) {
        measuredBitrate.onDiscontinuity(mediaId)
    }

    /** [measuredStreamKbps]'s answers, keyed by rendition URI, and the ones still being read. */
    private val measuredBitrateCache = ConcurrentHashMap<String, Int>()
    private val measuringBitrate: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private fun isLocalPlayback(mediaId: String, mediaItem: MediaItem?): Boolean {
        if (mediaId.startsWith("content://") || mediaId.startsWith("file://") || mediaId.startsWith("/")) {
            return true
        }
        val uri = mediaItem?.localConfiguration?.uri
        if (uri != null && (uri.scheme == "file" || uri.scheme == "content")) {
            return true
        }
        val requestUri = mediaItem?.requestMetadata?.mediaUri
        if (requestUri != null && (requestUri.scheme == "file" || requestUri.scheme == "content")) {
            return true
        }
        val extras = mediaItem?.mediaMetadata?.extras
        val localUri = extras?.getString(EXTRA_LOCAL_URI)
        if (!localUri.isNullOrBlank() && (localUri.startsWith("content://") || localUri.startsWith("file://"))) {
            return true
        }
        val localPath = extras?.getString(EXTRA_LOCAL_PATH)
        if (!localPath.isNullOrBlank()) {
            return true
        }
        if (Downloads.verifiedSavedUri(mediaId) != null) {
            return true
        }
        return false
    }

    private fun currentSourceName(mediaId: String?, mediaItem: MediaItem?): String? {
        val id = mediaId ?: return null
        if (isLocalPlayback(id, mediaItem)) {
            return "Local Storage"
        }

        // 1. Forced upgrade stream on active playback URI
        val uri = mediaItem?.localConfiguration?.uri
        if (uri != null) {
            QualityUpgrade.forcedStream(uri)?.let { upgraded ->
                val name = upgraded.sourceConfigId?.let { SourceRegistry.config(it)?.displayName }
                if (!name.isNullOrBlank()) return name
            }
        }

        // 2. Exact recorded source for this media item
        val exactRecorded = NerdStats.exactSourceFor(id)
        if (!exactRecorded.isNullOrBlank()) return exactRecorded

        // 3. Source-backed track key (e.g. src:<configId>::<trackId>)
        val sourceTrack = SourceRegistry.parseTrackKey(id)
        if (sourceTrack != null) {
            val config = SourceRegistry.config(sourceTrack.first)
            if (config != null) return config.displayName
            return "Unknown"
        }

        // 4. Stream substitution (e.g. YouTube substituted by addon)
        StreamChoice.of(id)?.let { serving ->
            val name = serving.sourceConfigId?.let { SourceRegistry.config(it)?.displayName }
                ?: if (StreamChoice.isSubstitute(id)) "Module" else null
            if (!name.isNullOrBlank()) return name
        }

        // 5. Virtual bitchord://source?s=... playback URI
        if (uri?.authority == "source") {
            val configId = uri.getQueryParameter("s")
            val name = configId?.let { SourceRegistry.config(it)?.displayName }
            if (!name.isNullOrBlank()) return name
            return "Unknown"
        }

        // 6. Non-prefixed mediaId fallback to recorded source if any
        val recorded = NerdStats.sourceFor(id)
        if (!recorded.isNullOrBlank()) return recorded

        // 7. YouTube video ID
        if (id.length == 11 || uri?.getQueryParameter("v") != null) {
            return "YouTube"
        }

        return "Unknown"
    }

    private fun resolveLocalUri(mediaId: String, mediaItem: MediaItem?): Uri? {
        val directUriStr = when {
            mediaId.startsWith("content://") || mediaId.startsWith("file://") -> mediaId
            mediaId.startsWith("/") -> "file://$mediaId"
            else -> null
        }
        if (directUriStr != null) return Uri.parse(directUriStr)

        val localConfigUri = mediaItem?.localConfiguration?.uri
        if (localConfigUri != null && (localConfigUri.scheme == "file" || localConfigUri.scheme == "content")) {
            return localConfigUri
        }

        val requestUri = mediaItem?.requestMetadata?.mediaUri
        if (requestUri != null && (requestUri.scheme == "file" || requestUri.scheme == "content")) {
            return requestUri
        }

        val extras = mediaItem?.mediaMetadata?.extras
        val localUriStr = extras?.getString(EXTRA_LOCAL_URI)
        if (!localUriStr.isNullOrBlank()) {
            return Uri.parse(localUriStr)
        }

        val localPath = extras?.getString(EXTRA_LOCAL_PATH)
        if (!localPath.isNullOrBlank()) {
            return Uri.fromFile(java.io.File(localPath))
        }

        val downloadedUri = Downloads.verifiedSavedUri(mediaId)
        if (downloadedUri != null) {
            return Uri.parse(downloadedUri)
        }

        return null
    }

    fun resolveLocalBitrate(mediaId: String, mediaItem: MediaItem?) {
        if (!isLocalPlayback(mediaId, mediaItem)) return
        if (localBitrateCache.containsKey(mediaId)) return

        val uri = resolveLocalUri(mediaId, mediaItem) ?: return
        scope.launch(Dispatchers.IO) {
            val reading = runCatching { extractLocalBitrate(uri) }.getOrNull()
            if (reading != null && reading.kbps > 0) {
                localBitrateCache[mediaId] = reading
                withContext(Dispatchers.Main) {
                    if (player()?.currentMediaItem?.mediaId == mediaId) {
                        publish()
                    }
                }
            }
        }
    }

    private fun extractLocalBitrate(uri: Uri): LocalBitrateReading? {
        val extractor = MediaExtractor()
        try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                extractor.setDataSource(descriptor.fileDescriptor)
                var durationMs: Long? = null
                val sizeBytes: Long? = descriptor.statSize.takeIf { it > 0 }
                for (i in 0 until extractor.trackCount) {
                    val track = extractor.getTrackFormat(i)
                    val mime = track.getString(MediaFormat.KEY_MIME) ?: continue
                    if (!mime.startsWith("audio/")) continue
                    if (durationMs == null && track.containsKey(MediaFormat.KEY_DURATION)) {
                        durationMs = track.getLong(MediaFormat.KEY_DURATION) / 1_000L
                    }
                    val bitrateBps = if (track.containsKey(MediaFormat.KEY_BIT_RATE)) {
                        track.getInteger(MediaFormat.KEY_BIT_RATE).toLong()
                    } else {
                        0L
                    }
                    if (bitrateBps > 0) {
                        val sampleRate = if (track.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            track.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        } else null
                        val channels = if (track.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            track.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        } else null
                        val bitDepth = if (track.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            pcmBitDepthOf(track.getInteger(MediaFormat.KEY_PCM_ENCODING))
                        } else null
                        if (ContainerBitrate.isPlausibleEncodedBitrate(bitrateBps, sampleRate, channels, bitDepth)) {
                            return LocalBitrateReading(
                                kbps = ((bitrateBps + 500L) / 1_000L).toInt(),
                                provenance = TelemetryProvenance.MEASURED,
                            )
                        }
                    }
                    break
                }
                val duration = durationMs ?: retrieverDurationMs(uri)
                if (sizeBytes != null && duration != null) {
                    ContainerBitrate.averageKbpsFromSizeAndDuration(sizeBytes, duration)?.let {
                        return LocalBitrateReading(it, TelemetryProvenance.DERIVED)
                    }
                }
            }
        } finally {
            runCatching { extractor.release() }
        }
        return null
    }

    private fun retrieverDurationMs(uri: Uri): Long? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun pcmBitDepthOf(encoding: Int): Int? = when (encoding) {
        android.media.AudioFormat.ENCODING_PCM_16BIT -> 16
        android.media.AudioFormat.ENCODING_PCM_FLOAT -> 32
        21 /* ENCODING_PCM_24BIT_PACKED */ -> 24
        22 /* ENCODING_PCM_32BIT */ -> 32
        else -> null
    }

    /**
     * Publishes what the decoder is really being fed, for "stats for nerds"
     * and for the quality badge above it.
     *
     * Everything the badge is decided on is measured off the stream in hand —
     * see [Measured] and [NerdStats.Snapshot.isLossless]. What a source said
     * it was about to send is carried alongside as [NerdStats.Snapshot.claimed]
     * and used for nothing but the mismatch note, because a claim is the one
     * thing that stays true after the stream it described has stopped playing.
     *
     * Bitrate is the awkward one. A lossless stream gets the rate its samples
     * decode to, which is a product of three figures already measured and is
     * the number a listener can compare between tracks. A lossy one gets the
     * container's, when the container states it — YouTube's WebM and MP4 do
     * not, so [Format.bitrate] arrives as `NO_VALUE` and the honest figure is
     * whatever named this stream instead. The source's own figure comes ahead
     * of YouTube's because a track can have both: one resolved through YouTube
     * and then upgraded to a module stream mid-song has a stale 160 sitting in
     * [NerdStats.pickedBitrateKbps] describing audio that stopped playing
     * several seconds ago. Anything still unknown is left null for the UI to
     * omit — better a shorter line than a made-up number.
     */
    fun publish() {
        val player = player() ?: return
        val format = player.audioFormat
        val mediaId = player.currentMediaItem?.mediaId
        val generation = NerdStats.currentGeneration
        val measured = format?.measure()
        // DSD reaches the renderer already decimated to PCM (DsdExtractor), so
        // its sample mime is `audio/raw`; the extractor leaves `dsd64` and the
        // like in `codecs`, and that is the name worth showing — with the DSD
        // stream's own bitrate and rate (2.8224 MHz for DSD64) rather than the
        // 176.4 kHz PCM it became.
        val dsd = format?.codecs?.takeIf { it.startsWith("dsd") }
        val dsdRateHz = dsd?.removePrefix("dsd")?.toIntOrNull()?.times(44_100)
        val isLossless = dsd != null || NerdStats.isLosslessMime(format?.sampleMimeType)
        val isRawPcm = NerdStats.isRawPcm(format?.sampleMimeType)
        val pcmDataRate = measured?.pcmBitrateKbps

        val liveMeasuredKbps = mediaId?.let { measuredBitrate.measuredKbps(it) }
        val localReading = mediaId?.let { localBitrateCache[it] }

        val (bitrate, provenance) = when {
            liveMeasuredKbps != null ->
                Pair(liveMeasuredKbps, TelemetryProvenance.MEASURED)
            localReading != null ->
                Pair(localReading.kbps, localReading.provenance)
            !isLossless && dsd == null && (format?.averageBitrate ?: 0) > 0 ->
                Pair((format!!.averageBitrate + 500) / 1000, TelemetryProvenance.AUTHORITATIVE)
            !isLossless && dsd == null && (format?.bitrate ?: 0) > 0 ->
                Pair((format!!.bitrate + 500) / 1000, TelemetryProvenance.AUTHORITATIVE)
            dsd == null && isRawPcm && pcmDataRate != null ->
                Pair(pcmDataRate, TelemetryProvenance.DERIVED)
            else ->
                NerdStats.sourceBitrate(mediaId) { measuredStreamKbps(player) }
                    ?: Pair(null, TelemetryProvenance.UNKNOWN)
        }

        NerdStats.publish(NerdStats.Snapshot(
            mimeType = dsd?.let { "audio/$it" } ?: format?.sampleMimeType,
            bitrateKbps = bitrate,
            bitrateProvenance = provenance,
            pcmDataRateKbps = if (isLossless) pcmDataRate else null,
            sampleRateHz = dsdRateHz ?: measured?.sampleRateHz,
            channels = measured?.channels,
            bitDepth = if (dsd != null) 1 else if (isLossless) measured?.bitDepth else null,
            claimed = NerdStats.declaredFormat(mediaId),
            sourceName = currentSourceName(mediaId, player.currentMediaItem),
            container = when (format?.containerMimeType) {
                "audio/x-dsf" -> "DSF"
                "audio/x-dff" -> "DFF"
                else -> null
            },
            mediaId = mediaId,
        ), expectedGeneration = generation)
    }

    /**
     * The playing stream's real bitrate: its full size over its length, or
     * null until both are known.
     *
     * For an addon that didn't state a bitrate and got one guessed from the
     * tier it was asked for — see [NerdStats.sourceBitrate]. The size is what
     * the server's response said, recorded in the cache index the first time
     * the rendition was opened, so this works on a stream still downloading.
     * Slightly high, because container overhead counts toward the bytes and
     * not toward the audio. Read off the main thread, once per rendition: the
     * cache index lookup takes the cache's lock, which a loader may be holding.
     */
    private fun measuredStreamKbps(player: Player): Int? {
        val uri = player.currentMediaItem?.localConfiguration?.uri ?: return null
        val key = uri.toString()
        measuredBitrateCache[key]?.let { return it }
        val durationMs = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return null
        if (measuringBitrate.add(key)) {
            scope.launch(Dispatchers.IO) {
                try {
                    val bytes = AudioCache.contentLengthOf(uri)
                    if (bytes > 0) {
                        val kbps = (bytes * 8 / durationMs).toInt()
                        if (kbps > 0) measuredBitrateCache[key] = kbps
                    }
                } finally {
                    measuringBitrate.remove(key)
                }
            }
        }
        return null
    }

    /** A different track: a rendition measured for the last one isn't kept past it. */
    fun onTrackTransition() {
        measuredBitrateCache.clear()
    }

    /** A new service, or the end of one: nothing measured carries over. */
    fun forgetSession() {
        localBitrateCache.clear()
        measuredBitrateCache.clear()
        measuredBitrate.forget()
    }
}
