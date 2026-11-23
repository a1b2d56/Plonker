package com.music.bitchord.data.scrobbling

import com.music.bitchord.data.model.maskKnownBands
import com.music.bitchord.data.model.unmaskKnownBands

/**
 * Returns the first credited artist for services that do not handle joint
 * artist credits well. The original value is preserved when no separator is
 * present so names such as "AC/DC" or "Earth, Wind & Fire" remain intact.
 */
internal fun String.primaryArtist(): String {
    val (masked, bands) = maskKnownBands(this)
    val leadMasked = masked.split(PRIMARY_ARTIST_SEPARATOR, limit = 2).first().trim()
    val lead = unmaskKnownBands(leadMasked, bands).trim()
    return lead.ifBlank { this }
}

private val PRIMARY_ARTIST_SEPARATOR = Regex("""\s*,\s*|\s+&\s+|\s+＆\s+""")
