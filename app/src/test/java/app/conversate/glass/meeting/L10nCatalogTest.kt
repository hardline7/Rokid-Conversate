package app.conversate.glass.meeting

import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Exercise shipped translations, rather than only a synthetic two-message dictionary. */
class L10nCatalogTest {
    private val sourceRoot = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }
    private fun catalog() = JSONObject(File(sourceRoot, "assets/companion/messages.json").readText())
    @After fun reset() { L10n.initialize(JSONObject(), null, "ko-KR") }

    @Test fun binderStatusMessagesUseEnglishAndKeepRuntimeDetailsVerbatim() {
        L10n.initialize(catalog(), null, "en-US")
        val expected = mapOf(
            "지원하지 않는 Binder 인터페이스" to "Unsupported Binder interface",
            "서비스 연결 · 등록 요청 완료" to "Service connected · Registration requested",
            "서비스 연결 끊김" to "Service disconnected",
            "서비스 재연결 필요" to "Service reconnection required",
            "서비스가 연결을 거절함" to "Service refused connection",
        )
        expected.forEach { (source, translation) -> assertEquals(translation, L10n.t(source)) }
        assertEquals("Registration failed: original OEM detail", L10n.t("등록 실패: {0}", "original OEM detail"))
        // Error detail is provider data, not an app-owned message or an instruction to translate it.
        assertEquals("Registration failed: 권한 부족", L10n.t("등록 실패: {0}", "권한 부족"))
        L10n.switchLanguage("es-ES")
        assertEquals("Service disconnected", L10n.t("서비스 연결 끊김"))
        assertFalse(L10n.isReady())
    }

    @Test fun everyNativeLiteralHasAnEnglishFallback() {
        val messages = catalog()
        val call = Regex("L10n\\.t\\(\"((?:\\\\.|[^\"\\\\])*)\"")
        var checked = 0
        File(sourceRoot, "java").walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            call.findAll(file.readText()).forEach { match ->
                val source = JSONArray("[\"${match.groupValues[1]}\"]").getString(0)
                assertTrue("Missing English fallback in ${file.name}: $source", messages.has(source))
                checked++
            }
        }
        assertTrue("Source coverage unexpectedly empty", checked > 100)
    }

    @Test fun shippedEnglishCatalogHasNoKoreanValuesAndPreservesPlaceholders() {
        val messages = catalog()
        val placeholders = Regex("\\{\\d+\\}")
        messages.keys().forEach { source ->
            val translated = messages.getString(source)
            assertTrue("Blank English translation: $source", translated.isNotBlank())
            assertFalse("Korean text in English fallback: $source", translated.any { it in '\uac00'..'\ud7a3' })
            assertEquals(source, placeholders.findAll(source).map { it.value }.sorted().toList(),
                placeholders.findAll(translated).map { it.value }.sorted().toList())
        }
    }
}
