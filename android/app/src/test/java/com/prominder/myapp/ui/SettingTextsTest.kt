package com.prominder.myapp.ui

import com.prominder.myapp.data.SettingSpecs
import com.prominder.myapp.i18n.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every setting has its words on screen (else it shows in English whatever the app's language). */
class SettingTextsTest {
    @Test
    fun `every setting has a label, and a help line when it has one in English`() {
        for (spec in SettingSpecs.ALL) {
            val texts = SettingTexts.TEXTS[spec.key]
            assertTrue("no label for ${spec.key}", texts != null)
            assertEquals("help for ${spec.key}", spec.help.isNotEmpty(), texts!!.second != null)
        }
        assertEquals(SettingSpecs.ALL.map { it.key }.toSet(), SettingTexts.TEXTS.keys)
    }

    @Test
    fun `every group and option has its words`() {
        for (spec in SettingSpecs.ALL) {
            assertTrue("no title for group ${spec.group}", spec.group in SettingTexts.GROUPS)
            for ((value, _) in spec.options) assertTrue("no label for ${spec.key}=$value", (spec.key to value) in SettingTexts.OPTIONS)
        }
    }

    @Test
    fun `language tags are normalized to the supported ones`() {
        assertEquals("he", AppLanguage.normalize("iw"))
        assertEquals("he", AppLanguage.normalize("he-IL,en-US"))
        assertEquals("en", AppLanguage.normalize("en-GB"))
        assertEquals("en", AppLanguage.normalize("fr,en"))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.normalize(""))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.normalize("fr"))
    }
}
