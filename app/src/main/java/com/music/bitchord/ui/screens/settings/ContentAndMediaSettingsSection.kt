package com.music.bitchord.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.LocalMediaRepository
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.ui.components.IosSwitch
import com.music.bitchord.ui.icons.SfIcons

@Composable
internal fun ContentPreferencesSettingsGroup(
    search: SettingsSearch,
) {
    val preferMusicOnly by AppSettings.preferMusicOnly.collectAsStateWithLifecycle()
    val hideRecents by AppSettings.hideRecents.collectAsStateWithLifecycle()
    val hideHomeVideos by AppSettings.hideHomeVideos.collectAsStateWithLifecycle()
    val hideTopPicks by AppSettings.hideTopPicks.collectAsStateWithLifecycle()
    val hideHomeMoods by AppSettings.hideHomeMoods.collectAsStateWithLifecycle()
    val squareVideoThumbnails by AppSettings.squareVideoThumbnails.collectAsStateWithLifecycle()

    SearchableSettingsGroup(search, header = stringResource(R.string.content_preferences)) {
        val preferMusicOnlyTitle = stringResource(R.string.prefer_music_only)
        row(preferMusicOnlyTitle, "video", "audio", "music video", "convert", "song") {
            SettingsRow(
                icon = SfIcons.Video,
                title = preferMusicOnlyTitle,
                subtitle = stringResource(R.string.prefer_music_only_subtitle),
                trailing = {
                    IosSwitch(
                        checked = preferMusicOnly,
                        onCheckedChange = AppSettings::setPreferMusicOnly,
                    )
                },
                onClick = { AppSettings.setPreferMusicOnly(!preferMusicOnly) },
            )
        }
        val hideRecentsTitle = stringResource(R.string.hide_recents)
        row(hideRecentsTitle, "recents", "recent", "history", "feed", "home") {
            SettingsRow(
                icon = SfIcons.VisibilityOff,
                title = hideRecentsTitle,
                trailing = {
                    IosSwitch(
                        checked = hideRecents,
                        onCheckedChange = AppSettings::setHideRecents,
                    )
                },
                onClick = { AppSettings.setHideRecents(!hideRecents) },
            )
        }
        val hideHomeVideosTitle = stringResource(R.string.hide_home_videos)
        row(hideHomeVideosTitle, "video", "music video", "feed", "home") {
            SettingsRow(
                icon = SfIcons.VisibilityOff,
                title = hideHomeVideosTitle,
                subtitle = stringResource(R.string.hide_home_videos_subtitle),
                trailing = {
                    IosSwitch(
                        checked = hideHomeVideos,
                        onCheckedChange = AppSettings::setHideHomeVideos,
                    )
                },
                onClick = { AppSettings.setHideHomeVideos(!hideHomeVideos) },
            )
        }
        val hideTopPicksTitle = stringResource(R.string.hide_top_picks)
        row(hideTopPicksTitle, "top picks", "recommendations", "feed", "home") {
            SettingsRow(
                icon = SfIcons.AutoAwesome,
                title = hideTopPicksTitle,
                trailing = {
                    IosSwitch(
                        checked = hideTopPicks,
                        onCheckedChange = AppSettings::setHideTopPicks,
                    )
                },
                onClick = { AppSettings.setHideTopPicks(!hideTopPicks) },
            )
        }
        val hideHomeMoodsTitle = stringResource(R.string.hide_home_moods)
        row(hideHomeMoodsTitle, "mood", "moods", "find your mood", "feed", "home") {
            SettingsRow(
                icon = SfIcons.Star,
                title = hideHomeMoodsTitle,
                trailing = {
                    IosSwitch(
                        checked = hideHomeMoods,
                        onCheckedChange = AppSettings::setHideHomeMoods,
                    )
                },
                onClick = { AppSettings.setHideHomeMoods(!hideHomeMoods) },
            )
        }
        val squareVideoThumbnailsTitle = stringResource(R.string.square_video_thumbnails)
        row(squareVideoThumbnailsTitle, "video", "thumbnail", "cover", "artwork", "player") {
            SettingsRow(
                icon = SfIcons.Apps,
                title = squareVideoThumbnailsTitle,
                subtitle = stringResource(R.string.square_video_thumbnails_subtitle),
                trailing = {
                    IosSwitch(
                        checked = squareVideoThumbnails,
                        onCheckedChange = AppSettings::setSquareVideoThumbnails,
                    )
                },
                onClick = { AppSettings.setSquareVideoThumbnails(!squareVideoThumbnails) },
            )
        }
    }
}

@Composable
internal fun LocalMusicSettingsGroup(
    search: SettingsSearch,
    localMusicFolderUri: String,
    onPickLocalFolder: () -> Unit,
) {
    val filterNonMusicAudio by AppSettings.filterNonMusicAudio.collectAsStateWithLifecycle()

    SearchableSettingsGroup(search, header = stringResource(R.string.local_music)) {
        val localMusicFolderTitle = stringResource(R.string.local_music_folder)
        row(localMusicFolderTitle, "folder", "offline", "files") {
            SettingsRow(
                icon = SfIcons.Folder,
                title = localMusicFolderTitle,
                subtitle = LocalMediaRepository.selectedFolderLabel(localMusicFolderUri)
                    ?: stringResource(R.string.all_audio_folders),
                onClick = onPickLocalFolder,
            )
        }
        if (localMusicFolderUri.isNotBlank()) {
            val useAllAudioFoldersTitle = stringResource(R.string.use_all_audio_folders)
            row(useAllAudioFoldersTitle, "folder") {
                SettingsRow(
                    icon = SfIcons.LibraryMusic,
                    title = useAllAudioFoldersTitle,
                    subtitle = stringResource(R.string.use_all_audio_folders_subtitle),
                    onClick = { AppSettings.setLocalMusicFolderUri("") },
                )
            }
        }
        val filterNonMusicAudioTitle = stringResource(R.string.filter_non_music_audio)
        row(filterNonMusicAudioTitle, "podcast", "recording") {
            SettingsRow(
                icon = SfIcons.FilterAlt,
                title = filterNonMusicAudioTitle,
                subtitle = stringResource(R.string.filter_non_music_audio_subtitle),
                trailing = {
                    IosSwitch(
                        checked = filterNonMusicAudio,
                        onCheckedChange = AppSettings::setFilterNonMusicAudio,
                    )
                },
                onClick = { AppSettings.setFilterNonMusicAudio(!filterNonMusicAudio) },
            )
        }
    }
}
