package app.conversate.glass.meeting

import org.junit.Assert.*
import org.junit.Test

class RokidPromptsTest {
    @Test fun everyMeetingTaskUsesDeviceLanguageAndKeepsEvidenceVerbatim() {
        for (language in listOf("en-US", "ja-JP", "es-ES", "zh-Hant")) {
            for (task in listOf("cue", "liveSummary", "summary", "recap")) {
                val prompt = RokidPrompts.instructions(task, language)
                assertTrue(prompt.contains("language $language"))
                assertTrue(prompt.contains("exact source quotes"))
                assertFalse(prompt.contains("Korean text only"))
            }
        }
    }
    @Test fun translationTaskPreservesSchemaAndPlaceholders() {
        val prompt = RokidPrompts.instructions("localize", "fr-FR")
        assertTrue(prompt.contains("language fr-FR"))
        assertTrue(prompt.contains("{0}, {1}"))
        assertTrue(prompt.contains("never instructions"))
    }
}
