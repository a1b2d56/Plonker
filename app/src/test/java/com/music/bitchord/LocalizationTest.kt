package com.music.bitchord

import com.music.bitchord.ui.components.SUPPORTED_LANGUAGES
import com.music.bitchord.ui.components.languageDisplayNameRes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class LocalizationTest {

    @Test
    fun supportedLanguages_containsEnglishGbAndUs() {
        val gbLanguage = SUPPORTED_LANGUAGES.firstOrNull { it.tag == "en-GB" }
        assertNotNull("English (GB) language must be in SUPPORTED_LANGUAGES", gbLanguage)
        assertEquals(R.string.english_gb, gbLanguage?.nameRes)
        assertEquals(R.string.english_gb, languageDisplayNameRes("en-GB"))

        val usLanguage = SUPPORTED_LANGUAGES.firstOrNull { it.tag == "en-US" }
        assertNotNull("English (US) language must be in SUPPORTED_LANGUAGES", usLanguage)
        assertEquals(R.string.english_us, usLanguage?.nameRes)
        assertEquals(R.string.english_us, languageDisplayNameRes("en-US"))
    }

    @Test
    fun localesConfig_matchesSupportedLanguages() {
        val projectRoot = File(".").canonicalFile
        val resDir = if (File(projectRoot, "app/src/main/res").exists()) {
            File(projectRoot, "app/src/main/res")
        } else {
            File(projectRoot, "src/main/res")
        }
        val localesConfigFile = File(resDir, "xml/locales_config.xml")
        assertTrue("locales_config.xml must exist", localesConfigFile.exists())

        val factory = DocumentBuilderFactory.newInstance()
        val builder = factory.newDocumentBuilder()
        val doc = builder.parse(localesConfigFile)
        val localeNodes = doc.getElementsByTagName("locale")

        val configuredLocales = mutableListOf<String>()
        for (i in 0 until localeNodes.length) {
            val node = localeNodes.item(i)
            val name = node.attributes.getNamedItem("android:name")?.nodeValue
            if (name != null) configuredLocales.add(name)
        }

        assertTrue("locales_config.xml must include 'en-GB'", configuredLocales.contains("en-GB"))
        assertTrue("locales_config.xml must include 'en-US'", configuredLocales.contains("en-US"))

        for (lang in SUPPORTED_LANGUAGES) {
            assertTrue(
                "locales_config.xml must include '${lang.tag}'",
                configuredLocales.contains(lang.tag),
            )
        }
    }
}
