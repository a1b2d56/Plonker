package com.music.bitchord.ui.screens.settings

import android.media.AudioFormat
import androidx.compose.foundation.layout.Column
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import com.music.bitchord.ui.components.IosMotion
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.NerdStats
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.BitPerfectMode
import com.music.bitchord.data.settings.OutputPcmMode
import com.music.bitchord.playback.AudioOutputStatus
import com.music.bitchord.playback.audio.bitperfect.BitPerfectOutput
import com.music.bitchord.ui.components.IosSwitch
import com.music.bitchord.ui.icons.SfIcons
import kotlin.math.roundToInt

@Composable
internal fun DownloadsSettingsGroup(
    search: SettingsSearch,
    onOpenChoice: (SettingsChoiceKind) -> Unit,
) {
    val downloadQuality by AppSettings.downloadQuality.collectAsStateWithLifecycle()
    val wifiOnlyDownloads by AppSettings.wifiOnlyDownloads.collectAsStateWithLifecycle()
    val exportDownloads by AppSettings.exportDownloads.collectAsStateWithLifecycle()
    val syncDownloadedPlaylists by AppSettings.syncDownloadedPlaylists.collectAsStateWithLifecycle()
    val metered by AppSettings.meteredConnection.collectAsStateWithLifecycle()

    SearchableSettingsGroup(search, header = stringResource(R.string.downloads)) {
        val downloadQualityTitle = stringResource(R.string.download_quality)
        row(downloadQualityTitle, "offline", "lossless") {
            SettingsRow(
                icon = SfIcons.Download,
                title = downloadQualityTitle,
                subtitle = stringResource(R.string.download_quality_subtitle, downloadQuality.perTrack),
                value = downloadQuality.localizedLabel(),
                onClick = { onOpenChoice(SettingsChoiceKind.DOWNLOAD_QUALITY) },
            )
        }
        val downloadWifiOnlyTitle = stringResource(R.string.download_wifi_only)
        row(downloadWifiOnlyTitle, "wi-fi", "cellular", divided = false) {
            SettingsSubRow(
                title = downloadWifiOnlyTitle,
                checked = wifiOnlyDownloads,
                onCheckedChange = AppSettings::setWifiOnlyDownloads,
                badge = stringResource(R.string.blocking).takeIf { wifiOnlyDownloads && metered == true },
            )
        }
        val exportDownloadsTitle = stringResource(R.string.export_compatible_downloads)
        row(exportDownloadsTitle, "music folder") {
            SettingsSubRow(
                title = exportDownloadsTitle,
                checked = exportDownloads,
                onCheckedChange = AppSettings::setExportDownloads,
                subtitle = "Music/Plonker".takeIf { exportDownloads },
            )
        }
        val syncPlaylistsTitle = stringResource(R.string.sync_downloaded_playlists)
        row(syncPlaylistsTitle, "playlist", "new songs", "background", "automatic") {
            SettingsSubRow(
                title = syncPlaylistsTitle,
                checked = syncDownloadedPlaylists,
                onCheckedChange = AppSettings::setSyncDownloadedPlaylists,
                subtitle = stringResource(R.string.sync_downloaded_playlists_subtitle),
            )
        }
    }
}

