package com.music.bitchord.ui.screens.settings

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.lyrics.translationLanguageName
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.ui.components.IosMotion
import com.music.bitchord.ui.components.IosSwitch
import com.music.bitchord.ui.icons.SfIcons
import java.util.Locale

/**
 * Everything about lyrics, on a page of its own: whether they are looked up,
 * how they are drawn, where they come from, and what goes under each line.
 * These rows used to be split between Appearance and Playback.
 */
@Composable
internal fun LyricsSettingsGroup(
    search: SettingsSearch,
    onLyricsSources: () -> Unit,
    onTranslationLanguage: () -> Unit,
) {
    val syncedLyrics by AppSettings.syncedLyrics.collectAsStateWithLifecycle()
    val lyricsBlur by AppSettings.lyricsBlur.collectAsStateWithLifecycle()
    val lyricsSources by AppSettings.lyricsSources.collectAsStateWithLifecycle()
    val translationLanguage by AppSettings.translationLanguage.collectAsStateWithLifecycle()
    val romanizedLyrics by AppSettings.romanizedLyrics.collectAsStateWithLifecycle()

    SearchableSettingsGroup(search, header = stringResource(R.string.lyrics)) {
        val syncedLyricsTitle = stringResource(R.string.synced_lyrics)
        // The rows under this one are only here while it is on.
        row(syncedLyricsTitle, "lyrics", "translation", "lrclib", "musixmatch") {
            SettingsRow(
                icon = SfIcons.Notes,
                title = syncedLyricsTitle,
                subtitle = stringResource(R.string.synced_lyrics_subtitle),
                trailing = {
                    IosSwitch(
                        checked = syncedLyrics,
                        onCheckedChange = AppSettings::setSyncedLyrics,
                    )
                },
                onClick = { AppSettings.setSyncedLyrics(!syncedLyrics) },
            )
        }
        // Nothing to choose between while the feature is off, and the
        // sources are third-party services being reached on the user's
        // connection — which is the part worth being able to narrow.
        val lyricsBlurTitle = stringResource(R.string.blur_unfocused_lyrics)
        val lyricsSourcesTitle = stringResource(R.string.lyrics_sources)
        val translationLanguageTitle = stringResource(R.string.translation_language)
        val romanizedLyricsTitle = stringResource(R.string.romanized_lyrics)
        row(
            lyricsBlurTitle, "lyrics", "blur", "sources", "translation",
            "romanize", "romanise", "romaji", "pinyin", "latin", "pronunciation",
            divided = false,
        ) {
            AnimatedVisibility(
                visible = syncedLyrics,
                enter = expandVertically(IosMotion.cardSpring()) + fadeIn(),
                exit = shrinkVertically(IosMotion.cardSpring()) + fadeOut(),
            ) {
                Column {
                    RowDivider()
                    SettingsRow(
                        icon = SfIcons.BlurOn,
                        title = lyricsBlurTitle,
                        subtitle = stringResource(R.string.blur_unfocused_lyrics_subtitle),
                        trailing = {
                            IosSwitch(
                                checked = lyricsBlur,
                                onCheckedChange = AppSettings::setLyricsBlur,
                            )
                        },
                        onClick = { AppSettings.setLyricsBlur(!lyricsBlur) },
                    )
                    RowDivider()
                    SettingsRow(
                        icon = SfIcons.Language,
                        title = lyricsSourcesTitle,
                        subtitle = lyricsSources
                            .sortedBy { it.ordinal }
                            .joinToString(", ") { it.label }
                            .ifEmpty { stringResource(R.string.no_lyrics_sources_enabled) },
                        trailing = { Chevron() },
                        onClick = onLyricsSources,
                    )
                    RowDivider()
                    SettingsRow(
                        icon = SfIcons.Translate,
                        title = translationLanguageTitle,
                        subtitle = if (translationLanguage.isBlank()) {
                            stringResource(R.string.translation_language_subtitle)
                        } else {
                            translationLanguageName(
                                translationLanguage,
                                AppCompatDelegate.getApplicationLocales().get(0) ?: Locale.getDefault(),
                            )
                        },
                        trailing = { Chevron() },
                        onClick = onTranslationLanguage,
                    )
                    RowDivider()
                    // The player's Romanize button is per song and forgets;
                    // this is the same thing left on.
                    SettingsRow(
                        icon = SfIcons.Font,
                        title = romanizedLyricsTitle,
                        subtitle = stringResource(R.string.romanized_lyrics_subtitle),
                        trailing = {
                            IosSwitch(
                                checked = romanizedLyrics,
                                onCheckedChange = AppSettings::setRomanizedLyrics,
                            )
                        },
                        onClick = { AppSettings.setRomanizedLyrics(!romanizedLyrics) },
                    )
                }
            }
        }
    }
}
