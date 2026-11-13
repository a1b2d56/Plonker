package com.music.bitchord.data

import com.music.bitchord.data.model.Song

/** What looking for a track's other version (video or audio release) came back with. */
sealed interface VersionLookup {
    /** The other version. */
    data class Found(val song: Song) : VersionLookup

    /** The lookups answered and there is no other version. */
    data object None : VersionLookup

    /** The lookups did not answer. Nothing is known about the track; asking again may find one. */
    data object Unavailable : VersionLookup
}
