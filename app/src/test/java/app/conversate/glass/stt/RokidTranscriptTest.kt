package app.conversate.glass.stt

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class RokidTranscriptTest {
    private fun frame(data: JSONObject, key: String = "Accessibility_ASR"): String = JSONObject()
        .put("type", "cmd_bluetooth_accessibility_data")
        .put("data", JSONObject().put("key", key).put("data", data.toString())).toString()
    private fun payload(final: Boolean) = JSONObject().put("audio_path", "1")
        .put("content", "회의를 시작하겠습니다.").put("final", final).put("number", 1).put("role", "")

    @Test fun realWireFormatRetainsPartialAndFinal() {
        val partial = RokidTranscript.parse(frame(payload(false)))!!
        val final = RokidTranscript.parse(frame(payload(true)))!!
        assertFalse(partial.isFinal); assertTrue(final.isFinal)
        assertEquals(partial.number, final.number)
        assertEquals("회의를 시작하겠습니다.", final.content)
        assertEquals("1", final.audioPath)
    }
    @Test fun malformedOrUnrelatedMessagesAreIgnored() {
        assertNull(RokidTranscript.parse("bad json"))
        assertNull(RokidTranscript.parse(frame(payload(true), "Accessibility_start")))
        assertNull(RokidTranscript.parse(frame(payload(true)).replace("cmd_bluetooth_accessibility_data", "other")))
        assertNull(RokidTranscript.parse(frame(payload(true).put("final", "true"))))
        assertNull(RokidTranscript.parse(frame(payload(true).apply { remove("final") })))
        assertNull(RokidTranscript.parse(frame(payload(true).put("number", -1))))
    }
    @Test fun journalDropsPartialsDeduplicatesFinalsAndRetainsCorrectionsAndPaths() {
        val directory = Files.createTempDirectory("rokid-journal").toFile()
        try {
            val journal = TranscriptJournal(directory)
            val event = RokidTranscript.parse(frame(payload(false)))!!
            assertFalse(journal.append(event)); assertFalse(journal.file.exists())
            val final = event.copy(isFinal = true)
            assertTrue(journal.append(final)); assertFalse(journal.append(final))
            assertTrue(journal.append(final.copy(content = "수정된 문장")))
            assertTrue(journal.append(final.copy(audioPath = "0")))
            val lines = journal.file.readLines().map(::JSONObject)
            assertEquals(3, lines.size)
            assertTrue(lines.all { it.getBoolean("final") })
            assertEquals("수정된 문장", lines[1].getString("content"))
            assertNotEquals(journal.file, TranscriptJournal(directory).file)
        } finally { directory.listFiles()?.forEach { it.delete() }; directory.delete() }
    }
}
