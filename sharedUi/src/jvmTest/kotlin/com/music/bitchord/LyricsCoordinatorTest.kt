package com.music.bitchord

import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.ui.player.lyrics.LyricsDisplayMode
import com.music.bitchord.ui.player.lyrics.LyricsTranslationUiState
import com.music.bitchord.ui.player.lyrics.displayedLyricsFor
import com.music.bitchord.ui.player.lyrics.shouldAutoRomanize
import com.music.bitchord.ui.player.lyrics.subLinesFor
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull

/**
 * The panel keeps the original words; the strip reads the version on screen,
 * and the version only shows once it has come back.
 */
class LyricsCoordinatorTest {

    private val original = listOf(LyricLine(1_000L, "Hola"))
    private val translated = listOf(LyricLine(1_000L, "Hello"))
    private val romanized = listOf(LyricLine(1_000L, "Ola"))
    private val ready = LyricsTranslationUiState.Ready(translated)
    private val readyRoman = LyricsTranslationUiState.Ready(romanized)

    @Test
    fun originalsShowTheOriginalsAndNothingUnderThem() {
        assertEquals(original, displayedLyricsFor(LyricsDisplayMode.Original, original, ready, readyRoman))
        assertNull(subLinesFor(LyricsDisplayMode.Original, ready, readyRoman))
    }

    @Test
    fun aTranslationThatCameBackIsReadAndDrawnUnder() {
        assertEquals(translated, displayedLyricsFor(LyricsDisplayMode.Translated, original, ready, readyRoman))
        assertEquals(translated, subLinesFor(LyricsDisplayMode.Translated, ready, readyRoman))
    }

    @Test
    fun aRomanizationThatCameBackIsReadAndDrawnUnder() {
        assertEquals(romanized, displayedLyricsFor(LyricsDisplayMode.Romanized, original, ready, readyRoman))
        assertEquals(romanized, subLinesFor(LyricsDisplayMode.Romanized, ready, readyRoman))
    }

    @Test
    fun aVersionStillLoadingFallsBackToTheOriginals() {
        val loading = LyricsTranslationUiState.Loading
        assertEquals(original, displayedLyricsFor(LyricsDisplayMode.Translated, original, loading, loading))
        assertNull(subLinesFor(LyricsDisplayMode.Translated, loading, loading))
        assertEquals(emptyList<LyricLine>(), displayedLyricsFor(LyricsDisplayMode.Romanized, null, loading, loading))
    }

    // ---- "Romanized lyrics" in Settings ----

    private val idle = LyricsTranslationUiState.Idle

    private fun auto(
        enabled: Boolean = true,
        hasLyrics: Boolean = true,
        mode: LyricsDisplayMode = LyricsDisplayMode.Original,
        translation: LyricsTranslationUiState = idle,
        romanization: LyricsTranslationUiState = idle,
    ) = shouldAutoRomanize(enabled, hasLyrics, mode, translation, romanization)

    @Test
    fun aFreshSheetIsRomanizedOnlyWithTheSettingOn() {
        assertTrue(auto())
        assertFalse(auto(enabled = false))
    }

    @Test
    fun noLyricsMeansNothingToAskFor() {
        assertFalse(auto(hasLyrics = false))
    }

    @Test
    fun aTranslationTheListenerChoseIsLeftAlone() {
        assertFalse(auto(translation = LyricsTranslationUiState.Loading))
        assertFalse(auto(mode = LyricsDisplayMode.Translated, translation = ready))
        // Back on the originals with the translation still held: they looked,
        // and chose the originals.
        assertFalse(auto(translation = ready))
    }

    @Test
    fun aRomanizationIsNotAskedForTwice() {
        assertFalse(auto(romanization = LyricsTranslationUiState.Loading))
        // Turned off by hand on this song.
        assertFalse(auto(romanization = readyRoman))
        // Already in Latin letters.
        assertFalse(auto(romanization = LyricsTranslationUiState.SameLanguage))
        assertFalse(auto(mode = LyricsDisplayMode.Romanized, romanization = readyRoman))
    }
}
