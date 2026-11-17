package app.conversate.glass.meeting

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class L10nTest {
    @get:Rule val temporary = TemporaryFolder()
    private val messages = JSONObject().put("요약 {0}/{1}", "Summary {0}/{1}").put("회의", "Meeting")
    @After fun reset() { L10n.initialize(JSONObject(), null, "ko-KR") }
    @Test fun normalizesDeviceTagsAndRejectsInvalidPaths() {
        assertEquals("zh-Hant-TW", L10n.normalize("zh_Hant_TW"))
        assertEquals("en-US", L10n.normalize("../../bad"))
    }
    @Test fun koreanEnglishAndFallbackPreserveArgumentsVerbatim() {
        L10n.initialize(messages, null, "en-US")
        assertEquals("Summary 2/3", L10n.t("요약 {0}/{1}", 2, 3))
        assertEquals("Summary {1}/3", L10n.t("요약 {0}/{1}", "{1}", 3))
        assertTrue(L10n.isReady())
        L10n.switchLanguage("ko-KR")
        assertEquals("요약 2/3", L10n.t("요약 {0}/{1}", 2, 3))
        L10n.switchLanguage("es-ES")
        assertFalse(L10n.isReady())
        assertEquals("Meeting", L10n.t("회의"))
        assertEquals("원문 데이터", L10n.t("원문 데이터"))
    }
    @Test fun acceptsCompleteValidatedPackAndCachesByLanguage() {
        val dir = temporary.newFolder()
        L10n.initialize(messages, dir, "es-ES")
        val keys = L10n.missing()
        val translations = JSONObject()
        keys.forEachIndexed { i, key -> translations.put("m$i", if (key == "회의") "Reunión" else "Resumen {0}/{1}") }
        assertTrue(L10n.accept("es-ES", keys, JSONObject().put("translations", translations)))
        assertTrue(L10n.isReady())
        assertEquals("Reunión", L10n.t("회의"))
        assertEquals("Reunión", L10n.label("Meeting"))
        L10n.switchLanguage("ja-JP")
        assertEquals("Meeting", L10n.t("회의"))
        L10n.switchLanguage("es-ES")
        assertEquals("Resumen 1/2", L10n.t("요약 {0}/{1}", 1, 2))
    }
    @Test fun rejectsMissingPlaceholdersAndStaleLanguageWithoutPartialCommit() {
        L10n.initialize(messages, null, "es-ES")
        val keys = L10n.missing()
        val bad = JSONObject().put("translations", JSONObject().put("m0", "Resumen {0}").put("m1", "Reunión"))
        assertFalse(L10n.accept("es-ES", keys, bad))
        assertFalse(L10n.accept("ja-JP", keys, bad))
        assertEquals(keys, L10n.missing())
    }
}
