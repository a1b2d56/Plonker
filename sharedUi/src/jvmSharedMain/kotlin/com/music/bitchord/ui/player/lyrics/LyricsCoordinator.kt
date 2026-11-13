package com.music.bitchord.ui.player.lyrics

import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.LocaleTags
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.LyricsSource
import com.music.bitchord.data.lyrics.translationLanguageName
import com.music.bitchord.sharedui.resources.*
import com.music.bitchord.ui.haptics.Haptic
import com.music.bitchord.ui.haptics.Haptics
import com.music.bitchord.ui.player.*
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/*
 * The lyric panel's versions of the words: the originals, a translation into
 * the listener's language, or a romanization into Latin script. One at a time,
 * asked for by the panel's two toggles and fetched through the platform host.
 *
 * The lookup of the lyrics themselves (LRCLIB, Musixmatch and the rest, and
 * lyrics embedded in local files) is the data layer's, in
 * `com.music.bitchord.data.lyrics`; this is what the player does with them.
 */

internal sealed interface LyricsTranslationUiState {
    data object Idle : LyricsTranslationUiState
    data object Loading : LyricsTranslationUiState
    data class Ready(val lines: List<LyricLine>) : LyricsTranslationUiState
    data object SameLanguage : LyricsTranslationUiState
}

internal enum class LyricsDisplayMode { Original, Romanized, Translated }

/**
 * Everything the player shows about a lyric translation or romanization, and
 * the two toggles that ask for one — see [rememberLyricsTranslation].
 */
internal class LyricsTranslationUi(
    /** The lines the one-line strip reads: the originals, or their translation. */
    val displayedLyrics: List<LyricLine>,
    /** Drawn small under each original line in the panel; null for none. */
    val subLines: List<LyricLine>?,
    val translationState: LyricsTranslationUiState,
    val romanizationState: LyricsTranslationUiState,
    val showingTranslation: Boolean,
    val showingRomanization: Boolean,
    /** Bumped on every switch between versions — the particle motion's trigger. */
    val transition: Int,
    val reduceMotion: Boolean,
    /** The line naming the lyrics' source, or what the translation is doing. */
    val status: String,
    val toggleTranslation: () -> Unit,
    val toggleRomanization: () -> Unit,
)

/**
 * The translation and romanization state machine behind the lyric panel:
 * which language, what has been asked for, what came back, and which version
 * is on screen. Reset per track, per target language and per lyric sheet.
 */
