package com.music.bitchord.playback

import android.content.Context
import android.content.Intent
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.audiofx.AudioEffect
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import com.music.bitchord.data.NerdStats
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.EqualizerMode
import com.music.bitchord.playback.audio.DirectAudioProbe
import com.music.bitchord.playback.audio.DspChain
import com.music.bitchord.playback.audio.OutputNegotiator
import com.music.bitchord.playback.audio.PcmEncoding
import com.music.bitchord.playback.audio.PrecisionAudioSink
import com.music.bitchord.playback.audio.SourceDescriptor
import com.music.bitchord.playback.audio.bitperfect.BitPerfectAudioSink
import com.music.bitchord.playback.audio.bitperfect.BitPerfectOutput
import com.music.bitchord.playback.audio.bitperfect.BitPerfectRoutingSink
import com.music.bitchord.playback.audio.bluetooth.BluetoothAudioTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * What happens to the sound between the decoder and the output: the two
 * players' DSP stages, which device they play to, and in what format.
 *
 * Each player has its own [Deck] of processors, fixed to it, because each
 * carries per-sink state (a delay line, filter memory) that two sinks cannot
 * share. [activeFilter] and [spareFilter] are the roles, and they trade places
 * with the players at every handoff ([swapFilterRoles]). Settings that shape
 * the sound (the equaliser, spatial widening) are written to both decks, since
 * the idle one is the one the next transition starts a song on.
 *
 * The output side follows the route: a USB DAC when the listener prefers one,
 * whatever they picked in the output picker, else the system's choice; and it
 * decides whether the sink may open a float AudioTrack ([shouldEnableFloatOutput])
 * and what PCM encoding each format is negotiated to
 * ([resolvePreferredOutputEncoding]). A change that a running sink cannot take
 * waits out any crossfade and then has the service rebuild both players
 * ([rebuildPlayers]); anything else just re-routes.
 *
 * The platform audio-effect session is advertised from here too, so system
 * equalisers can attach to whichever player is audible.
 */
