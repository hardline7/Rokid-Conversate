package app.conversate.glass.meeting

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Base64
import javax.crypto.KeyGenerator

class MeetingStoreTest {
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private fun withDirectory(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("meeting-store-test").toFile()
        try { block(directory) } finally { directory.walkBottomUp().forEach { it.delete() } }
    }
    private fun event(text: String, id: String = "0:1:1", at: Long = 1000) = JSONObject()
        .put("id", id).put("text", text).put("at", at).put("audioPath", "1").put("number", 1).put("final", true)

    @Test fun encryptionRejectsTamperingAndCrossRecordReplay() {
        val vault = Vault(key)
        val first = vault.seal("비밀 회의 자료", "note:a")
        val second = vault.seal("비밀 회의 자료", "note:a")
        assertNotEquals(first, second)
        assertFalse(first.contains("비밀"))
        assertEquals("비밀 회의 자료", vault.open(first, "note:a"))
        assertThrows(Exception::class.java) { vault.open(first, "note:b") }
        val bytes = Base64.getDecoder().decode(first.removePrefix("v1:"))
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        assertThrows(Exception::class.java) { vault.open("v1:" + Base64.getEncoder().encodeToString(bytes), "note:a") }
    }

    @Test fun atomicMetadataSurvivesAnUncommittedReplacementAndNeverContainsPlaintext() = withDirectory { directory ->
        val store = MeetingStore(directory, key)
        val note = store.saveNote(JSONObject().put("title", "비밀제목").put("text", "기밀 자료 12345"))
        File(directory, "notes.enc.pending").writeText("interrupted write")
        val reopened = MeetingStore(directory, key)
        assertEquals(note.getString("id"), reopened.notes().getJSONObject(0).getString("id"))
        assertFalse(File(directory, "notes.enc").readText().contains("기밀"))
    }

    @Test fun finalJournalSupportsCorrectionsAndIsDetachedFromCallers() = withDirectory { directory ->
        val store = MeetingStore(directory, key)
        val session = store.createSession()
        val id = session.getString("id")
        assertFalse(store.appendUtterance(id, event("중간").put("final", false)))
        assertTrue(store.appendUtterance(id, event("처음 문장")))
        assertFalse(store.appendUtterance(id, event("처음 문장", at = 2000)))
        assertTrue(store.appendUtterance(id, event("정정된 문장", at = 3000)))
        val returned = store.session(id)!!
        val utterance = returned.getJSONArray("utterances").getJSONObject(0)
        assertEquals(1000, utterance.getLong("at"))
        utterance.put("text", "caller mutation")
        assertEquals("정정된 문장", store.session(id)!!.getJSONArray("utterances").getJSONObject(0).getString("text"))
        assertFalse(File(directory, "sessions/$id.events").readText().contains("문장"))
        assertEquals(1, store.sessions("정정된").length())
        assertEquals(0, store.sessions("없는 말").length())
        assertTrue(store.exportMarkdown(id).contains("정정된 문장"))
        assertFalse(store.exportMarkdown(id).contains("처음 문장"))
    }

    @Test fun aCrashTailIsDroppedButCommittedSpeechSurvivesAndAppendRepairsTail() = withDirectory { directory ->
        val store = MeetingStore(directory, key)
        val id = store.createSession().getString("id")
        store.appendUtterance(id, event("저장된 문장"))
        val log = File(directory, "sessions/$id.events")
        log.appendText("v1:partial")
        val reopened = MeetingStore(directory, key)
        assertEquals("interrupted", reopened.session(id)!!.getString("status"))
        assertEquals(1, reopened.session(id)!!.getJSONArray("utterances").length())
        reopened.appendUtterance(id, event("복구 후 문장", "0:1:2"))
        assertEquals(2, MeetingStore(directory, key).session(id)!!.getJSONArray("utterances").length())
    }