@Composable
internal fun rememberLyricsTranslation(
    trackId: String,
    lyrics: List<LyricLine>?,
    lyricsSource: LyricsSource?,
    lyricsUnavailable: Boolean,
    /** What the status line says while the lyrics are still being looked up. */
    loadingText: String,
    haptics: Haptics,
): LyricsTranslationUi {
    val reduceTranslationMotion by PlayerSettings.reduceAnimation.collectAsStateWithLifecycle()
    val configuredLocale = appLanguageTag()
    val preferredTranslation by PlayerSettings.translationLanguage.collectAsStateWithLifecycle()
    // Settings wins where it has been set; blank means follow the app. Only the
    // app-language path is reduced to a base language — a code chosen in
    // Settings is already exactly what the endpoint wants and narrowing it
    // would throw away the script half of zh-TW. Chinese is the exception on the
    // app path too: bare "zh" comes back Simplified, so the script maps to zh-TW / zh-CN.
    val translationLanguage = remember(configuredLocale, preferredTranslation) {
        preferredTranslation.ifBlank {
            if (LocaleTags.isChineseTag(configuredLocale)) {
                LocaleTags.translationWireTarget(configuredLocale)
            } else {
                Locale.forLanguageTag(configuredLocale).language.ifBlank { "en" }
            }
        }
    }
    val translationLanguageName = remember(configuredLocale, translationLanguage) {
        translationLanguageName(translationLanguage, Locale.forLanguageTag(configuredLocale))
    }
    // Resolved here, in composition, for the callbacks below to show.
    val alreadyInLanguageMessage = stringResource(Res.string.lyrics_already_in_language, translationLanguageName)
    val translationUnavailableMessage = stringResource(Res.string.lyrics_translation_unavailable)
    val alreadyRomanizedMessage = stringResource(Res.string.lyrics_already_romanized)
    val romanizationUnavailableMessage = stringResource(Res.string.lyrics_romanization_unavailable)
    var translationState by remember(trackId, translationLanguage, lyrics) {
        mutableStateOf<LyricsTranslationUiState>(LyricsTranslationUiState.Idle)
    }
    var romanizationState by remember(trackId, translationLanguage, lyrics) {
        mutableStateOf<LyricsTranslationUiState>(LyricsTranslationUiState.Idle)
    }
    var lyricsDisplayMode by remember(trackId, translationLanguage, lyrics) {
        mutableStateOf(LyricsDisplayMode.Original)
    }
    val showingTranslation = lyricsDisplayMode == LyricsDisplayMode.Translated
    val showingRomanization = lyricsDisplayMode == LyricsDisplayMode.Romanized
    var translationTransition by remember(trackId) { mutableIntStateOf(0) }
    var translationJob by remember(trackId, translationLanguage, lyrics) {
        mutableStateOf<Job?>(null)
    }
    var romanizationJob by remember(trackId, translationLanguage, lyrics) {
        mutableStateOf<Job?>(null)
    }
    DisposableEffect(trackId, translationLanguage, lyrics) {
        onDispose {
            translationJob?.cancel()
            romanizationJob?.cancel()
        }
    }
    val displayedLyrics = displayedLyricsFor(lyricsDisplayMode, lyrics, translationState, romanizationState)
    // What the panel draws in small type under each original line, Apple
    // Music style. The panel itself always keeps the original words; only the
    // one-line strip over the scrubber swaps to [displayedLyrics]. One mode at
    // a time by construction — [lyricsDisplayMode] holds a single value.
    val lyricsSubLines = subLinesFor(lyricsDisplayMode, translationState, romanizationState)
    val translationScope = rememberCoroutineScope()
    val toggleTranslation: () -> Unit = toggleTranslation@{
        when (val state = translationState) {
            is LyricsTranslationUiState.Ready -> {
                lyricsDisplayMode = if (showingTranslation) {
                    LyricsDisplayMode.Original
                } else {
                    LyricsDisplayMode.Translated
                }
                translationTransition++
                haptics.play(Haptic.Select)
            }
            LyricsTranslationUiState.Loading -> Unit
            LyricsTranslationUiState.SameLanguage -> {
                haptics.play(Haptic.Tap)
                PlayerPlatform.host.showMessage(alreadyInLanguageMessage)
            }
            LyricsTranslationUiState.Idle -> {
                val source = lyrics.orEmpty()
                if (source.isEmpty()) return@toggleTranslation
                haptics.play(Haptic.Tap)
                translationState = LyricsTranslationUiState.Loading
                romanizationJob?.cancel()
                if (romanizationState is LyricsTranslationUiState.Loading) {
                    romanizationState = LyricsTranslationUiState.Idle
                }
                translationJob?.cancel()
                translationJob = translationScope.launch {
                    when (
                        val result = PlayerPlatform.host.translateLyrics(
                            trackId = trackId,
                            lines = source,
                            targetLanguageTag = translationLanguage,
                        )
                    ) {
                        is LyricsTranslationResult.Translated -> {
                            translationState = LyricsTranslationUiState.Ready(result.lines)
                            lyricsDisplayMode = LyricsDisplayMode.Translated
                            translationTransition++
                            haptics.play(Haptic.ToggleOn)
                        }
                        is LyricsTranslationResult.SameLanguage -> {
                            translationState = LyricsTranslationUiState.SameLanguage
                            PlayerPlatform.host.showMessage(alreadyInLanguageMessage)
                        }
                        LyricsTranslationResult.Unavailable -> {
                            translationState = LyricsTranslationUiState.Idle
                            PlayerPlatform.host.showMessage(translationUnavailableMessage)
                        }
                    }
                }
            }
        }
    }
    // Asks for the romanization and shows it when it comes back. [announce] is
    // the difference between a finger on the button and the setting asking for
    // every song: the setting gets no buzz and no toast, because a sheet that
    // is already in Latin letters, or one the service could not do, is the
    // ordinary case for it and not news. Either way the original stays up.
    val startRomanization: (Boolean) -> Unit = startRomanization@{ announce ->
        val source = lyrics.orEmpty()
        if (source.isEmpty()) return@startRomanization
        if (announce) haptics.play(Haptic.Tap)
        romanizationState = LyricsTranslationUiState.Loading
        translationJob?.cancel()
        if (translationState is LyricsTranslationUiState.Loading) {
            translationState = LyricsTranslationUiState.Idle
        }
        romanizationJob?.cancel()
        romanizationJob = translationScope.launch {
            when (
                val result = PlayerPlatform.host.romanizeLyrics(
                    trackId = trackId,
                    lines = source,
                    targetLanguageTag = translationLanguage,
                )
            ) {
                is LyricsRomanizationResult.Romanized -> {
                    romanizationState = LyricsTranslationUiState.Ready(result.lines)
                    lyricsDisplayMode = LyricsDisplayMode.Romanized
                    translationTransition++
                    if (announce) haptics.play(Haptic.ToggleOn)
                }
                LyricsRomanizationResult.AlreadyRomanized -> {
                    romanizationState = LyricsTranslationUiState.SameLanguage
                    if (announce) PlayerPlatform.host.showMessage(alreadyRomanizedMessage)
                }
                LyricsRomanizationResult.Unavailable -> {
                    romanizationState = LyricsTranslationUiState.Idle
                    if (announce) PlayerPlatform.host.showMessage(romanizationUnavailableMessage)
                }
            }
        }
    }
    // "Romanized lyrics" in Settings: once for each lyric sheet, as it arrives.
    // Keyed on the sheet, so it is not asked again for the same one — a
    // romanization the listener turned off on this song stays off, and one that
    // failed is not retried until the lyrics themselves change.
    val autoRomanize by PlayerSettings.romanizedLyrics.collectAsStateWithLifecycle()
    LaunchedEffect(trackId, translationLanguage, lyrics, autoRomanize) {
        if (shouldAutoRomanize(
                enabled = autoRomanize,
                hasLyrics = !lyrics.isNullOrEmpty(),
                mode = lyricsDisplayMode,
                translation = translationState,
                romanization = romanizationState,
            )
        ) {
            startRomanization(false)
        }
    }
    val toggleRomanization: () -> Unit = toggleRomanization@{
        when (val state = romanizationState) {
            is LyricsTranslationUiState.Ready -> {
                lyricsDisplayMode = if (showingRomanization) {
                    LyricsDisplayMode.Original
                } else {
                    LyricsDisplayMode.Romanized
                }
                translationTransition++
                haptics.play(Haptic.Select)
            }
            LyricsTranslationUiState.Loading -> Unit
            LyricsTranslationUiState.SameLanguage -> {
                haptics.play(Haptic.Tap)
                PlayerPlatform.host.showMessage(alreadyRomanizedMessage)
            }
            LyricsTranslationUiState.Idle -> startRomanization(true)
        }
    }
    // The one line that sits above the scrubber while the lyrics are open: which
    // of the providers the timings came from, or what the translation is doing.
    // Worked out once for both layouts — the portrait player puts it over its
    // half-player, the landscape one under its lyric column — so the two can
    // never disagree about what it says.
    val status = when {
        translationState is LyricsTranslationUiState.Loading ->
            stringResource(Res.string.translating_lyrics_to, translationLanguageName)
        romanizationState is LyricsTranslationUiState.Loading ->
            stringResource(Res.string.romanizing_lyrics)
        showingTranslation ->
            stringResource(Res.string.lyrics_translated_to, translationLanguageName)
        showingRomanization ->
            stringResource(Res.string.lyrics_romanized)
        translationState is LyricsTranslationUiState.SameLanguage ->
            stringResource(Res.string.lyrics_already_in_language, translationLanguageName)
        romanizationState is LyricsTranslationUiState.SameLanguage ->
            stringResource(Res.string.lyrics_already_romanized)
        lyricsSource != null -> stringResource(Res.string.lyrics_by, lyricsSource.label)
        lyricsUnavailable -> stringResource(Res.string.no_lyrics_found)
        lyrics.isNullOrEmpty() -> loadingText
        else -> stringResource(Res.string.lyrics_saved_with_download)
    }

    return LyricsTranslationUi(
        displayedLyrics = displayedLyrics,
        subLines = lyricsSubLines,
        translationState = translationState,
        romanizationState = romanizationState,
        showingTranslation = showingTranslation,
        showingRomanization = showingRomanization,
        transition = translationTransition,
        reduceMotion = reduceTranslationMotion,
        status = status,
        toggleTranslation = toggleTranslation,
        toggleRomanization = toggleRomanization,
    )
}