@UnstableApi
internal class AudioRoutingAndDspPipeline(
    private val context: Context,
    private val scope: CoroutineScope,
    /** Both players, in whichever roles they hold now. */
    private val players: () -> List<ExoPlayer>,
    private val transitioning: () -> Boolean,
    /** Rebuild both players for a float setting a running sink cannot change. */
    private val rebuildPlayers: (enableFloat: Boolean) -> Unit,
) {
    /** One player's processors, in the order its sink runs them. */
    class Deck(
        val sing: VocalCutProcessor,
        val spatial: SpatialAudioProcessor,
        val equalizer: EqualizerProcessor,
        val filter: TransitionFilterProcessor,
    )

    private val audioManager by lazy { context.getSystemService(AudioManager::class.java) }
    val bluetoothTracker by lazy { BluetoothAudioTracker(context) }
    /** The format arriving at the active player's decoder, set by the service as it is reported. */
    var currentAudioInputFormat: Format? = null
    private val outputDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>) {
            // Plugging something in moves the music to it. Always — a choice
            // made an hour ago about the speaker is not a standing instruction
            // to ignore the headphones now going in, and there is no row in the
            // picker for "go back to following the system", so a stuck choice
            // would be a picker that had quietly broken the obvious behaviour.
            AudioRouting.forget()
            requestOutputReconfiguration()
        }
        override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) {
            // A chosen output that has been unplugged is no longer a choice.
            // Left set, its id matches nothing — and ids are reused, so it
            // would eventually match whatever device the framework hands that
            // number to next. See [AudioRouting].
            if (removedDevices.any { it.id == AudioRouting.selectedId.value }) {
                AudioRouting.forget()
            }
            requestOutputReconfiguration()
        }
    }

    /**
     * What the players currently in hand were built for.
     *
     * Read at construction rather than per buffer because it decides whether
     * Media3 may open a float AudioTrack at all, which cannot be changed under
     * a running renderer. Moving the setting therefore rebuilds both players.
     */
    var configuredFloatOutput = false
    private var outputReconfigureJob: Job? = null
    /**
     * One audio-processor set per player, because both carry per-sink state — a
     * delay line, filter memory — that two sinks cannot share.
     *
     * The `A`/`B` pair is fixed to the players that own them; [activeFilter] and
     * [spareFilter] are the *roles*, and they trade places at every handoff
     * along with the players. Everything downstream talks in roles.
     */
    val a = Deck(VocalCutProcessor(), SpatialAudioProcessor(), EqualizerProcessor(), TransitionFilterProcessor())
    val b = Deck(VocalCutProcessor(), SpatialAudioProcessor(), EqualizerProcessor(), TransitionFilterProcessor())

    /** The platform audio session currently advertised to system audio tools. */
    private var advertisedAudioEffectSessionId: Int = C.AUDIO_SESSION_ID_UNSET

    /**
     * Whether the format currently arriving at the active player's decoder
     * is Dolby Atmos (E-AC-3 JOC). Widening a JOC stream would fight the
     * object-based mix Dolby already spatializes, so the effect is forced
     * off for as long as this is true — see [applySpatialAudioEnabled].
     */
    var activeTrackIsDolbyAtmos = false

    /** The transition filter on the audible player's sink — a role, see [swapFilterRoles]. */
    var activeFilter: TransitionFilterProcessor = a.filter
        private set

    /** The transition filter on the idle player's sink. */
    var spareFilter: TransitionFilterProcessor = b.filter
        private set

    /** Whether deck [a] is the audible one, told by the role its filter holds. */
    val aIsActive: Boolean get() = activeFilter === a.filter

    /** The players have traded places: the filter roles go with them. */
    fun swapFilterRoles() {
        val held = activeFilter
        activeFilter = spareFilter
        spareFilter = held
    }

    /** Fresh players are built with [a] audible, as at the service's start. */
    fun resetFilterRoles() {
        activeFilter = a.filter
        spareFilter = b.filter
    }

    /** Starts following devices being plugged in and taken away. */
    fun watchOutputDevices() {
        audioManager?.registerAudioDeviceCallback(outputDeviceCallback, null)
    }

    /** The service is going: close the effect session and stop watching the outputs. */
    fun release() {
        closeAudioEffectSession()
        bluetoothTracker.stop()
        audioManager?.unregisterAudioDeviceCallback(outputDeviceCallback)
    }
    /**
     * Publishes the standard music-session lifecycle used by system and
     * third-party audio controllers. Keep the session open while playback is
     * preparing as well as while it is audible, so an effect can attach before
     * the first decoded buffer reaches the output device.
     */
    fun updateAudioEffectSession(activePlayer: ExoPlayer) {
        val sessionId = activePlayer.audioSessionId
        val shouldAdvertise = activePlayer.playWhenReady &&
            (activePlayer.playbackState == Player.STATE_BUFFERING ||
                activePlayer.playbackState == Player.STATE_READY) &&
            sessionId != C.AUDIO_SESSION_ID_UNSET && sessionId > 0

        if (!shouldAdvertise) {
            closeAudioEffectSession()
            return
        }
        if (advertisedAudioEffectSessionId == sessionId) return

        closeAudioEffectSession()
        advertisedAudioEffectSessionId = sessionId
        context.sendBroadcast(
            Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION).apply {
                putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
                putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
                putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
            },
        )
    }

    private fun closeAudioEffectSession() {
        val sessionId = advertisedAudioEffectSessionId
        if (sessionId == C.AUDIO_SESSION_ID_UNSET) return

        advertisedAudioEffectSessionId = C.AUDIO_SESSION_ID_UNSET
        context.sendBroadcast(
            Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION).apply {
                putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
                putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
            },
        )
    }

    /**
     * Renderers whose audio sink only skips silence worth skipping.
     *
     * Media3's stock threshold is 100ms, which eats the breaths, rests and
     * pre-chorus beats *inside* a song — the setting reads as "make the music
     * sound rushed" rather than "trim dead air". A second-long floor leaves
     * musical pauses alone and still collapses the run-in and run-out of a
     * track. Everything else about the chain stays default, so
     * `skipSilenceEnabled` keeps driving it as before.
     */
    fun renderersFor(deck: Deck, loudness: LoudnessProcessor): RenderersFactory =
        object : FfmpegFallbackRenderersFactory(context) {
        init {
            // Do not force PCM_FLOAT onto an OEM speaker mixer merely because
            // the preference asks for it. The selected route must advertise
            // the format; otherwise Media3 uses its stable PCM16 path.
            //
            // Which way this cuts is worth stating, because it reads backwards:
            // float is what *preserves* a hi-res source rather than what
            // embellishes it, since it is the only AudioTrack encoding Media3
            // will open that carries a 24-bit sample intact.
            setEnableAudioFloatOutput(configuredFloatOutput)
            // c2.sec.flac.decoder produces one extra 232.2ms timestamp
            // advance per decoded buffer when Media3 requests float PCM.
            // Decode FLAC through the platform software codec on affected Samsung devices.
            setMediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
                if (mimeType == MimeTypes.AUDIO_ALAC && bundledFfmpegDecodesAlac()) {
                    // ALAC never goes to the platform decoder.
                    //
                    // The note on the renderer list below says FFmpeg is there
                    // for "a track no platform decoder takes: ALAC everywhere" —
                    // which was only ever true on a device whose platform ships
                    // no ALAC decoder. A device that has one takes it, and the
                    // one on a GSI image does: `c2.mtk.alac.decoder` initialises
                    // on a 24-bit stream, plays about a second of it at a crawl,
                    // and then dies with a MediaCodec state error
                    // ("releaseOutputBuffer() is valid only at Executing
                    // states"). The player reads that as a broken stream and
                    // answers by falling back to YouTube's lossy copy, so a
                    // hi-res lossless track becomes a 128kbps one a second in.
                    //
                    // The reference player decodes ALAC with FFmpeg and has
                    // neither the crawl nor the crash. So the platform decoder
                    // is refused for ALAC outright, and the bundled FFmpeg
                    // takes it — but only when it is actually there to take it.
                    emptyList()
                } else {
                    val selector = if (mimeType == MimeTypes.AUDIO_FLAC) {
                        MediaCodecSelector.PREFER_SOFTWARE
                    } else {
                        MediaCodecSelector.DEFAULT
                    }
                    val candidates = selector.getDecoderInfos(
                        mimeType,
                        requiresSecureDecoder,
                        requiresTunnelingDecoder,
                    )
                    if (mimeType == MimeTypes.AUDIO_FLAC) {
                        candidates.filterNot { AudioOutputPolicy.isUnsafeFloatFlacDecoder(it.name) }
                    } else {
                        candidates
                    }
                }
            }
        }

        /**
         * Whether the bundled FFmpeg can decode ALAC.
         *
         * Asked before the platform decoder is refused for ALAC: refusing it
         * on a build with no software decoder would leave the format with no
         * decoder at all, which is worse than the platform one.
         */
        private fun bundledFfmpegDecodesAlac(): Boolean = runCatching {
            FfmpegLibrary.isAvailable() && FfmpegLibrary.supportsFormat(MimeTypes.AUDIO_ALAC)
        }.getOrElse {
            TrackLog.w("Plonker", "could not query the bundled ALAC decoder — ${it.message}")
            false
        }

        override fun buildAudioSink(
            context: Context,
            enableFloatOutput: Boolean,
            enableAudioTrackPlaybackParams: Boolean,
        ): AudioSink {
            val defaultSink = DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(enableFloatOutput)
                // Speed handled by the AudioTrack itself on a float-capable
                // route, rather than by Sonic. `DefaultAudioSink.configure`
                // appends `audioProcessorChain.getAudioProcessors()` — which is
                // where Sonic lives — only on its 16-bit branch; the float
                // branch gets the format converter and nothing else. With the
                // stock flag, changing speed on a float route therefore left
                // the audio playing at 1x while `getMediaDuration` went on
                // scaling, so the position clock drifted away from the sound.
                // AudioTrack's own playback params work on either branch.
                //
                // Silence skipping is in that same dropped chain and has no
                // such escape hatch; the equaliser screen says so, and the
                // equaliser itself is unaffected because it runs upstream in
                // [PrecisionAudioSink], not here.
                .setEnableAudioTrackPlaybackParams(
                    enableAudioTrackPlaybackParams || enableFloatOutput,
                )
                .setAudioProcessorChain(
                    DefaultAudioSink.DefaultAudioProcessorChain(
                        emptyArray<AudioProcessor>(),
                        SilenceSkippingAudioProcessor(
                            MIN_SILENCE_US,
                            SilenceSkippingAudioProcessor.DEFAULT_SILENCE_RETENTION_RATIO,
                            SilenceSkippingAudioProcessor.DEFAULT_MAX_SILENCE_TO_KEEP_DURATION_US,
                            SilenceSkippingAudioProcessor.DEFAULT_MIN_VOLUME_TO_KEEP_PERCENTAGE,
                            SilenceSkippingAudioProcessor.DEFAULT_SILENCE_THRESHOLD_LEVEL,
                        ),
                        SonicAudioProcessor(),
                    ),
                )
                .build()

            // Transition filtering last of the three: widening is a
            // property of the track, and a bass swap that ran before it
            // would have its own low end fed back in by the crossfeed —
            // or, once the equaliser is in the chain, by whatever the
            // listener's low band was set to. The equaliser sits between
            // them for the same reason: it belongs to the listener and
            // the whole session, while the transition filter belongs to
            // one handoff and has to have the last word on it.
            val dspChain = DspChain(deck.sing, deck.spatial, deck.equalizer, deck.filter, loudness)
            val precision = PrecisionAudioSink(
                delegate = defaultSink,
                dspChain = dspChain,
                enableFloatOutput = enableFloatOutput,
                preferredOutputEncodingProvider = { format -> resolvePreferredOutputEncoding(format) },
                // Which of the two sinks the listener can hear, told by the
                // role its transition filter currently holds — [activeFilter]
                // and [spareFilter] trade places at every handoff. Only the
                // audible one writes telemetry, so the idle player's reset
                // cannot publish "not exact" over the playing track's verdict.
                isAudible = { deck.filter === activeFilter },
            )
            // In front of everything above: a track the bit-perfect output can
            // carry skips the DSP chain and Media3's sink altogether.
            return BitPerfectRoutingSink(precision, BitPerfectAudioSink(BitPerfectOutput.forContext(context)))
        }
    }

    /**
     * What the pipeline last reported, for when the live [Format] carries no
     * usable rate or channel count of its own. Guessing 48 kHz here is what made
     * a 176.4 kHz stream negotiate itself down, so the real last-known value is
     * worth reaching for before giving up.
     */
    private fun lastReportedSampleRateHz(): Int? {
        val status = AudioOutputStatus.current.value
        return NerdStats.current.value?.sampleRateHz?.takeIf { it > 0 }
            ?: status.actualSampleRateHz?.takeIf { it > 0 }
            ?: status.negotiationResult?.output?.sampleRateHz?.takeIf { it > 0 }
    }

    private fun lastReportedChannelCount(): Int? {
        val status = AudioOutputStatus.current.value
        return NerdStats.current.value?.channels?.takeIf { it > 0 }
            ?: status.negotiationResult?.output?.channelCount?.takeIf { it > 0 }
    }

    /**
     * Applies the user's USB-DAC preference through the public Android routing
     * API. This does not bypass the system USB driver: it intentionally lets
     * Android negotiate only formats the connected DAC actually advertises.
     */
    fun applyOutputRoute() {
        val manager = audioManager ?: return
        val usb = preferredUsbDevice()
        val chosen = AudioRouting.infoFor(manager, AudioRouting.selectedId.value)
        val preferred = chosen ?: usb.takeIf { AppSettings.preferUsbDac.value }
        players().forEach { it.setPreferredAudioDevice(preferred) }

        val activeDevice = resolveActiveOutputDevice()
        val routeKind = resolveActiveRouteKind(activeDevice)

        val format = currentAudioInputFormat
        val measured by lazy(LazyThreadSafetyMode.NONE) { format?.measure() }
        val sampleRate = format?.sampleRate?.takeIf { it > 0 }
            ?: measured?.sampleRateHz?.takeIf { it > 0 }
            ?: lastReportedSampleRateHz()
        val channels = format?.channelCount?.takeIf { it > 0 }
            ?: measured?.channels?.takeIf { it > 0 }
            ?: lastReportedChannelCount()
            ?: 2
        val directSupport = if (sampleRate != null) {
            DirectAudioProbe.probeDirectSupport(
                audioManager = manager,
                sampleRateHz = sampleRate,
                channelCount = channels,
                activeDevice = activeDevice,
            )
        } else {
            DirectAudioProbe.DirectSupport.NONE
        }
        val btTelemetry = if (routeKind == AudioRouting.Kind.BLUETOOTH) bluetoothTracker.telemetry.value else null

        AudioOutputStatus.publish(
            manager = manager,
            requestedPcmMode = AppSettings.outputPcmMode.value,
            preferred = activeDevice,
            floatEnabled = shouldEnableFloatOutput(),
            routeKind = routeKind,
            directSupport = directSupport,
            bluetoothTelemetry = btTelemetry,
            systemMixerRateHz = if (routeKind == AudioRouting.Kind.USB) 48000 else null,
        )

        if (format != null) {
            resolvePreferredOutputEncoding(format)
        }
    }

    private fun resolvePreferredOutputEncoding(format: Format): PcmEncoding? {
        val manager = audioManager ?: return null
        val activeDevice = resolveActiveOutputDevice()
        val routeKind = resolveActiveRouteKind(activeDevice)

        if (routeKind == AudioRouting.Kind.PHONE) {
            return PcmEncoding.PCM_16BIT
        }

        val inputFormat = currentAudioInputFormat ?: format
        val measuredFormat = format.measure()
        val measuredInput by lazy(LazyThreadSafetyMode.NONE) {
            if (inputFormat === format) measuredFormat else inputFormat.measure()
        }

        // No rate from the stream and none ever reported means nothing is known
        // yet: negotiating against a guessed rate would publish that guess as
        // fact. Leave the encoding to the sink's own fallback instead.
        val sampleRate = format.sampleRate.takeIf { it > 0 }
            ?: inputFormat.sampleRate.takeIf { it > 0 }
            ?: measuredFormat.sampleRateHz?.takeIf { it > 0 }
            ?: measuredInput.sampleRateHz?.takeIf { it > 0 }
            ?: lastReportedSampleRateHz()
            ?: return null

        val channels = format.channelCount.takeIf { it > 0 }
            ?: inputFormat.channelCount.takeIf { it > 0 }
            ?: measuredFormat.channels?.takeIf { it > 0 }
            ?: measuredInput.channels?.takeIf { it > 0 }
            ?: lastReportedChannelCount()
            ?: 2
        val bitDepth = measuredFormat.bitDepth
            ?: measuredInput.bitDepth
            ?: NerdStats.current.value?.bitDepth ?: when (inputFormat.pcmEncoding) {
            C.ENCODING_PCM_32BIT -> 32
            C.ENCODING_PCM_24BIT -> 24
            C.ENCODING_PCM_FLOAT -> 32
            else -> when (format.pcmEncoding) {
                C.ENCODING_PCM_32BIT -> 32
                C.ENCODING_PCM_24BIT -> 24
                C.ENCODING_PCM_FLOAT -> 32
                else -> 16
            }
        }

        val sourceEncoding = if (inputFormat.sampleMimeType != null && inputFormat.sampleMimeType != "audio/raw") {
            inputFormat.sampleMimeType!!
        } else {
            format.sampleMimeType ?: "audio/raw"
        }

        val source = SourceDescriptor(
            encoding = sourceEncoding,
            sampleRateHz = sampleRate,
            channelCount = channels,
            bitDepth = bitDepth,
        )

        val directSupport = DirectAudioProbe.probeDirectSupport(
            audioManager = manager,
            sampleRateHz = sampleRate,
            channelCount = channels,
            activeDevice = activeDevice,
        )
        val btTelemetry = if (routeKind == AudioRouting.Kind.BLUETOOTH) bluetoothTracker.telemetry.value else null

        val advertisedEncodings = activeDevice?.encodings?.toList() ?: emptyList()
        val advertisedSampleRates = activeDevice?.sampleRates?.toList() ?: emptyList()

        val negotiation = OutputNegotiator.negotiate(
            source = source,
            decoderName = AudioOutputStatus.current.value.decoderName,
            sampleRateHz = sampleRate,
            channelCount = channels,
            routeKind = routeKind,
            deviceName = activeDevice?.productName?.toString()?.ifBlank { null } ?: "System default",
            advertisedEncodings = advertisedEncodings,
            advertisedSampleRates = advertisedSampleRates,
            requestedMode = AppSettings.outputPcmMode.value,
            directSupport = directSupport,
            bluetoothTelemetry = btTelemetry,
            delegateSupportsFloat = shouldEnableFloatOutput(),
            // Never, on any route. `DefaultAudioSink.configure` inserts either
            // `ToFloatPcmAudioProcessor` or `ToInt16PcmAudioProcessor` for every
            // linear-PCM input encoding, so there is no packed-24-bit AudioTrack
            // to be had through it however loudly the device advertises one.
            // Claiming otherwise here selected a 24-bit path that Media3 then
            // silently re-converted, costing an extra quantization and leaving
            // this readout naming a bit depth that was never written.
            delegateSupportsPcm24 = false,
            knownSystemMixerRateHz = if (routeKind == AudioRouting.Kind.USB) 48000 else null,
        )

        AudioOutputStatus.publishNegotiation(negotiation)

        return negotiation.output.encoding
    }

    fun requestOutputReconfiguration(rebuild: Boolean = false) {
        outputReconfigureJob?.cancel()
        outputReconfigureJob = scope.launch {
            // Replacing two renderers during a blend would cut one half of it.
            // Wait for the short transition to settle, then swap the engine.
            while (transitioning()) delay(50)
            val requestedFloat = shouldEnableFloatOutput()
            if (requestedFloat == configuredFloatOutput && !rebuild) {
                applyOutputRoute()
            } else {
                rebuildPlayers(requestedFloat)
            }
        }
    }

    private fun preferredUsbDevice(): AudioDeviceInfo? = audioManager
        ?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        ?.firstOrNull { device -> isUsbOutput(device.type) }

    private fun resolveActiveOutputDevice(): AudioDeviceInfo? {
        val manager = audioManager ?: return null
        val infos = runCatching { manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS) }.getOrNull() ?: return null
        val chosen = AudioRouting.infoFor(manager, AudioRouting.selectedId.value)
        val preferred = chosen ?: preferredUsbDevice().takeIf { AppSettings.preferUsbDac.value }
        return preferred ?: AudioRouting.activeOf(infos, null)
    }

    private fun resolveActiveRouteKind(device: AudioDeviceInfo?): AudioRouting.Kind {
        if (device == null) return AudioRouting.Kind.PHONE
        return AudioRouting.kindOf(device.type) ?: AudioRouting.Kind.PHONE
    }

    fun shouldEnableFloatOutput(): Boolean {
        val activeDevice = resolveActiveOutputDevice()
        val routeKind = resolveActiveRouteKind(activeDevice)
        val advertisesFloat = activeDevice?.encodings?.contains(AudioFormat.ENCODING_PCM_FLOAT) == true
        val directFloatSupported = audioManager?.let { mgr ->
            DirectAudioProbe.probeDirectSupport(mgr, 48000, 2, activeDevice).supportsFloat
        } ?: false
        // Worth knowing which way this cuts for a hi-res stream: with float
        // off, Media3 downconverts *every* input encoding to 16-bit, because
        // float and 16-bit are the only two linear-PCM tracks its sink can
        // open. Float is what carries a 24-bit sample to the hardware intact,
        // so "32-bit float" in Output precision is the setting that preserves
        // a hi-res source, not the one that embellishes it.
        return AudioOutputPolicy.shouldUseFloatOutput(
            requestedMode = AppSettings.outputPcmMode.value,
            routeKind = routeKind,
            advertisesPcmFloat = advertisesFloat || directFloatSupported,
            bluetoothCodecBitDepth = bluetoothCodecBitDepth(routeKind),
        )
    }

    /** The negotiated A2DP bit depth, only when Android actually reported it. */
    private fun bluetoothCodecBitDepth(routeKind: AudioRouting.Kind): Int? {
        if (routeKind != AudioRouting.Kind.BLUETOOTH) return null
        val telemetry = bluetoothTracker.telemetry.value
        return telemetry.bitDepth.takeIf { telemetry.isConnected && telemetry.isAuthoritative }
    }

    /**
     * Renders the equaliser settings into a curve and hands it to both
     * processors.
     *
     * Both, and not just the audible one, for the reason [applySettings] gives:
     * the idle player is the one the next transition starts a song on, and a
     * blend whose two halves were equalised differently would sweep the curve in
     * over the crossfade.
     *
     * The curve is built here rather than in the processor because working out
     * the make-up attenuation walks the whole response ([EqCurve]), and the
     * audio thread is the one place that must not do that.
     */
    fun applyEqualizer() {
        val enabled = AppSettings.equalizerEnabled.value
        val curve = when (AppSettings.equalizerMode.value) {
            EqualizerMode.DYNAMIC -> toneCurve(
                x = AppSettings.equalizerToneX.value,
                y = AppSettings.equalizerToneY.value,
                focused = AppSettings.equalizerFocused.value,
            )
            EqualizerMode.MANUAL -> manualCurve(AppSettings.equalizerBands.value)
        }
        val balance = AppSettings.equalizerBalance.value
        a.equalizer.setTuning(enabled, curve, balance)
        b.equalizer.setTuning(enabled, curve, balance)
    }

    /**
     * The effective spatial-audio state: the user's toggle, unless the
     * active player's decoder is currently on a Dolby Atmos (E-AC-3 JOC)
     * stream, in which case it's forced off. [activeTrackIsDolbyAtmos] only
     * ever describes the active player — see [formatListener] — so both
     * processors are kept in lockstep rather than tracking a role swap.
     */
    fun applySingLevel() {
        val level = AppSettings.singLevel.value
        a.sing.level = level
        b.sing.level = level
    }

    fun applySingMethod() {
        val method = AppSettings.singMethod.value
        a.sing.setMethod(method)
        b.sing.setMethod(method)
    }

    fun applySpatialAudioEnabled() {
        val enabled = AppSettings.spatialAudio.value && !activeTrackIsDolbyAtmos
        a.spatial.enabled = enabled
        b.spatial.enabled = enabled
    }

    companion object {
        /** Shortest gap "skip silence" is allowed to touch. */
        const val MIN_SILENCE_US = 1_000_000L

        /** Whether an output device of [type] is a USB DAC, headset or accessory. */
        fun isUsbOutput(type: Int): Boolean =
            type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                type == AudioDeviceInfo.TYPE_USB_ACCESSORY
    }
}
