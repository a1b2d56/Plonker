package com.music.bitchord.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.music.bitchord.data.Http
import com.music.bitchord.data.NerdStats
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.innertube.StreamResolver
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.data.sources.SourceResolver
import com.music.bitchord.data.sources.SourceStream
import com.music.bitchord.data.sources.StreamFormat
import com.music.bitchord.playback.smart.AutomixAnalysisSource
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Turns a queue item's virtual `bitchord://` URI into the URL its bytes come
 * from, at the moment ExoPlayer opens it — on the loader thread, which is why
 * the waits below may block.
 *
 * In order: a track picked from an addon is served by whichever source can
 * ([SourceResolver.resolve]); a revert to the original and Automix's analysis
 * reads go straight to YouTube; an upgraded item gets the stream already
 * proved for it ([QualityUpgrade]); whoever is filling a track's cache entry
 * keeps it ([StreamChoice]); bytes on disk from YouTube are finished by
 * YouTube; and otherwise YouTube races the higher-ranked modules
 * ([StreamRecoveryCoordinator.resolveWithModulePriority]). Direct Source
 * Stream holds every open until the track is about to play.
 *
 * [upstreamFactory] builds the network chain the player, read-ahead and the
 * watch all read through, with this resolver in it.
 */
@UnstableApi
internal class PlaybackStreamResolver(
    private val recovery: StreamRecoveryCoordinator,
) : ResolvingDataSource.Resolver {

    override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
        // Which track everything below is for, said once, because none of
        // it would otherwise know: this runs on ExoPlayer's loader thread
        // with a DataSpec and nothing else, and the work it starts — the
        // source ladder, the module sandbox, a client walk — logs from
        // places several layers deep that have no idea whose bytes they
        // are fetching. Read-ahead means the track being resolved here is
        // usually *not* the one playing, which is exactly why the lines
        // have to say. See [TrackLog.about].
        val about = TrackLog.about(mediaIdIn(dataSpec.uri))
        // A source-backed track is resolved by whichever source can serve
        // it, which is not necessarily the one it was queued from — see
        // [SourceResolver.resolve]. Handled ahead of the YouTube path
        // because these carry no `v` parameter and would otherwise fall
        // straight through unresolved.
        if (dataSpec.uri.authority == "source") {
            // Direct Source Stream: an addon row is held until it is about
            // to play, the same as a YouTube one — see [DirectSourceGate].
            mediaIdIn(dataSpec.uri)?.let { id -> runBlocking(about) { DirectSourceGate.awaitOpen(id) } }
            val directYouTube =
                dataSpec.uri.getQueryParameter(DIRECT_YOUTUBE_PARAMETER) == "1"
            val stream = runBlocking(about) {
                withTimeout(RESOLVE_TIMEOUT_MS) {
                    if (directYouTube) {
                        SourceResolver.youtubeFallback(SourceResolver.targetIn(dataSpec.uri))
                    } else {
                        SourceResolver.resolve(dataSpec.uri)
                    }
                }
            } ?: throw java.io.IOException(
                if (directYouTube) {
                    "No YouTube audio fallback matched ${dataSpec.uri.getQueryParameter("n")}"
                } else {
                    "No enabled source could serve ${dataSpec.uri.getQueryParameter("n")}"
                },
            )
            val configId = dataSpec.uri.getQueryParameter("s")
            val sourceName = if (directYouTube) {
                "YouTube"
            } else {
                stream.sourceConfigId?.let { SourceRegistry.config(it)?.displayName }
                    ?: configId?.let { SourceRegistry.config(it)?.displayName }
                    ?: "Source"
            }
            val trackParam = dataSpec.uri.getQueryParameter("t")
            val fullMediaId = mediaIdIn(dataSpec.uri)
            NerdStats.onSourceStream(fullMediaId ?: trackParam, stream.format, sourceName)
            if (fullMediaId != null) {
                NerdStats.recordSource(fullMediaId, sourceName)
            }
            NerdStats.recordSource(trackParam, sourceName)
            return dataSpec.buildUpon()
                .setUri(Uri.parse(stream.url))
                .setHttpRequestHeaders(
                    if (directYouTube) StreamResolver.mediaHeadersFor(stream.url)
                    else stream.headers,
                )
                .build()
        }
        val videoId = dataSpec.uri.getQueryParameter("v")
            ?: return dataSpec
        // Direct Source Stream holds every playback open of a track that
        // is neither playing nor next inside the window: read-ahead and
        // ExoPlayer's own early preparation of the next item alike. Not
        // Automix's Opus reads, which measure the next track ahead of time
        // on purpose and never touch an addon.
        val analysisRead = AutomixAnalysisSource.requestsYouTubeOpus(
            dataSpec.uri.getQueryParameter(AutomixAnalysisSource.OPUS_QUERY_PARAMETER),
        )
        if (!analysisRead) runBlocking(about) { DirectSourceGate.awaitOpen(videoId) }
        // An explicit rollback is not a preference for a different
        // candidate: it means this exact YouTube rendition, immediately.
        // Answer it before StreamChoice, the module race and a pending
        // upgrade can put another source back under the listener.
        if (dataSpec.uri.getQueryParameter(DIRECT_YOUTUBE_PARAMETER) == "1") {
            QualityUpgrade.forget(videoId)
            StreamChoice.forget(videoId)
            NerdStats.clearDeclared(videoId)
            NerdStats.recordSource(videoId, "YouTube")
            val streamUrl = resolveYouTube(videoId, about, "Direct YouTube resolution")
            val headers = StreamResolver.mediaHeadersFor(streamUrl)
            TrackLog.d("BitChord", "serving original YouTube version for $videoId", about = videoId)
            return dataSpec.buildUpon()
                .setUri(Uri.parse(streamUrl))
                .setHttpRequestHeaders(headers)
                .build()
        }
        // Automix owns a base-cache Opus rendition. It must bypass the
        // playback race winner (JioSaavn, a module, or a lossless upgrade)
        // and resolve directly to YouTube for this analysis-only request.
        if (analysisRead) {
            NerdStats.recordSource(videoId, "YouTube")
            val streamUrl = resolveYouTube(videoId, about, "Automix Opus resolution")
            val headers = StreamResolver.mediaHeadersFor(streamUrl)
            return dataSpec.buildUpon()
                .setUri(Uri.parse(streamUrl))
                .setHttpRequestHeaders(headers)
                .build()
        }
        // An upgraded item carries a marker and its stream has already
        // been found — see [QualityUpgrade]. Answered before anything
        // else, and without re-resolving: this exact URL is what the
        // player was told it was getting when it agreed to the swap.
        QualityUpgrade.forcedStream(dataSpec.uri)?.let { upgraded ->
            // An audition opens this same stream before a note of the one
            // playing has been touched — see [auditionUpgrade] — so what it
            // is about to be handed describes a swap that has not happened
            // and may never. Recording it here would light "Lossless" over
            // the lossy stream still coming out of the speaker. The real
            // open, moments later, records it.
            val proving = QualityUpgrade.isAuditioning(videoId)
            val sourceName = upgraded.sourceConfigId?.let { SourceRegistry.config(it)?.displayName }
                ?: "Upgrade"
            if (!proving) {
                NerdStats.onSourceStream(videoId, upgraded.format, sourceName)
                NerdStats.recordSource(videoId, sourceName)
            }
            // Logged because the alternative — a swap that silently never
            // reached its stream — is indistinguishable in the logs from
            // one that reached it and got nothing back, and the two have
            // opposite fixes.
            TrackLog.d(
                "BitChord",
                "${if (proving) "auditioning" else "serving"} upgraded $videoId " +
                    "from ${Uri.parse(upgraded.url).host} " +
                    "at ${dataSpec.position} (${upgraded.format.summary})",
                about = videoId,
            )
            return dataSpec.buildUpon()
                .setUri(Uri.parse(upgraded.url))
                .setHttpRequestHeaders(upgraded.headers)
                .build()
        }
        // A downloaded copy is *not* substituted here, deliberately. This
        // point is inside the HTTP-only half of the chain — below
        // DefaultDataSource, which has already given up on dispatching by
        // scheme, and below the cache bypass that keeps local files from
        // being written to disk a second time. A content:// URI returned
        // from here reaches OkHttp, which refuses it as a malformed URL.
        // Which copy of a track to play is settled where the item is built
        // instead: see [Song.toMediaItem].
        // Whoever is already filling this track's cache entry keeps it.
        // Everything below decides between servers holding *different
        // files*, and this method is called again for every re-open of a
        // track — including the continuation fetch when playback runs off
        // the end of the cached bytes. Deciding afresh each time is how
        // the middle of an MP4 ended up appended to a WebM. See
        // [StreamChoice].
        StreamChoice.of(videoId)?.let { serving ->
            // What the stream claims to be, restated on every open rather
            // than only on the one that chose it.
            //
            // The race below is what used to report this, and it was enough
            // while the race was the only way a substitution could be made.
            // Read-ahead now pins one before the track is reached, so a
            // warmed track arrives *here* on its very first open and never
            // reaches the race at all — leaving the player with a 320kbps
            // stream and nothing on record saying so, and the quality badge
            // reading blank until the decoder got far enough to measure it
            // for itself.
            //
            // Only when the format states something. A plain YouTube choice
            // is remembered with an empty one, and writing that over a
            // claim some other path made would be worse than saying nothing.
            val sourceName = serving.sourceConfigId?.let { SourceRegistry.config(it)?.displayName }
                ?: if (StreamChoice.isSubstitute(videoId)) "Module" else "YouTube"
            if (serving.format != StreamFormat()) {
                NerdStats.onSourceStream(videoId, serving.format, sourceName)
            }
            NerdStats.recordSource(videoId, sourceName)
            // Read-ahead can pin JioSaavn's quick 320kbps answer before
            // this track becomes current.  It is the right answer for an
            // immediate start, but it is not the final quality verdict:
            // returning here used to bypass [resolveWithModulePriority],
            // the only path which calls [QualityUpgrade.settledForLess].
            // Consequently a warmed track displayed its high-quality
            // source but never kept the upgrading state or asked the
            // module for its lossless copy.
            //
            // Only substituted, non-lossless choices need this. A pinned
            // YouTube URL has no source result to promote, and a lossless
            // module result already satisfies the request.
            // Direct Source Stream plays the addon's answer as final: no
            // second look, no mid-song swap.
            if (!DirectSourceGate.isActive() &&
                StreamChoice.isSubstitute(videoId) &&
                serving.format.isLossless != true &&
                QualityUpgrade.couldStillUpgrade(videoId, dataSpec.uri)
            ) {
                val pending = QualityUpgrade.settledForLess(
                    mediaId = videoId,
                    target = SourceResolver.targetIn(dataSpec.uri),
                    playing = serving.format,
                    servedBy = serving.sourceConfigId,
                )
                if (!pending) NerdStats.onLosslessRaceEnd(videoId)
            }
            return dataSpec.buildUpon()
                .setUri(Uri.parse(serving.url))
                .setHttpRequestHeaders(serving.headers)
                .build()
        }
        // A track queued from YouTube may be held by a source the user
        // ranked above it — see [SourceResolver.substituteForYouTube] and
        // [raceYouTubeOrModule]. Only worth the extra lookup when
        // something actually outranks YouTube; otherwise this is the
        // plain resolve every build before this one made.
        if (!SourceResolver.canSubstituteForYouTube()) {
            val streamUrl = resolveYouTube(videoId, about, "Stream resolution")
            // googlevideo names the client that minted the URL inside the
            // URL itself, and compares it against the request that comes
            // back for the bytes. A mismatch is answered with a throttled
            // trickle or a 403 rather than an error worth the name, so the
            // fetch is dressed as whatever the URL says it should be.
            val headers = StreamResolver.mediaHeadersFor(streamUrl)
            // Recorded even though only one server can answer here: a
            // source enabled from Settings mid-track flips the branch
            // above under a half-filled cache entry, and the entry would
            // then be finished by a different file.
            NerdStats.recordSource(videoId, "YouTube")
            StreamChoice.remember(videoId, SourceStream(streamUrl, headers = headers), substituted = false)
            return dataSpec.buildUpon()
                .setUri(Uri.parse(streamUrl))
                .setHttpRequestHeaders(headers)
                .build()
        }
        // The entry already holds YouTube's own bytes, from a play nothing in
        // this process remembers — a restart, or a [StreamChoice] that has
        // expired. This open is where those bytes run out, so it is
        // YouTube's to finish: the race below can hand the rest of the file
        // to a module, streamed into the middle of a WebM. See
        // [AudioCache.heldByYouTube].
        //
        // The better copy still gets its hearing, the way every other
        // YouTube start does: marked for the second look, which swaps it in
        // under the music. Direct Source Stream has settled this before the
        // cache was read — see [AudioCache.playbackFactory].
        if (AudioCache.heldByYouTube(dataSpec.key ?: AudioCache.cacheKeyOf(dataSpec.uri))) {
            val streamUrl = resolveYouTube(videoId, about, "Stream resolution")
            val headers = StreamResolver.mediaHeadersFor(streamUrl)
            NerdStats.recordSource(videoId, "YouTube")
            StreamChoice.remember(videoId, SourceStream(streamUrl, headers = headers), substituted = false)
            if (QualityUpgrade.couldStillUpgrade(videoId, dataSpec.uri)) {
                val pending = QualityUpgrade.settledForLess(
                    mediaId = videoId,
                    target = SourceResolver.targetIn(dataSpec.uri),
                    playing = NerdStats.pickedBitrateKbps(videoId)?.let { StreamFormat(kbps = it) },
                )
                if (!pending) NerdStats.onLosslessRaceEnd(videoId)
            }
            return dataSpec.buildUpon()
                .setUri(Uri.parse(streamUrl))
                .setHttpRequestHeaders(headers)
                .build()
        }
        val won = runBlocking(about) {
            recovery.resolveWithModulePriority(
                videoId = videoId,
                target = SourceResolver.targetIn(dataSpec.uri),
            )
        }
        return when (won) {
            is StreamRecoveryCoordinator.Resolved.Module -> {
                val sourceName = won.stream.sourceConfigId?.let { SourceRegistry.config(it)?.displayName }
                    ?: "Module"
                NerdStats.onSourceStream(videoId, won.stream.format, sourceName)
                NerdStats.recordSource(videoId, sourceName)
                StreamChoice.remember(videoId, won.stream, substituted = true)
                dataSpec.buildUpon()
                    .setUri(Uri.parse(won.stream.url))
                    .setHttpRequestHeaders(won.stream.headers)
                    .build()
            }
            // A module could have served this and didn't — it missed, its
            // server was slow, or the lookup ran out of budget. The last
            // of those is worth chasing rather than accepting: measured
            // here, a module's stream URL arrived 66ms after the live path
            // gave up on it, and the difference between a FLAC and a
            // YouTube Opus stream came down to that. The second look has
            // no such deadline, so what was nearly in hand is asked for
            // again while the fallback plays.
            is StreamRecoveryCoordinator.Resolved.YouTube -> {
                NerdStats.recordSource(videoId, "YouTube")
                val headers = StreamResolver.mediaHeadersFor(won.url)
                StreamChoice.remember(videoId, SourceStream(won.url, headers = headers), substituted = false)
                dataSpec.buildUpon()
                    .setUri(Uri.parse(won.url))
                    .setHttpRequestHeaders(headers)
                    .build()
            }
        }
    }

    /** YouTube's own stream for [videoId], bounded by [RESOLVE_TIMEOUT_MS]; [what] names the wait in the error. */
    private fun resolveYouTube(videoId: String, about: CoroutineContext, what: String): String = try {
        runBlocking(about) {
            withTimeout(RESOLVE_TIMEOUT_MS) { StreamResolver.resolve(videoId) }
        }
    } catch (e: TimeoutCancellationException) {
        throw java.io.IOException("$what timed out for $videoId", e)
    }

    /**
     * The upstream every playback read goes through: the network, chunked,
     * behind this resolver, with smb:// shares routed off to the side.
     */
    fun upstreamFactory(context: Context): DataSource.Factory {
        // No user agent on the factory: the right one depends on which client
        // minted the URL, so it is set per request below. Setting it here as
        // well would not override that — OkHttpDataSource *appends* the
        // factory's agent after the request's, and the fetch would go out
        // carrying two contradictory User-Agent headers.
        val resolvingFactory = ResolvingDataSource.Factory(
            // Innermost, so it chunks the real googlevideo URL the resolver
            // above has already substituted in — see [ChunkedDataSource] for
            // why an open-ended read of one is worth avoiding.
            ChunkedDataSource.Factory(OkHttpDataSource.Factory(Http.client), STREAM_CHUNK_BYTES),
        ) { dataSpec ->
            // Wrapped rather than folded into the resolver above so the record
            // is made in one place for every branch of it, and made from what
            // the resolver *returned* rather than from what each branch meant
            // to return. Nothing above this line knows what is really on the
            // end of a `bitchord://` URI and nothing below it knows which
            // track's bytes it is fetching; this is the seam where both are in
            // hand. See [StreamContainer].
            resolveDataSpec(dataSpec).also { resolved ->
                mediaIdIn(dataSpec.uri)?.let { StreamContainer.served(it, resolved.uri.toString()) }
                // The same seam tells the cache whose file it is writing, which
                // decides whether the entry outlives the track — see
                // [AudioCache.Origin].
                AudioCache.recordServed(dataSpec, resolved.uri.toString())
            }
        }
        // Read-ahead resolves streams through the same chain the player does.
        // smb:// tracks read straight off the share below the cache (so they
        // cache and seek like HTTP); everything else flows as before, and the
        // YouTube resolver never sees a scheme it cannot answer.
        return SmbDataSource.RoutingFactory(
            DefaultDataSource.Factory(context, resolvingFactory),
        )
    }

    private companion object {
        /** Upper bound per range; [PlayerClient.rangeBytesFor] narrows it per client. */
        const val STREAM_CHUNK_BYTES = 1L * 1024 * 1024

        /**
         * Outer cap on stream resolution. Individual client calls and probes
         * have their own timeouts, but iterating all seven plus the NewPipe
         * fallback can accumulate far beyond what a listener should wait.
         *
         * The NewPipe fallback alone — a scrape of the watch page, shaped
         * harder than anything else this app asks Google for — routinely
         * takes 45-90s on its own when every player client is bot-checked, a
         * state that has become the common case rather than the rare one. A
         * cap shorter than that doesn't bound the wait; it cancels the
         * resolve just as it was about to succeed, and the retry that
         * follows restarts the same slow walk from zero, so the listener
         * waits *longer* under a tighter cap than a looser one.
         */
        const val RESOLVE_TIMEOUT_MS = 120_000L
    }
}
