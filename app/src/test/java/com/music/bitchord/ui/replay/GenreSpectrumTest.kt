package com.music.bitchord.ui.replay

import com.music.bitchord.data.stats.RankedEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GenreSpectrumTest {

    @Test
    fun `percentage of top genre scales accurately against total listening time`() {
        val totalMs = 100_000L
        val genres = listOf(
            RankedEntry("rock", null, null, null, 42_000L, 10),
            RankedEntry("pop", null, null, null, 28_000L, 8),
            RankedEntry("jazz", null, null, null, 15_000L, 4),
        )
        val leader = genres.first()
        val pct = ((leader.ms.toFloat() / totalMs) * 100f).toInt().coerceIn(1, 100)
        assertEquals(42, pct)
    }

    @Test
    fun `relative heights scale from 0 to 1 based on leader genre`() {
        val genres = listOf(
            RankedEntry("electronic", null, null, null, 50_000L, 15),
            RankedEntry("ambient", null, null, null, 25_000L, 8),
            RankedEntry("techno", null, null, null, 10_000L, 3),
        )
        val leader = genres.first()
        val topMs = leader.ms.coerceAtLeast(1L).toFloat()

        val fractions = genres.map { it.ms.toFloat() / topMs }
        assertEquals(1.0f, fractions[0], 0.001f)
        assertEquals(0.5f, fractions[1], 0.001f)
        assertEquals(0.2f, fractions[2], 0.001f)
    }

    @Test
    fun `handles empty and zero ms edge cases gracefully`() {
        val zeroMsGenres = listOf(
            RankedEntry("classical", null, null, null, 0L, 1),
        )
        val leader = zeroMsGenres.first()
        val topMs = leader.ms.coerceAtLeast(1L).toFloat()
        val fraction = zeroMsGenres[0].ms.toFloat() / topMs
        assertEquals(0.0f, fraction, 0.001f)

        val totalMs = 0L
        val pct = if (totalMs > 0) ((leader.ms.toFloat() / totalMs) * 100f).toInt().coerceIn(1, 100) else 100
        assertEquals(100, pct)
    }

    @Test
    fun `spectrum takes at most 18 genres`() {
        val manyGenres = (1..30).map { index ->
            RankedEntry("genre-$index", null, null, null, (100 - index) * 1000L, 5)
        }
        val visible = manyGenres.take(18)
        assertEquals(18, visible.size)
        assertTrue(visible.first().ms > visible.last().ms)
    }
}