@Composable
internal fun PlaybackSettingsGroup(
    search: SettingsSearch,
    onOpenChoice: (SettingsChoiceKind) -> Unit,
    onEqualizer: () -> Unit,
    onConfirmFixedVolume: () -> Unit,
) {
    val outputStatus by AudioOutputStatus.current.collectAsStateWithLifecycle()
    val outputPcmMode by AppSettings.outputPcmMode.collectAsStateWithLifecycle()
    val preferUsbDac by AppSettings.preferUsbDac.collectAsStateWithLifecycle()
    val bitPerfectMode by AppSettings.bitPerfectMode.collectAsStateWithLifecycle()
    val bitPerfectStatus by BitPerfectOutput.status.collectAsStateWithLifecycle()
    val bitPerfectReady by BitPerfectOutput.ready.collectAsStateWithLifecycle()
    val dacVolume by BitPerfectOutput.hardwareVolume.collectAsStateWithLifecycle()
    val usbAllowFixedVolume by AppSettings.usbAllowFixedVolume.collectAsStateWithLifecycle()
    val convertForLimitedDac by AppSettings.convertForLimitedDac.collectAsStateWithLifecycle()
    val loudnessNormalization by AppSettings.loudnessNormalization.collectAsStateWithLifecycle()
    val loudnessOffOnSpeaker by AppSettings.loudnessOffOnSpeaker.collectAsStateWithLifecycle()
    val crossfade by AppSettings.crossfadeSeconds.collectAsStateWithLifecycle()
    val smartFade by AppSettings.smartFadeEnabled.collectAsStateWithLifecycle()
    val automixPerformance by AppSettings.automixPerformanceMode.collectAsStateWithLifecycle()
    val skipSilence by AppSettings.skipSilence.collectAsStateWithLifecycle()
    val spatialAudio by AppSettings.spatialAudio.collectAsStateWithLifecycle()
    val playingFormat by NerdStats.current.collectAsStateWithLifecycle()
    val playingDolbyAtmos = playingFormat?.isDolbyAtmos == true

    SearchableSettingsGroup(
        search,
        header = stringResource(R.string.playback),
        footer = stringResource(R.string.bit_perfect_bypassed_footer).takeIf { bitPerfectReady },
    ) {
        val outputPrecisionTitle = stringResource(R.string.output_precision)
        row(outputPrecisionTitle, "pcm", "bit depth", "sample rate", "dac") {
            SettingsRow(
                icon = SfIcons.GraphicEq,
                title = outputPrecisionTitle,
                subtitle = buildString {
                    append(outputStatus.sink)
                    append(" · ")
                    append(outputStatus.deviceName)
                    (outputStatus.actualSampleRateHz ?: outputStatus.sampleRatesHz.firstOrNull())
                        ?.let { append(" · ${it / 1000.0} kHz") }
                    append(" · ")
                    append(AudioOutputStatus.encodingLabel(outputStatus))
                },
            )
            SegmentedControl(
                options = OutputPcmMode.entries.map(OutputPcmMode::label),
                selectedIndex = OutputPcmMode.entries.indexOf(outputPcmMode),
                onSelect = { AppSettings.setOutputPcmMode(OutputPcmMode.entries[it]) },
                modifier = Modifier.padding(start = TEXT_INSET, end = ROW_INSET, bottom = 14.dp),
            )
        }
        val preferUsbDacTitle = stringResource(R.string.prefer_usb_dac)
        row(preferUsbDacTitle, "headphone", "output") {
            SettingsSubRow(
                title = preferUsbDacTitle,
                checked = preferUsbDac,
                onCheckedChange = AppSettings::setPreferUsbDac,
                badge = stringResource(R.string.connected).takeIf { outputStatus.isUsb },
            )
        }
        val bitPerfectTitle = stringResource(R.string.bit_perfect_output)
        row(bitPerfectTitle, "usb", "dac", "exclusive", "hi-res", "bitperfect") {
            SettingsRow(
                icon = SfIcons.Usb,
                title = bitPerfectTitle,
                subtitleContent = {
                    Column {
                        Text(
                            text = bitPerfectSubtitle(bitPerfectMode, bitPerfectStatus),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = bitPerfectMode.localizedLabel(),
                            modifier = Modifier.padding(top = 2.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                },
                onClick = { onOpenChoice(SettingsChoiceKind.BIT_PERFECT) },
            )
        }
        dacVolume?.let { volume ->
            val dacVolumeTitle = stringResource(R.string.dac_volume)
            row(dacVolumeTitle, "volume", "usb", "dac", divided = false) {
                SliderRow(
                    icon = SfIcons.VolumeUp,
                    title = dacVolumeTitle,
                    subtitle = stringResource(R.string.dac_volume_subtitle),
                    value = "%.1f dB".format(volume.currentDb),
                    sliderValue = volume.currentDb.toFloat(),
                    onSliderValue = { BitPerfectOutput.setHardwareVolume(it.toDouble()) },
                    valueRange = volume.minDb.toFloat()..volume.maxDb.toFloat(),
                    steps = 0,
                )
            }
        }
        val fixedVolumeTitle = stringResource(R.string.usb_allow_fixed_volume)
        row(fixedVolumeTitle, "volume", "usb", "dac", divided = false) {
            AnimatedVisibility(
                visible = bitPerfectMode == BitPerfectMode.USB_DIRECT,
                enter = expandVertically(IosMotion.cardSpring()) + fadeIn(),
                exit = shrinkVertically(IosMotion.cardSpring()) + fadeOut(),
            ) {
                Column {
                    RowDivider()
                    SettingsSubRow(
                        title = fixedVolumeTitle,
                        subtitle = stringResource(R.string.usb_allow_fixed_volume_subtitle),
                        checked = usbAllowFixedVolume,
                        onCheckedChange = { on ->
                            if (on) onConfirmFixedVolume() else AppSettings.setUsbAllowFixedVolume(false)
                        },
                    )
                }
            }
        }
        val convertTitle = stringResource(R.string.convert_for_limited_dac)
        row(convertTitle, "convert", "resample", "usb", "dac", "bitperfect", divided = false) {
            AnimatedVisibility(
                visible = bitPerfectMode != BitPerfectMode.OFF && bitPerfectMode.isSupported,
                enter = expandVertically(IosMotion.cardSpring()) + fadeIn(),
                exit = shrinkVertically(IosMotion.cardSpring()) + fadeOut(),
            ) {
                Column {
                    RowDivider()
                    SettingsSubRow(
                        title = convertTitle,
                        subtitle = stringResource(R.string.convert_for_limited_dac_subtitle),
                        checked = convertForLimitedDac,
                        onCheckedChange = AppSettings::setConvertForLimitedDac,
                    )
                }
            }
        }
        val loudnessTitle = stringResource(R.string.loudness_normalization)
        row(loudnessTitle, "loudness", "volume", "normalize", "replaygain", "lufs") {
            BypassedByBitPerfect(bitPerfectReady) {
                SettingsRow(
                    icon = SfIcons.VolumeUp,
                    title = loudnessTitle,
                    subtitle = stringResource(R.string.loudness_normalization_subtitle),
                    trailing = {
                        IosSwitch(
                            checked = loudnessNormalization,
                            onCheckedChange = AppSettings::setLoudnessNormalization,
                        )
                    },
                    onClick = { AppSettings.setLoudnessNormalization(!loudnessNormalization) },
                )
            }
        }
        val loudnessSpeakerTitle = stringResource(R.string.loudness_off_on_speaker)
        row(loudnessSpeakerTitle, "loudness", "speaker", "normalize", divided = false) {
            AnimatedVisibility(
                visible = loudnessNormalization,
                enter = expandVertically(IosMotion.cardSpring()) + fadeIn(),
                exit = shrinkVertically(IosMotion.cardSpring()) + fadeOut(),
            ) {
                Column {
                    RowDivider()
                    BypassedByBitPerfect(bitPerfectReady) {
                        SettingsSubRow(
                            title = loudnessSpeakerTitle,
                            checked = loudnessOffOnSpeaker,
                            onCheckedChange = AppSettings::setLoudnessOffOnSpeaker,
                        )
                    }
                }
            }
        }
        val crossfadeTitle = stringResource(R.string.crossfade)
        row(crossfadeTitle, "fade", "gapless", divided = false) {
            AnimatedVisibility(
                visible = !smartFade,
                enter = expandVertically(IosMotion.cardSpring()) + fadeIn(),
                exit = shrinkVertically(IosMotion.cardSpring()) + fadeOut(),
            ) {
                Column {
                    RowDivider()
                    BypassedByBitPerfect(bitPerfectReady) {
                        SliderRow(
                            icon = SfIcons.Waves,
                            title = crossfadeTitle,
                            subtitle = stringResource(R.string.crossfade_subtitle),
                            value = if (crossfade == 0) stringResource(R.string.off) else "${crossfade}s",
                            sliderValue = crossfade.toFloat(),
                            onSliderValue = { AppSettings.setCrossfadeSeconds(it.roundToInt()) },
                            valueRange = 0f..12f,
                            steps = 11,
                        )
                    }
                }
            }
        }
        val automixTitle = stringResource(R.string.automix)
        row(automixTitle, "crossfade", "smart fade", "mix") {
            BypassedByBitPerfect(bitPerfectReady) {
                SettingsRow(
                    icon = SfIcons.AutoAwesome,
                    title = automixTitle,
                    subtitle = if (smartFade) {
                        stringResource(R.string.automix_enabled_subtitle)
                    } else {
                        stringResource(R.string.automix_disabled_subtitle)
                    },
                    trailing = {
                        IosSwitch(
                            checked = smartFade,
                            onCheckedChange = AppSettings::setSmartFadeEnabled,
                        )
                    },
                    onClick = { AppSettings.setSmartFadeEnabled(!smartFade) },
                )
            }
        }
        val automixPerformanceTitle = stringResource(R.string.automix_performance)
        row(automixPerformanceTitle, "cpu", "battery", divided = false) {
            AnimatedVisibility(
                visible = smartFade,
                enter = expandVertically(IosMotion.cardSpring()) + fadeIn(),
                exit = shrinkVertically(IosMotion.cardSpring()) + fadeOut(),
            ) {
                Column {
                    RowDivider()
                    BypassedByBitPerfect(bitPerfectReady) {
                        SettingsRow(
                            icon = SfIcons.Tune,
                            title = automixPerformanceTitle,
                            subtitle = stringResource(R.string.automix_performance_subtitle),
                            value = automixPerformance.localizedLabel(),
                            onClick = { onOpenChoice(SettingsChoiceKind.AUTOMIX_PERFORMANCE) },
                        )
                    }
                }
            }
        }
        val skipSilenceTitle = stringResource(R.string.skip_silence)
        row(skipSilenceTitle, "silence") {
            BypassedByBitPerfect(bitPerfectReady) {
                SettingsRow(
                    icon = SfIcons.VolumeOff,
                    title = skipSilenceTitle,
                    subtitle = if (outputStatus.actualEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
                        stringResource(R.string.skip_silence_float_subtitle)
                    } else {
                        stringResource(R.string.skip_silence_subtitle)
                    },
                    trailing = {
                        IosSwitch(
                            checked = skipSilence,
                            onCheckedChange = AppSettings::setSkipSilence,
                        )
                    },
                    onClick = { AppSettings.setSkipSilence(!skipSilence) },
                )
            }
        }
        val spatialAudioTitle = stringResource(R.string.spatial_audio)
        row(spatialAudioTitle, "surround", "3d") {
            BypassedByBitPerfect(bitPerfectReady) {
                SettingsRow(
                    icon = SfIcons.SurroundSound,
                    title = spatialAudioTitle,
                    subtitle = if (playingDolbyAtmos) {
                        stringResource(R.string.spatial_audio_atmos_subtitle)
                    } else {
                        stringResource(R.string.spatial_audio_subtitle)
                    },
                    trailing = {
                        IosSwitch(
                            checked = spatialAudio,
                            onCheckedChange = AppSettings::setSpatialAudio,
                        )
                    },
                    onClick = { AppSettings.setSpatialAudio(!spatialAudio) },
                )
            }
        }
        val equalizerTitle = stringResource(R.string.equalizer)
        row(equalizerTitle, "eq", "bass", "treble") {
            BypassedByBitPerfect(bitPerfectReady) {
                SettingsRow(
                    icon = SfIcons.Tune,
                    title = equalizerTitle,
                    subtitle = stringResource(R.string.equalizer_subtitle),
                    onClick = onEqualizer,
                )
            }
        }
    }
}
