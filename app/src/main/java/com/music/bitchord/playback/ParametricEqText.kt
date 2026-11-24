package com.music.bitchord.playback

import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

/**
 * Reads and formats equaliser profiles in Equalizer APO / Peace parametric EQ
 * and GraphicEQ formats.
 */
object ParametricEqText {
    private val PREAMP = Regex("""^\s*Preamp:\s*([-+]?\d+(?:\.\d+)?)\s*dB""", RegexOption.IGNORE_CASE)
    private val FILTER = Regex(
        """^\s*Filter\s*\d*\s*:\s*(ON|OFF)\s+([A-Z]+)\s+Fc\s+([-+]?\d+(?:\.\d+)?)\s*Hz""" +
            """(?:\s+Gain\s+([-+]?\d+(?:\.\d+)?)\s*dB)?(?:\s+Q\s+([-+]?\d+(?:\.\d+)?))?""",
        RegexOption.IGNORE_CASE,
    )
    private val GRAPHIC_EQ = Regex("""^\s*GraphicEQ:\s*(.*)$""", RegexOption.IGNORE_CASE)

    data class FilterEntry(
        val type: String,
        val frequencyHz: Float,
        val gainDb: Float,
        val q: Float,
        val enabled: Boolean,
    )

    data class ParsedProfile(
        val preampDb: Float,
        val filters: List<FilterEntry>,
        val bands: List<Float>,
    )

    /**
     * Parses text in Equalizer APO, Peace, or GraphicEQ format into 7-band gains
     * matching [EqLayout.MANUAL_BANDS_HZ].
     */
    fun parse(text: String): ParsedProfile? {
        val standardBands = EqLayout.MANUAL_BANDS_HZ
        var preamp = 0f
        val filters = mutableListOf<FilterEntry>()
        val graphicEqPairs = mutableListOf<Pair<Float, Float>>()

        for (line in text.lineSequence()) {
            PREAMP.find(line)?.let { preamp = it.groupValues[1].toFloatOrNull() ?: 0f }
            val graphicMatch = GRAPHIC_EQ.find(line)
            if (graphicMatch != null) {
                val tokens = graphicMatch.groupValues[1].split(';')
                for (token in tokens) {
                    val parts = token.trim().split(Regex("""\s+"""))
                    if (parts.size >= 2) {
                        val freq = parts[0].toFloatOrNull()
                        val gain = parts[1].toFloatOrNull()
                        if (freq != null && gain != null) {
                            graphicEqPairs.add(freq to gain)
                        }
                    }
                }
                continue
            }
            val match = FILTER.find(line) ?: continue
            val enabled = match.groupValues[1].equals("ON", ignoreCase = true)
            val type = match.groupValues[2]
            val fc = match.groupValues[3].toFloatOrNull() ?: continue
            val gain = match.groupValues[4].toFloatOrNull() ?: 0f
            val q = match.groupValues[5].toFloatOrNull() ?: 1.41f
            filters += FilterEntry(
                type = type,
                frequencyHz = fc,
                gainDb = gain,
                q = q,
                enabled = enabled,
            )
        }

        val resultBands = FloatArray(standardBands.size) { 0f }

        if (graphicEqPairs.isNotEmpty()) {
            for (i in standardBands.indices) {
                val targetHz = standardBands[i]
                val nearest = graphicEqPairs.minByOrNull { abs(it.first - targetHz) }
                if (nearest != null) {
                    resultBands[i] = nearest.second.coerceIn(-EqLayout.MANUAL_RANGE_DB, EqLayout.MANUAL_RANGE_DB)
                }
            }
            return ParsedProfile(preamp, filters, resultBands.toList())
        }

        if (filters.isEmpty()) return null

        for (i in standardBands.indices) {
            val targetHz = standardBands[i]
            var netGain = 0f
            for (f in filters) {
                if (!f.enabled) continue
                val distRatio = abs(ln(targetHz.toDouble() / f.frequencyHz.toDouble()))
                val bandwidth = 1.0 / (f.q.coerceAtLeast(0.1f) * 1.5)
                val weight = exp(-0.5 * (distRatio / bandwidth) * (distRatio / bandwidth)).toFloat()
                netGain += f.gainDb * weight
            }
            resultBands[i] = netGain.coerceIn(-EqLayout.MANUAL_RANGE_DB, EqLayout.MANUAL_RANGE_DB)
        }

        return ParsedProfile(preamp, filters, resultBands.toList())
    }

    /**
     * Formats the current 7-band manual equaliser gains into standard Equalizer APO syntax.
     */
    fun format(bands: List<Float>): String = buildString {
        appendLine("Preamp: 0.0 dB")
        EqLayout.MANUAL_BANDS_HZ.forEachIndexed { index, hz ->
            val gain = bands.getOrElse(index) { 0f }
            val type = when (index) {
                0 -> "LSC"
                EqLayout.MANUAL_BANDS_HZ.lastIndex -> "HSC"
                else -> "PK"
            }
            appendLine("Filter ${index + 1}: ON $type Fc %.0f Hz Gain %.1f dB Q 1.41".format(Locale.ROOT, hz, gain))
        }
    }
}
