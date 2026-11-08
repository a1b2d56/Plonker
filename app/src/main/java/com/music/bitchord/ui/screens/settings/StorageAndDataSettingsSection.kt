package com.music.bitchord.ui.screens.settings

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.SingletonImageLoader
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.music.bitchord.playback.AudioCache
import com.music.bitchord.R
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.ui.components.IosSwitch
import com.music.bitchord.ui.icons.SfIcons
import kotlin.math.roundToInt

@Composable
internal fun StorageSettingsGroup(
    search: SettingsSearch,
    onClearSongCache: () -> Unit,
) {
    val context = LocalContext.current
    val showCacheFolder by AppSettings.showCacheFolder.collectAsStateWithLifecycle()
    val cacheLimitBytes by AppSettings.audioCacheLimitBytes.collectAsStateWithLifecycle()

    val cacheUnlimited = cacheLimitBytes == AppSettings.UNLIMITED_CACHE_LIMIT_BYTES
    val cacheLimitMb = if (cacheUnlimited) CACHE_UNLIMITED_STOP_MB else (cacheLimitBytes / (1024 * 1024)).toInt()
    val unlimitedLabel = stringResource(R.string.song_cache_unlimited)
    val cacheLimitLabel = if (cacheUnlimited) unlimitedLabel else formatCacheSize(cacheLimitMb)

    SearchableSettingsGroup(search, header = stringResource(R.string.storage)) {
        val showCacheFolderTitle = stringResource(R.string.show_cache_folder)
        row(showCacheFolderTitle, "cache", "cached songs", "library", "folder") {
            SettingsRow(
                icon = SfIcons.Cached,
                title = showCacheFolderTitle,
                subtitle = stringResource(R.string.show_cache_folder_subtitle),
                trailing = {
                    IosSwitch(
                        checked = showCacheFolder,
                        onCheckedChange = AppSettings::setShowCacheFolder,
                    )
                },
                onClick = { AppSettings.setShowCacheFolder(!showCacheFolder) },
            )
        }
        val songCacheLimitTitle = stringResource(R.string.song_cache_limit)
        row(songCacheLimitTitle, "cache", "space", "unlimited") {
            SliderRow(
                icon = SfIcons.Storage,
                title = songCacheLimitTitle,
                subtitle = when {
                    cacheUnlimited -> stringResource(R.string.song_cache_unlimited_subtitle)
                    cacheLimitMb > CACHE_WARNING_MB ->
                        stringResource(R.string.song_cache_large_subtitle, cacheLimitLabel)
                    else -> stringResource(R.string.song_cache_limit_subtitle)
                },
                subtitleMinLines = 3,
                value = cacheLimitLabel,
                sliderValue = cacheLimitMb.toFloat(),
                onSliderValue = {
                    AppSettings.setAudioCacheLimitBytes(it.roundToInt().toLong() * 1024 * 1024)
                },
                valueRange = (AppSettings.DEFAULT_CACHE_LIMIT_BYTES / (1024 * 1024)).toFloat()..
                    CACHE_UNLIMITED_STOP_MB.toFloat(),
                steps = 19,
            )
        }
        val clearSongCacheTitle = stringResource(R.string.clear_song_cache)
        row(clearSongCacheTitle, "cache", "free space") {
            SettingsRow(
                icon = SfIcons.DeleteSweep,
                title = clearSongCacheTitle,
                subtitle = stringResource(R.string.clear_song_cache_subtitle),
                onClick = onClearSongCache,
            )
        }
        val clearImageCacheTitle = stringResource(R.string.clear_image_cache)
        row(clearImageCacheTitle, "cache", "artwork", "free space") {
            SettingsRow(
                icon = SfIcons.DeleteSweep,
                title = clearImageCacheTitle,
                subtitle = stringResource(R.string.clear_image_cache_subtitle),
                onClick = {
                    val loader = SingletonImageLoader.get(context)
                    loader.memoryCache?.clear()
                    loader.diskCache?.clear()
                    Toast.makeText(context, context.getString(R.string.image_cache_cleared), Toast.LENGTH_SHORT).show()
                },
            )
        }
    }
}

@Composable
internal fun YourDataSettingsGroup(
    search: SettingsSearch,
    exportStatus: String?,
    importStatus: String?,
    onOpenReplay: () -> Unit,
    onExportData: () -> Unit,
    onImportData: () -> Unit,
) {
    val replayGenres by AppSettings.replayGenres.collectAsStateWithLifecycle()

    SearchableSettingsGroup(search, header = stringResource(R.string.your_data)) {
        val replayTitle = stringResource(R.string.replay)
        row(replayTitle, "stats", "history", "wrapped") {
            SettingsRow(
                icon = SfIcons.BarChart,
                title = replayTitle,
                subtitle = stringResource(R.string.replay_subtitle),
                onClick = onOpenReplay,
            )
        }
        val workOutGenresTitle = stringResource(R.string.work_out_genres)
        row(workOutGenresTitle, "genre", "replay") {
            SettingsRow(
                icon = SfIcons.LocalOffer,
                title = workOutGenresTitle,
                subtitle = if (replayGenres) {
                    stringResource(R.string.replay_genres_enabled_subtitle)
                } else {
                    stringResource(R.string.replay_genres_disabled_subtitle)
                },
                trailing = {
                    IosSwitch(
                        checked = replayGenres,
                        onCheckedChange = AppSettings::setReplayGenres,
                    )
                },
                onClick = { AppSettings.setReplayGenres(!replayGenres) },
            )
        }
        val exportDataTitle = stringResource(R.string.export_data)
        row(exportDataTitle, "backup") {
            SettingsRow(
                icon = SfIcons.FileUpload,
                title = exportDataTitle,
                subtitle = exportStatus ?: stringResource(R.string.export_data_subtitle),
                onClick = onExportData,
            )
        }
        val importDataTitle = stringResource(R.string.import_data)
        row(importDataTitle, "backup", "restore") {
            SettingsRow(
                icon = SfIcons.FileDownload,
                title = importDataTitle,
                subtitle = importStatus ?: stringResource(R.string.import_data_subtitle),
                onClick = onImportData,
            )
        }
    }
}

@Composable
internal fun ClearSongCacheDialog(
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.clear_song_cache_title)) },
        text = { Text(stringResource(R.string.clear_song_cache_warning)) },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                AudioCache.clear {
                    Toast.makeText(context, context.getString(R.string.song_cache_cleared), Toast.LENGTH_SHORT).show()
                }
            }) {
                Text(stringResource(R.string.clear))
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
internal fun ImportBackupDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.import_backup_title)) },
        text = {
            Text(stringResource(R.string.import_backup_warning))
        },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                onConfirm()
            }) {
                Text(stringResource(R.string.choose_file))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
