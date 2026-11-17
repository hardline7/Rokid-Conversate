package app.conversate.glass.meeting

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CuePolicyTest {
    private val notes = JSONArray().put(JSONObject().put("id", "note1").put("title", "회의 자료").put("text", "예산은 100만 원이며 담당자는 김민수입니다."))
    private fun cue(type: String = "Answers", title: String = "예산") = JSONObject()
        .put("type", type).put("title", title).put("body", "예산 100만 원")
        .put("confidence", 0.9).put("sourceId", "note1").put("quote", "예산은 100만 원")
    @Test fun inventedEvidenceNumbersAndNonfiniteConfidenceAreRejected() {
        val policy = CuePolicy()
        assertFalse(policy.offer(cue().put("sourceId", "other"), notes))
        assertFalse(policy.offer(cue().put("quote", "예산은 200만 원"), notes))
        assertFalse(policy.offer(cue().put("body", "예산 200만 원"), notes))
        assertFalse(policy.offer(cue().put("quote", "예산은 1100만 원"),
            JSONArray().put(JSONObject().put("id", "note1").put("title", "자료").put("text", "예산은 1100만 원"))))
        assertFalse(policy.offer(cue().put("confidence", "NaN"), notes))
        assertTrue(policy.offer(cue(), notes))
    }
    @Test fun groundedPriorityDurationAndRateAreEnforced() {
        val policy = CuePolicy()
        assertTrue(policy.offer(cue("Suggestions", "제안"), notes))
        assertTrue(policy.offer(cue(), notes))
        assertEquals("Answers", policy.tick(1000)!!.getString("type"))
        assertNull(policy.tick(5000))
        assertNull(policy.tick(10000))
        assertEquals("Suggestions", policy.tick(14000)!!.getString("type"))
        for (n in 1..4) assertTrue(policy.offer(cue("Suggestions", "제안$n"), notes))
        assertNotNull(policy.tick(27000)); assertNotNull(policy.tick(40000))
        assertNull(policy.tick(53000)) // Fifth cue within a minute is held.
        assertNotNull(policy.tick(61000))
    }
    @Test fun conceptsShownOnlyOncePerSession() {
        val policy = CuePolicy()
        assertTrue(policy.offer(cue("Concepts", "리텐션"), notes))
        assertNotNull(policy.tick(0))
        assertFalse(policy.offer(cue("Concepts", "리텐션"), notes))
        policy.clear()
        assertTrue(policy.offer(cue("Concepts", "리텐션"), notes))
    }
    @Test fun staleQueuedCuesExpireAndPausePreservesConceptDeduplication() {
        val policy = CuePolicy()
        assertTrue(policy.offer(cue("Concepts", "예산"), notes, 0))
        assertNull(policy.tick(16000))
        assertTrue(policy.offer(cue("Concepts", "예산"), notes, 17000))
        assertNotNull(policy.tick(17000))
        policy.suspend()
        assertFalse(policy.offer(cue("Concepts", "예산"), notes, 18000))
    }
}
