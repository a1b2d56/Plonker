package com.music.bitchord.ui.screens.settings

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Brightness4
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.AppIcon
import com.music.bitchord.R
import com.music.bitchord.data.lyrics.translationLanguageName
import com.music.bitchord.data.settings.AppFont
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
    val appFont by AppSettings.appFont.collectAsStateWithLifecycle()
    val hapticFeedback by AppSettings.hapticFeedbackEnabled.collectAsStateWithLifecycle()
    val reduceAnimation by AppSettings.reduceAnimation.collectAsStateWithLifecycle()
    val reduceDynamicBlur by AppSettings.reduceDynamicBlurSetting.collectAsStateWithLifecycle()
    val blurReducedBySystem by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    val disableBlendEffects by AppSettings.disableBlendEffects.collectAsStateWithLifecycle()
    val liquidGlass by AppSettings.liquidGlass.collectAsStateWithLifecycle()
    val glassPerformance by AppSettings.glassPerformance.collectAsStateWithLifecycle()
    val progressiveBlur by AppSettings.progressiveBlur.collectAsStateWithLifecycle()
    val liquidGlassSupported = isGlassSupported()
    val animatedCanvas by AppSettings.animatedCanvas.collectAsStateWithLifecycle()
    val canvasOverCellular by AppSettings.canvasOverCellular.collectAsStateWithLifecycle()
    val canvasExplicit by AppSettings.canvasExplicit.collectAsStateWithLifecycle()
    val canvasTokenPresent by AppSettings.canvasTokenPresent.collectAsStateWithLifecycle()
    val canvasTokenExpired by AppSettings.canvasTokenExpired.collectAsStateWithLifecycle()
    val playerTheme by AppSettings.playerTheme.collectAsStateWithLifecycle()
    val fullBleedArtwork by AppSettings.fullBleedArtwork.collectAsStateWithLifecycle()
    val fullBleedAvailable = fullBleedArtworkAvailable(windowWidth)
    val showArtistSignatures by AppSettings.showArtistSignatures.collectAsStateWithLifecycle()
    val signatureApiEnabled by AppSettings.signatureApiEnabled.collectAsStateWithLifecycle()
    val signatureApiUrl by AppSettings.signatureApiUrl.collectAsStateWithLifecycle()
    val syncedLyrics by AppSettings.syncedLyrics.collectAsStateWithLifecycle()
    val lyricsBlur by AppSettings.lyricsBlur.collectAsStateWithLifecycle()
    val lyricsSources by AppSettings.lyricsSources.collectAsStateWithLifecycle()
    val translationLanguage by AppSettings.translationLanguage.collectAsStateWithLifecycle()
    val appIcon by AppSettings.appIcon.collectAsStateWithLifecycle()

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
        val fontTitle = stringResource(R.string.font)
        row(fontTitle, "typeface", "sf pro", "legacy") {
            SettingsRow(
                icon = SfIcons.Font,
                title = fontTitle,
                subtitle = appFont.description(),
                value = appFont.localizedLabel(),
                onClick = { onOpenChoice(SettingsChoiceKind.FONT) },
            )
        }
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
                        checked = liquidGlass && liquidGlassSupported,
                        onCheckedChange = AppSettings::setLiquidGlass,
                        enabled = liquidGlassSupported,
                    )
                },
                onClick = { AppSettings.setLiquidGlass(!liquidGlass) },
            )
        }
        if (liquidGlass && liquidGlassSupported) {
            val progressiveBlurTitle = stringResource(R.string.progressive_blur)
            row(progressiveBlurTitle, "glass", "blur", "gradient blur", divided = false) {
                SettingsSubRow(
                    title = progressiveBlurTitle,
                    subtitle = stringResource(R.string.progressive_blur_subtitle),
                    checked = progressiveBlur,
                    onCheckedChange = AppSettings::setProgressiveBlur,
                )
            }
            val glassPerformanceTitle = stringResource(R.string.glass_performance)
            row(glassPerformanceTitle, "glass", "performance", "battery", "smooth", divided = false) {
                SettingsSubRow(
                    title = glassPerformanceTitle,
                    subtitle = stringResource(R.string.glass_performance_subtitle),
                    checked = glassPerformance,
                    onCheckedChange = AppSettings::setGlassPerformance,
                )
            }
        }
        val animatedCanvasTitle = stringResource(R.string.animated_cover_art)
        row(animatedCanvasTitle, "canvas", "spotify", "video cover", "loop") {
            SettingsRow(
                icon = SfIcons.Draw,
                title = animatedCanvasTitle,
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
        if (animatedCanvas) {
            val canvasOverCellularTitle = stringResource(R.string.canvas_over_cellular)
            row(canvasOverCellularTitle, "canvas", "cellular", "mobile data", divided = false) {
                SettingsSubRow(
                    title = canvasOverCellularTitle,
                    checked = canvasOverCellular,
                    onCheckedChange = AppSettings::setCanvasOverCellular,
                )
            }
            val canvasExplicitTitle = stringResource(R.string.canvas_explicit)
            row(canvasExplicitTitle, "canvas", "explicit", "canvas explicit", divided = false) {
                SettingsSubRow(
                    title = canvasExplicitTitle,
                    subtitle = stringResource(R.string.canvas_explicit_subtitle),
                    checked = canvasExplicit,
                    onCheckedChange = AppSettings::setCanvasExplicit,
                )
            }
            val spotifyCanvasAuthTitle = stringResource(R.string.spotify_canvas_auth)
            row(spotifyCanvasAuthTitle, "spotify", "canvas", "token", "sp_dc", divided = false) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onSpotifyCanvasAuth)
                        .padding(start = ROW_INSET, end = ROW_INSET, top = 4.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = spotifyCanvasAuthTitle,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                        Text(
                            text = stringResource(
                                when {
                                    canvasTokenExpired -> R.string.spotify_canvas_token_expired
                                    canvasTokenPresent -> R.string.spotify_canvas_token_configured
                                    else -> R.string.spotify_canvas_token_unconfigured
                                },
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Chevron()
                }
            }
        }
        val playerThemeTitle = stringResource(R.string.player_theme)
        row(playerThemeTitle, "player", "theme", "layout", "default", "legacy", "simple") {
            SettingsRow(
                icon = SfIcons.Appearance,
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
        val fullBleedArtworkTitle = stringResource(R.string.full_bleed_artwork)
        row(fullBleedArtworkTitle, "cover", "album art", "edge to edge") {
            SettingsRow(
                icon = SfIcons.Fullscreen,
                title = fullBleedArtworkTitle,
                subtitle = stringResource(
                    if (fullBleedAvailable) {
                        R.string.full_bleed_artwork_subtitle
                    } else {
                        R.string.full_bleed_artwork_unavailable
                    },
                ),
                enabled = fullBleedAvailable,
                trailing = {
                    IosSwitch(
                        checked = fullBleedArtwork && fullBleedAvailable,
                        onCheckedChange = AppSettings::setFullBleedArtwork,
                        enabled = fullBleedAvailable,
                    )
                },
                onClick = { AppSettings.setFullBleedArtwork(!fullBleedArtwork) },
            )
        }
        val showArtistSignaturesTitle = stringResource(R.string.show_artist_signatures)
        row(showArtistSignaturesTitle, "signature", "artist", "badge", "mark") {
            SettingsRow(
                icon = SfIcons.LocalOffer,
                title = showArtistSignaturesTitle,
                subtitle = stringResource(R.string.show_artist_signatures_subtitle),
                trailing = {
                    IosSwitch(
                        checked = showArtistSignatures,
                        onCheckedChange = AppSettings::setShowArtistSignatures,
                    )
                },
                onClick = { AppSettings.setShowArtistSignatures(!showArtistSignatures) },
            )
        }
        val signatureApiEnabledTitle = stringResource(R.string.artist_signatures_api)
        row(signatureApiEnabledTitle, "signature", "api", "url") {
            SettingsRow(
                icon = SfIcons.Extension,
                title = signatureApiEnabledTitle,
                subtitle = stringResource(R.string.artist_signatures_api_subtitle),
                trailing = {
                    IosSwitch(
                        checked = signatureApiEnabled,
                        onCheckedChange = AppSettings::setSignatureApiEnabled,
                    )
                },
                onClick = { AppSettings.setSignatureApiEnabled(!signatureApiEnabled) },
            )
        }
        if (signatureApiEnabled) {
            val signatureUrlTitle = stringResource(R.string.artist_signatures_url)
            row(signatureUrlTitle, "signature", "api", "url", divided = false) {
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
        val syncedLyricsTitle = stringResource(R.string.synced_lyrics)
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
        if (syncedLyrics) {
            val lyricsBlurTitle = stringResource(R.string.blur_unfocused_lyrics)
            row(lyricsBlurTitle, "lyrics", "blur") {
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
            }
            val lyricsSourcesTitle = stringResource(R.string.lyrics_sources)
            row(lyricsSourcesTitle, "lyrics", "lrclib", "musixmatch") {
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
            }
            val translationLanguageTitle = stringResource(R.string.translation_language)
            row(translationLanguageTitle, "lyrics", "translate") {
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
        val appIconTitle = stringResource(R.string.app_icon)
        row(appIconTitle, "icon", "logo", "launcher") {
            SettingsRow(
                icon = SfIcons.Apps,
                title = appIconTitle,
                subtitle = stringResource(R.string.app_icon_subtitle),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(start = ROW_INSET, end = ROW_INSET, bottom = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AppIcon.entries.forEach { icon ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { AppSettings.setAppIcon(icon) }
                            .padding(4.dp),
                    ) {
                        Image(
                            painter = painterResource(icon.iconRes),
                            contentDescription = stringResource(icon.labelRes),
                            modifier = Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(12.dp)),
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(icon.labelRes),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (icon == appIcon) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }
        }
    }
}
