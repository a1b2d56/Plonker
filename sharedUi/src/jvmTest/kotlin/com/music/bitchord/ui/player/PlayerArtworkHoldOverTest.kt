package com.music.bitchord.ui.player

import coil3.PlatformContext
import com.music.bitchord.data.model.PLAYER_ART_MEMORY_CACHE_KEY_EXTRA
import com.music.bitchord.data.model.PLAYER_ART_PX
import kotlin.test.Test
import kotlin.test.assertEquals

class PlayerArtworkHoldOverTest {

    @Test
    fun playerArtworkUsesAResolutionSpecificMemoryCacheIdentity() {
        val art = PlayerArtwork("content://media/external/audio/albumart/2", PlatformContext.INSTANCE)
        assertEquals(PLAYER_ART_PX.toString(), art.request.memoryCacheKeyExtras[PLAYER_ART_MEMORY_CACHE_KEY_EXTRA])
    }
}
