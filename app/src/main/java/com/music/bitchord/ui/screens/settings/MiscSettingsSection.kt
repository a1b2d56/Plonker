package com.music.bitchord.ui.screens.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import com.music.bitchord.ui.components.IosMotion
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R

import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.ui.components.IosSwitch
import com.music.bitchord.ui.components.languageDisplayNameRes
import com.music.bitchord.ui.icons.SfIcons
import com.music.bitchord.ui.performance.resolvePerformanceRefreshRate
import com.music.bitchord.ui.performance.supportedPerformanceRefreshRates
import java.util.Locale

@Composable
internal fun PerformanceSettingsGroup(
    search: SettingsSearch,
    onRequestEnablePerformance: () -> Unit,
) {
    val highPerformanceMode by AppSettings.highPerformanceMode.collectAsStateWithLifecycle()
    val performanceRefreshRate by AppSettings.performanceRefreshRate.collectAsStateWithLifecycle()
    val currentDisplay = LocalView.current.display
    val supportedRefreshRates = remember(currentDisplay) {
        currentDisplay.supportedPerformanceRefreshRates()
    }
    val selectedPerformanceRefreshRate = remember(currentDisplay, performanceRefreshRate) {
        currentDisplay.resolvePerformanceRefreshRate(performanceRefreshRate)
    }

    LaunchedEffect(selectedPerformanceRefreshRate, performanceRefreshRate) {
        if (selectedPerformanceRefreshRate != performanceRefreshRate) {
            AppSettings.setPerformanceRefreshRate(selectedPerformanceRefreshRate)
        }
    }

    SearchableSettingsGroup(search, header = stringResource(R.string.performance)) {
        val highPerformanceModeTitle = stringResource(R.string.high_performance_mode)
        row(highPerformanceModeTitle, "frame rate", "refresh rate", "hz", "battery", "smooth") {
            SettingsRow(
                icon = SfIcons.Performance,
                title = highPerformanceModeTitle,
                subtitle = if (highPerformanceMode) {
                    stringResource(R.string.high_performance_active, selectedPerformanceRefreshRate)
                } else {
                    stringResource(R.string.high_performance_subtitle)
                },
                trailing = {
                    IosSwitch(
                        checked = highPerformanceMode,
                        onCheckedChange = { enabled ->
                            if (enabled) {
                                onRequestEnablePerformance()
                            } else {
                                AppSettings.setHighPerformanceMode(false)
                            }
                        },
                    )
                },
                onClick = {
                    if (highPerformanceMode) {
                        AppSettings.setHighPerformanceMode(false)
                    } else {
                        onRequestEnablePerformance()
                    }
                },
            )
        }
        val refreshRateTitle = stringResource(R.string.refresh_rate)
        row(refreshRateTitle, "hz", "frame rate", divided = false) {
            AnimatedVisibility(
                visible = highPerformanceMode,
                enter = expandVertically(IosMotion.cardSpring()) + fadeIn(),
                exit = shrinkVertically(IosMotion.cardSpring()) + fadeOut(),
            ) {
                Column {
                    RowDivider()
                    SettingsRow(
                        icon = SfIcons.FrameRate,
                        title = refreshRateTitle,
                    )
                    SegmentedControl(
                        options = supportedRefreshRates.map { "$it Hz" },
                        selectedIndex = supportedRefreshRates.indexOf(selectedPerformanceRefreshRate),
                        onSelect = { index ->
                            AppSettings.setPerformanceRefreshRate(supportedRefreshRates[index])
                        },
                        modifier = Modifier.padding(
                            start = TEXT_INSET,
                            end = ROW_INSET,
                            bottom = 14.dp,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
internal fun MiscellaneousSettingsGroup(
    search: SettingsSearch,
) {
    val context = LocalContext.current
    val swipeToPlayNext by AppSettings.swipeToPlayNext.collectAsStateWithLifecycle()
    val dontRepeatSuggestions by AppSettings.dontRepeatSuggestions.collectAsStateWithLifecycle()
    val stopOnTaskRemoved by AppSettings.stopOnTaskRemoved.collectAsStateWithLifecycle()
    val hideVolumeBar by AppSettings.hideVolumeBar.collectAsStateWithLifecycle()
    val listenTogetherButton by AppSettings.showListenTogetherButton.collectAsStateWithLifecycle()
    val hideStatusBarInPlayer by AppSettings.hideStatusBarInPlayer.collectAsStateWithLifecycle()
    val showQueueHistory by AppSettings.showQueueHistory.collectAsStateWithLifecycle()
    val hideSongStatus by AppSettings.hideSongStatus.collectAsStateWithLifecycle()
    val smartVersionAlignment by AppSettings.smartVersionAlignment.collectAsStateWithLifecycle()
    val nerdStats by AppSettings.showNerdStats.collectAsStateWithLifecycle()

    SearchableSettingsGroup(search, header = stringResource(R.string.miscellaneous)) {
        val playNextOnSwipeTitle = stringResource(R.string.play_next_on_swipe)
        row(playNextOnSwipeTitle, "swipe", "queue") {
            SettingsRow(
                icon = SfIcons.PlaylistPlay,
                title = playNextOnSwipeTitle,
                subtitle = if (swipeToPlayNext) {
                    stringResource(R.string.swipe_plays_next)
                } else {
                    stringResource(R.string.swipe_adds_to_queue)
                },
                trailing = {
                    IosSwitch(
                        checked = swipeToPlayNext,
                        onCheckedChange = AppSettings::setSwipeToPlayNext,
                    )
                },
                onClick = { AppSettings.setSwipeToPlayNext(!swipeToPlayNext) },
            )
        }
        val dontRepeatSongsTitle = stringResource(R.string.dont_repeat_songs)
        row(dontRepeatSongsTitle, "radio", "suggestions") {
            SettingsRow(
                icon = SfIcons.History,
                title = dontRepeatSongsTitle,
                subtitle = stringResource(R.string.dont_repeat_songs_subtitle),
                trailing = {
                    IosSwitch(
                        checked = dontRepeatSuggestions,
                        onCheckedChange = AppSettings::setDontRepeatSuggestions,
                    )
                },
                onClick = { AppSettings.setDontRepeatSuggestions(!dontRepeatSuggestions) },
            )
        }
        val stopMusicOnCloseTitle = stringResource(R.string.stop_music_on_close)
        row(stopMusicOnCloseTitle, "notification", "background") {
            SettingsRow(
                icon = SfIcons.MusicOff,
                title = stopMusicOnCloseTitle,
                subtitle = stringResource(R.string.stop_music_on_close_subtitle),
                trailing = {
                    IosSwitch(
                        checked = stopOnTaskRemoved,
                        onCheckedChange = AppSettings::setStopOnTaskRemoved,
                    )
                },
                onClick = { AppSettings.setStopOnTaskRemoved(!stopOnTaskRemoved) },
            )
        }
        val hideVolumeBarTitle = stringResource(R.string.hide_volume_bar)
        row(hideVolumeBarTitle, "volume", "player") {
            SettingsRow(
                icon = SfIcons.VolumeOff,
                title = hideVolumeBarTitle,
                subtitle = stringResource(R.string.hide_volume_bar_subtitle),
                trailing = {
                    IosSwitch(
                        checked = hideVolumeBar,
                        onCheckedChange = AppSettings::setHideVolumeBar,
                    )
                },
                onClick = { AppSettings.setHideVolumeBar(!hideVolumeBar) },
            )
        }
        val listenTogetherButtonTitle = stringResource(R.string.show_listen_together_button)
        row(listenTogetherButtonTitle, "party", "jam", "output", "headphones", "player") {
            SettingsRow(
                icon = SfIcons.Groups,
                title = listenTogetherButtonTitle,
                subtitle = stringResource(R.string.show_listen_together_button_subtitle),
                trailing = {
                    IosSwitch(
                        checked = listenTogetherButton,
                        onCheckedChange = AppSettings::setShowListenTogetherButton,
                    )
                },
                onClick = { AppSettings.setShowListenTogetherButton(!listenTogetherButton) },
            )
        }
        val hideStatusBarTitle = stringResource(R.string.hide_status_bar_in_player)
        row(hideStatusBarTitle, "status bar", "immersive", "fullscreen", "player") {
            SettingsRow(
                icon = SfIcons.WebAssetOff,
                title = hideStatusBarTitle,
                subtitle = stringResource(R.string.hide_status_bar_in_player_subtitle),
                trailing = {
                    IosSwitch(
                        checked = hideStatusBarInPlayer,
                        onCheckedChange = AppSettings::setHideStatusBarInPlayer,
                    )
                },
                onClick = { AppSettings.setHideStatusBarInPlayer(!hideStatusBarInPlayer) },
            )
        }
        val showQueueHistoryTitle = stringResource(R.string.show_queue_history)
        row(showQueueHistoryTitle, "player", "queue", "history", "previously played") {
            SettingsRow(
                icon = SfIcons.History,
                title = showQueueHistoryTitle,
                subtitle = stringResource(R.string.show_queue_history_subtitle),
                trailing = {
                    IosSwitch(
                        checked = showQueueHistory,
                        onCheckedChange = AppSettings::setShowQueueHistory,
                    )
                },
                onClick = { AppSettings.setShowQueueHistory(!showQueueHistory) },
            )
        }
        val hideSongStatusTitle = stringResource(R.string.hide_song_status)
        row(hideSongStatusTitle, "player", "playing from", "played by") {
            SettingsRow(
                icon = SfIcons.VisibilityOff,
                title = hideSongStatusTitle,
                subtitle = stringResource(R.string.hide_song_status_subtitle),
                trailing = {
                    IosSwitch(
                        checked = hideSongStatus,
                        onCheckedChange = AppSettings::setHideSongStatus,
                    )
                },
                onClick = { AppSettings.setHideSongStatus(!hideSongStatus) },
            )
        }
        val smartAlignmentTitle = stringResource(R.string.smart_version_alignment)
        row(smartAlignmentTitle, "alignment", "sync", "waveform", "video", "audio", "skit", "intro") {
            SettingsRow(
                icon = SfIcons.GraphicEq,
                title = smartAlignmentTitle,
                subtitle = stringResource(R.string.smart_version_alignment_subtitle),
                trailing = {
                    IosSwitch(
                        checked = smartVersionAlignment,
                        onCheckedChange = AppSettings::setSmartVersionAlignment,
                    )
                },
                onClick = { AppSettings.setSmartVersionAlignment(!smartVersionAlignment) },
            )
        }
        val showNerdStatsTitle = stringResource(R.string.show_nerd_stats)
        row(showNerdStatsTitle, "debug", "bitrate", "codec") {
            SettingsRow(
                icon = SfIcons.BarChart,
                title = showNerdStatsTitle,
                subtitle = stringResource(R.string.show_nerd_stats_subtitle),
                trailing = {
                    IosSwitch(
                        checked = nerdStats,
                        onCheckedChange = AppSettings::setShowNerdStats,
                    )
                },
                onClick = { AppSettings.setShowNerdStats(!nerdStats) },
            )
        }
    }
}

@Composable
internal fun LanguageSettingsGroup(
    search: SettingsSearch,
    onAppLanguage: () -> Unit,
) {
    SearchableSettingsGroup(search, header = stringResource(R.string.language)) {
        val appLanguageTitle = stringResource(R.string.app_language)
        row(appLanguageTitle, "locale", "translate") {
            val selectedLanguage = AppCompatDelegate.getApplicationLocales().get(0)?.toLanguageTag()
                ?: Locale.getDefault().toLanguageTag()
            SettingsRow(
                icon = SfIcons.Language,
                title = appLanguageTitle,
                subtitle = stringResource(languageDisplayNameRes(selectedLanguage)),
                onClick = onAppLanguage,
            )
        }
    }
}

@Composable
internal fun PerformanceWarningDialog(
    onDismiss: () -> Unit,
    onOpenBatterySettings: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.high_performance_before_enabling)) },
        text = { Text(stringResource(R.string.high_performance_battery_warning)) },
        confirmButton = {
            TextButton(onClick = onOpenBatterySettings) {
                Text(stringResource(R.string.open_battery_settings))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
internal fun PerformanceConfirmationDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.enable_high_performance_title)) },
        text = { Text(stringResource(R.string.enable_high_performance_confirmation)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.enable))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.not_yet))
            }
        },
    )
}