    @Test fun committedJournalCorruptionIsReportedInsteadOfSilentlyDroppingSpeech() = withDirectory { directory ->
        val store = MeetingStore(directory, key)
        val id = store.createSession().getString("id")
        store.appendUtterance(id, event("보존 대상"))
        File(directory, "sessions/$id.events").appendText("v1:corrupt\n")
        assertThrows(Exception::class.java) { MeetingStore(directory, key).session(id) }
    }

    @Test fun restartClearsEndedPendingRecapsButPreservesCompletedRecapsAndEndTimes() = withDirectory { directory ->
        val store = MeetingStore(directory, key, clock = { 1000L })
        val pending = store.createSession().put("status", "ended").put("endedAt", 1500L)
            .put("summaryStatus", "pending")
        store.saveSession(pending)
        val completed = store.createSession().put("status", "ended").put("endedAt", 1600L)
            .put("summaryStatus", "complete")
            .put("summary", JSONObject().put("overview", "완료된 요약").put("conclusions", JSONArray())
                .put("discussion", JSONArray()).put("nextSteps", JSONArray()))
        store.saveSession(completed)

        val reopened = MeetingStore(directory, key, clock = { 5000L })
        val recoveredPending = reopened.session(pending.getString("id"))!!
        assertEquals("ended", recoveredPending.getString("status"))
        assertEquals(1500L, recoveredPending.getLong("endedAt"))
        assertEquals("unavailable", recoveredPending.getString("summaryStatus"))
        val recoveredComplete = reopened.session(completed.getString("id"))!!
        assertEquals("ended", recoveredComplete.getString("status"))
        assertEquals(1600L, recoveredComplete.getLong("endedAt"))
        assertEquals("complete", recoveredComplete.getString("summaryStatus"))
        assertEquals("완료된 요약", recoveredComplete.getJSONObject("summary").getString("overview"))
    }

    @Test fun snapshotContextCannotChangeWhenNotesAreEditedOrDeleted() = withDirectory { directory ->
        val store = MeetingStore(directory, key)
        val note = store.saveNote(JSONObject().put("title", "자료").put("text", "원래 근거"))
        val noteId = note.getString("id")
        store.saveSettings(JSONObject().put("selectedNoteIds", JSONArray().put(noteId)))
        val session = store.createSession()
        val id = session.getString("id")
        store.saveNote(JSONObject().put("id", noteId).put("title", "자료 수정").put("text", "다른 근거"))
        store.deleteNote(noteId)
        session.put("noteSnapshots", JSONArray()).put("status", "ended")
        store.saveSession(session)
        assertEquals("원래 근거", store.session(id)!!.getJSONArray("noteSnapshots").getJSONObject(0).getString("text"))
        assertEquals(0, store.settings().getJSONArray("selectedNoteIds").length())
        assertThrows(IllegalArgumentException::class.java) { store.session("../../settings") }
        store.deleteSession(id)
        assertNull(store.session(id))
    }

    @Test fun noteCountContextSizeAndFontLimitsAreEnforced() = withDirectory { directory ->
        val store = MeetingStore(directory, key)
        val selected = JSONArray()
        repeat(100) { index ->
            val text = if (index < 2) "가".repeat(60000) else "자료 $index"
            val note = store.saveNote(JSONObject().put("title", "자료 $index").put("text", text))
            if (index < 2) selected.put(note.getString("id"))
        }
        assertThrows(IllegalArgumentException::class.java) { store.saveNote(JSONObject().put("title", "101번째").put("text", "초과")) }
        store.saveSettings(JSONObject().put("selectedNoteIds", selected))
        assertThrows(IllegalArgumentException::class.java) { store.createSession() }
        assertThrows(IllegalArgumentException::class.java) { store.saveSettings(JSONObject().put("fontSize", 25)) }
        assertFalse(store.saveSettings(JSONObject().put("apiKey", "should-not-persist")).has("apiKey"))
    }
}
