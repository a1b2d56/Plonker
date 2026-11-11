package com.music.bitchord.ui.screens.settings

import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Brightness4
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import kotlin.math.roundToInt
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.lyrics.translationLanguageName
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.PlayerTheme
import com.music.bitchord.data.settings.ThemeMode
import com.music.bitchord.ui.components.IosSwitch
import com.music.bitchord.ui.components.isGlassSupported
import com.music.bitchord.ui.icons.SfIcons
import com.music.bitchord.ui.player.fullBleedArtworkAvailable
import java.util.Locale

@Composable
internal fun AppearanceSettingsGroup(
    search: SettingsSearch,
    windowWidth: Dp,
    onOpenChoice: (SettingsChoiceKind) -> Unit,
    onSpotifyCanvasAuth: () -> Unit,
    onEditSignatureApi: () -> Unit,
    onLyricsSources: () -> Unit,
    onTranslationLanguage: () -> Unit,
) {
    val theme by AppSettings.themeMode.collectAsStateWithLifecycle()
    val hapticFeedback by AppSettings.hapticFeedbackEnabled.collectAsStateWithLifecycle()
    val reduceAnimation by AppSettings.reduceAnimation.collectAsStateWithLifecycle()
    val reduceDynamicBlur by AppSettings.reduceDynamicBlurSetting.collectAsStateWithLifecycle()
    val blurReducedBySystem by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    val disableBlendEffects by AppSettings.disableBlendEffects.collectAsStateWithLifecycle()
    val liquidGlass by AppSettings.liquidGlass.collectAsStateWithLifecycle()
    val glassPerformance by AppSettings.glassPerformance.collectAsStateWithLifecycle()
    val progressiveBlur by AppSettings.progressiveBlur.collectAsStateWithLifecycle()
    val liquidGlassHaptics by AppSettings.liquidGlassHaptics.collectAsStateWithLifecycle()
    val liquidGlassIntensity by AppSettings.liquidGlassIntensity.collectAsStateWithLifecycle()
    val liquidGlassSupported = isGlassSupported()
    val fullBleedArtwork by AppSettings.fullBleedArtwork.collectAsStateWithLifecycle()
    val fullBleedDetailArtwork by AppSettings.fullBleedDetailArtwork.collectAsStateWithLifecycle()
    val playerTheme by AppSettings.playerTheme.collectAsStateWithLifecycle()
    val animatedCanvas by AppSettings.animatedCanvas.collectAsStateWithLifecycle()
    val canvasOverCellular by AppSettings.canvasOverCellular.collectAsStateWithLifecycle()
    val signatureApiEnabled by AppSettings.signatureApiEnabled.collectAsStateWithLifecycle()
    val signatureApiUrl by AppSettings.signatureApiUrl.collectAsStateWithLifecycle()
    val syncedLyrics by AppSettings.syncedLyrics.collectAsStateWithLifecycle()
    val lyricsBlur by AppSettings.lyricsBlur.collectAsStateWithLifecycle()
    val lyricsSources by AppSettings.lyricsSources.collectAsStateWithLifecycle()
    val translationLanguage by AppSettings.translationLanguage.collectAsStateWithLifecycle()

    SearchableSettingsGroup(search, header = stringResource(R.string.appearance)) {
            val themeTitle = stringResource(R.string.theme)
            row(themeTitle, "dark mode", "light mode") {
                SettingsRow(icon = Icons.Rounded.Brightness4, title = themeTitle)
                SegmentedControl(
                    options = ThemeMode.entries.map { it.localizedLabel() },
                    selectedIndex = ThemeMode.entries.indexOf(theme),
                    onSelect = { AppSettings.setThemeMode(ThemeMode.entries[it]) },
                    modifier = Modifier.padding(start = ROW_INSET, end = ROW_INSET, bottom = 14.dp),
                )
            }
                        val hapticFeedback by AppSettings.hapticFeedbackEnabled.collectAsStateWithLifecycle()
            val hapticFeedbackTitle = stringResource(R.string.haptic_feedback)
            row(hapticFeedbackTitle, "vibration", "touch", "haptics") {
                SettingsRow(
                    icon = SfIcons.SmartButton,
                    title = hapticFeedbackTitle,
                    subtitle = stringResource(R.string.haptic_feedback_subtitle),
                    trailing = {
                        IosSwitch(
                            checked = hapticFeedback,
                            onCheckedChange = AppSettings::setHapticFeedbackEnabled,
                        )
                    },
                    onClick = { AppSettings.setHapticFeedbackEnabled(!hapticFeedback) },
                )
            }
            val reduceAnimationTitle = stringResource(R.string.reduce_animation)
            row(reduceAnimationTitle, "motion") {
                SettingsRow(
                    icon = SfIcons.MotionPhotosOff,
                    title = reduceAnimationTitle,
                    subtitle = stringResource(R.string.reduce_animation_subtitle),
                    trailing = {
                        IosSwitch(
                            checked = reduceAnimation,
                            onCheckedChange = AppSettings::setReduceAnimation,
                        )
                    },
                    onClick = { AppSettings.setReduceAnimation(!reduceAnimation) },
                )
            }
            val reduceDynamicBlurTitle = stringResource(R.string.reduce_dynamic_blur)
            row(reduceDynamicBlurTitle, "blur", "performance") {
                SettingsRow(
                    icon = SfIcons.BlurOff,
                    title = reduceDynamicBlurTitle,
                    subtitle = stringResource(
                        if (blurReducedBySystem && !reduceDynamicBlur) {
                            R.string.reduce_dynamic_blur_battery_saver
                        } else {
                            R.string.reduce_dynamic_blur_subtitle
                        },
                    ),
                    trailing = {
                        IosSwitch(
                            checked = reduceDynamicBlur,
                            onCheckedChange = AppSettings::setReduceDynamicBlur,
                        )
                    },
                    onClick = { AppSettings.setReduceDynamicBlur(!reduceDynamicBlur) },
                )
            }
            val disableBlendEffectsTitle = stringResource(R.string.disable_blend_effects)
            row(disableBlendEffectsTitle, "blend", "blending", "colour", "color", "tint", "player", "menu") {
                SettingsRow(
                    icon = SfIcons.Gradient,
                    title = disableBlendEffectsTitle,
                    subtitle = stringResource(R.string.disable_blend_effects_subtitle),
                    trailing = {
                        IosSwitch(
                            checked = disableBlendEffects,
                            onCheckedChange = AppSettings::setDisableBlendEffects,
                        )
                    },
                    onClick = { AppSettings.setDisableBlendEffects(!disableBlendEffects) },
                )
            }
            val liquidGlassTitle = stringResource(R.string.liquid_glass)
            row(liquidGlassTitle, "glass", "blur") {
                SettingsRow(
                    icon = SfIcons.AutoAwesome,
                    title = liquidGlassTitle,
                    subtitle = stringResource(
                        if (liquidGlassSupported) {
                            R.string.liquid_glass_subtitle
                        } else {
                            R.string.liquid_glass_unavailable
                        },
                    ),
                    enabled = liquidGlassSupported,
                    trailing = {
                        IosSwitch(
                            checked = liquidGlass,
                            onCheckedChange = AppSettings::setLiquidGlass,
                            enabled = liquidGlassSupported,
                        )
                    },
                    onClick = { AppSettings.setLiquidGlass(!liquidGlass) },
                )
            }
            // One line each, like Direct Source Stream under Sources, and only
            // while Liquid Glass is on: they qualify the glass rather than standing
            // on their own. Progressive Blur has its own switch because it costs
            // frames of its own. Reduce dynamic blur turns it off.
            val liquidGlassIntensityTitle = stringResource(R.string.liquid_glass_intensity)
            val glassPerformanceTitle = stringResource(R.string.glass_performance)
            val progressiveBlurTitle = stringResource(R.string.progressive_blur)
            val liquidGlassHapticsTitle = stringResource(R.string.liquid_glass_haptics)
            row(liquidGlassIntensityTitle, "glass", "intensity", "performance", "progressive", "blur", "haptics", divided = false) {
                AnimatedVisibility(
                    visible = liquidGlass && liquidGlassSupported,
                    enter = expandVertically(IosMotion.cardSpring()) + fadeIn(),
                    exit = shrinkVertically(IosMotion.cardSpring()) + fadeOut(),
                ) {
                    Column {
                        RowDivider()
                        SliderRow(
                            icon = SfIcons.Tune,
                            title = liquidGlassIntensityTitle,
                            subtitle = stringResource(R.string.liquid_glass_intensity_subtitle),
                            value = "${(liquidGlassIntensity * 100).roundToInt()}%",
                            sliderValue = liquidGlassIntensity,
                            onSliderValue = { AppSettings.setLiquidGlassIntensity(it) },
                            valueRange = AppSettings.MIN_LIQUID_GLASS_INTENSITY..AppSettings.MAX_LIQUID_GLASS_INTENSITY,
                            steps = 8,
                        )
                        SettingsSubRow(
                            title = glassPerformanceTitle,
                            checked = glassPerformance,
                            onCheckedChange = AppSettings::setGlassPerformance,
                        )
                        SettingsSubRow(
                            title = progressiveBlurTitle,
                            checked = progressiveBlur,
                            onCheckedChange = AppSettings::setProgressiveBlur,
                        )
                        SettingsSubRow(
                            title = liquidGlassHapticsTitle,
                            checked = liquidGlassHaptics,
                            onCheckedChange = AppSettings::setLiquidGlassHaptics,
                        )
                    }
                }
            }
            // Left out where the player won't honour it: a window too wide for
            // the player to fill and too narrow to stand a page beside it keeps
            // the sleeve either way. A docked pane is a phone's width, so it does
            // honour it — see [fullBleedArtworkAvailable].
            if (fullBleedArtworkAvailable(windowWidth)) {
                val fullScreenCoverArtTitle = stringResource(R.string.full_screen_cover_art)
                row(fullScreenCoverArtTitle, "artwork", "player") {
                    SettingsRow(
                        icon = SfIcons.Fullscreen,
                        title = fullScreenCoverArtTitle,
                        subtitle = stringResource(R.string.full_screen_cover_art_subtitle),
                        trailing = {
                            IosSwitch(
                                checked = fullBleedArtwork,
                                onCheckedChange = AppSettings::setFullBleedArtwork,
                            )
                        },
                        onClick = { AppSettings.setFullBleedArtwork(!fullBleedArtwork) },
                    )
                }
            }
            // Listed at every width: the page itself keeps the framed sleeve
            // wherever a square the window's width wouldn't fit — see
            // [ReleaseHeader].
            val fullScreenDetailCoverTitle = stringResource(R.string.full_screen_detail_cover)
            row(fullScreenDetailCoverTitle, "artwork", "album", "playlist", "cover") {
                SettingsRow(
                    icon = SfIcons.Fullscreen,
                    title = fullScreenDetailCoverTitle,
                    subtitle = stringResource(R.string.full_screen_detail_cover_subtitle),
                    trailing = {
                        IosSwitch(
                            checked = fullBleedDetailArtwork,
                            onCheckedChange = AppSettings::setFullBleedDetailArtwork,
                        )
                    },
                    onClick = { AppSettings.setFullBleedDetailArtwork(!fullBleedDetailArtwork) },
                )
            }
            val playerThemeTitle = stringResource(R.string.player_theme)
            row(playerThemeTitle, "background", "player", "mesh", "gradient", "legacy", "simple", "blur") {
                SettingsRow(
                    icon = SfIcons.Gradient,
                    title = playerThemeTitle,
                    subtitle = playerTheme.description(),
                )
                SegmentedControl(
                    options = PlayerTheme.entries.map { it.localizedLabel() },
                    selectedIndex = PlayerTheme.entries.indexOf(playerTheme),
                    onSelect = { AppSettings.setPlayerTheme(PlayerTheme.entries[it]) },
                    modifier = Modifier.padding(start = ROW_INSET, end = ROW_INSET, bottom = 14.dp),
                )
            }
            val animatedCoverArtTitle = stringResource(R.string.animated_cover_art)
            // Carries the keywords of the rows that only appear once it is on,
            // so searching "spotify" with animated covers switched off turns up
            // the switch that brings the Spotify row back rather than nothing.
            row(animatedCoverArtTitle, "canvas", "video", "spotify") {
                SettingsRow(
                    icon = SfIcons.Animation,
                    title = animatedCoverArtTitle,
                    subtitle = stringResource(R.string.animated_cover_art_subtitle),
                    trailing = {
                        IosSwitch(
                            checked = animatedCanvas,
                            onCheckedChange = AppSettings::setAnimatedCanvas,
                        )
                    },
                    onClick = { AppSettings.setAnimatedCanvas(!animatedCanvas) },
                )
            }
            // Reads as part of the Animated cover art option above it, not
            // as a separate setting. Nothing to narrow while the clip itself
            // is off. Defaults to off: a clip loops for as long as its track
            // plays, so on cellular this is not a one-time video cost but
            // that cost repeated on every loop — see AppSettings.canvasOverCellular.
            val coverCellularTitle = stringResource(R.string.animated_cover_cellular)
            val spotifyCanvasTitle = stringResource(R.string.integrate_spotify_canvas)
            row(coverCellularTitle, "canvas", "cellular", "spotify", divided = false) {
                AnimatedVisibility(
                    visible = animatedCanvas,
                    enter = expandVertically(IosMotion.cardSpring()) + fadeIn(),
                    exit = shrinkVertically(IosMotion.cardSpring()) + fadeOut(),
                ) {
                    Column {
                        RowDivider()
                        SettingsSubRow(
                            title = coverCellularTitle,
                            checked = canvasOverCellular,
                            onCheckedChange = AppSettings::setCanvasOverCellular,
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(onClick = onSpotifyCanvasAuth)
                                .padding(start = ROW_INSET, end = ROW_INSET, top = 4.dp, bottom = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = spotifyCanvasTitle,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onBackground,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Chevron()
                        }
                    }
                }
            }
            val artistSignaturesTitle = stringResource(R.string.artist_signatures)
            row(artistSignaturesTitle, "signature", "artist", "logo") {
                SettingsRow(
                    icon = SfIcons.Draw,
                    title = artistSignaturesTitle,
                    subtitle = stringResource(R.string.artist_signatures_subtitle),
                    trailing = {
                        IosSwitch(
                            checked = signatureApiEnabled,
                            onCheckedChange = AppSettings::setSignatureApiEnabled,
                        )
                    },
                    onClick = { AppSettings.setSignatureApiEnabled(!signatureApiEnabled) },
                )
            }
            val signatureUrlTitle = stringResource(R.string.artist_signatures_url)
            row(signatureUrlTitle, "signature", "api", "url", divided = false) {
                AnimatedVisibility(
                    visible = signatureApiEnabled,
                    enter = expandVertically(IosMotion.cardSpring()) + fadeIn(),
                    exit = shrinkVertically(IosMotion.cardSpring()) + fadeOut(),
                ) {
                    Column {
                        RowDivider()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(onClick = onEditSignatureApi)
                                .padding(start = ROW_INSET, end = ROW_INSET, top = 4.dp, bottom = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = signatureApiUrl.ifBlank {
                                    stringResource(R.string.artist_signatures_unconfigured)
                                },
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onBackground,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Chevron()
                        }
                    }
                }
            }
            val syncedLyricsTitle = stringResource(R.string.synced_lyrics)
            // Same reasoning as Animated cover art above: the lyrics source and
            // translation rows are only here while this is on.
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
            row(lyricsBlurTitle, "lyrics", "blur", "sources", "translation", divided = false) {
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
                    }
                }
            }
            }
}
