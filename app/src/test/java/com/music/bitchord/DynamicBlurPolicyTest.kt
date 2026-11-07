package com.music.bitchord

import com.music.bitchord.data.settings.dynamicBlurReduced
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DynamicBlurPolicyTest {

    @Test
    fun `blur draws by default`() {
        assertFalse(dynamicBlurReduced(setting = false, powerSave = false, highPerformance = false))
    }

    @Test
    fun `the listener's switch reduces it`() {
        assertTrue(dynamicBlurReduced(setting = true, powerSave = false, highPerformance = false))
    }

    @Test
    fun `battery saver reduces it with the switch off`() {
        assertTrue(dynamicBlurReduced(setting = false, powerSave = true, highPerformance = false))
    }

    @Test
    fun `high performance mode keeps every effect, battery saver or not`() {
        assertFalse(dynamicBlurReduced(setting = false, powerSave = true, highPerformance = true))
        assertFalse(dynamicBlurReduced(setting = true, powerSave = true, highPerformance = true))
    }
}