/** The lines the one-line strip reads in [mode]: a version that has come back, else the originals. */
internal fun displayedLyricsFor(
    mode: LyricsDisplayMode,
    lyrics: List<LyricLine>?,
    translation: LyricsTranslationUiState,
    romanization: LyricsTranslationUiState,
): List<LyricLine> = when (mode) {
    LyricsDisplayMode.Translated -> (translation as? LyricsTranslationUiState.Ready)?.lines ?: lyrics.orEmpty()
    LyricsDisplayMode.Romanized -> (romanization as? LyricsTranslationUiState.Ready)?.lines ?: lyrics.orEmpty()
    LyricsDisplayMode.Original -> lyrics.orEmpty()
}

/** What the panel draws small under each original line in [mode]; null for nothing. */
internal fun subLinesFor(
    mode: LyricsDisplayMode,
    translation: LyricsTranslationUiState,
    romanization: LyricsTranslationUiState,
): List<LyricLine>? = when (mode) {
    LyricsDisplayMode.Translated -> (translation as? LyricsTranslationUiState.Ready)?.lines
    LyricsDisplayMode.Romanized -> (romanization as? LyricsTranslationUiState.Ready)?.lines
    LyricsDisplayMode.Original -> null
}

/**
 * Whether the "Romanized lyrics" setting should ask for a sheet's romanization
 * on its own.
 *
 * Only from a clean slate. What the listener has done to this sheet comes
 * first: a translation showing or on its way is their choice of what goes under
 * the lines, and a romanization that is already loading, already here, or
 * already known to be pointless is not asked for twice. With nothing to
 * romanize there is nothing to ask. Whatever the answer turns out to be, the
 * original lyrics are what is on screen until a romanization replaces them.
 */
internal fun shouldAutoRomanize(
    enabled: Boolean,
    hasLyrics: Boolean,
    mode: LyricsDisplayMode,
    translation: LyricsTranslationUiState,
    romanization: LyricsTranslationUiState,
): Boolean = enabled && hasLyrics &&
    mode == LyricsDisplayMode.Original &&
    translation is LyricsTranslationUiState.Idle &&
    romanization is LyricsTranslationUiState.Idle
