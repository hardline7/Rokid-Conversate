package app.conversate.glass.meeting

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CueTriggerPolicyTest {
    private fun rows(count: Int = 1) = JSONArray((0 until count).map {
        JSONObject().put("id", "u$it").put("at", 100000L + it * 1000).put("text", "RAG 도입을 검토합시다.")
    })
    @Test fun independentCueTriggerUsesShortPauseAndBoundsContinuousSpeech() {
        assertNull(CueTriggerPolicy.batch(rows(), 0, 101000, 100000, 0))
        assertNotNull(CueTriggerPolicy.batch(rows(), 0, 101200, 100000, 0))
        assertNotNull(CueTriggerPolicy.batch(rows(), 0, 106000, 106000, 0))
        assertNotNull(CueTriggerPolicy.batch(rows(3), 0, 102000, 102000, 0))
        assertNull(CueTriggerPolicy.batch(rows(3), 0, 108000, 102000, 101000))
        assertEquals(6, CueTriggerPolicy.batch(rows(8), 0, 110000, 107000, 0)!!.length())
        assertNull(CueTriggerPolicy.batch(rows(), 1, 110000, 100000, 0))
    }
    @Test fun acronymMatchingRejectsWordFragmentsButAllowsKoreanParticlesAndUnicodeTerms() {
        assertTrue(CueTriggerPolicy.mentions("RAG를 도입합시다.", "RAG"))
        assertTrue(CueTriggerPolicy.mentions("Use rag-based retrieval", "RAG"))
        assertFalse(CueTriggerPolicy.mentions("FRAGMENT", "RAG"))
        assertTrue(CueTriggerPolicy.mentions("제로트러스트를 검토", "제로트러스트"))
        assertFalse(CueTriggerPolicy.mentions("ordinary text", ""))
    }
    @Test fun oldUnprocessedSpeechIsSkippedRatherThanPoppingUpMinutesLater() {
        assertEquals(0, CueTriggerPolicy.freshCursor(rows(3), 0, 130000))
        assertEquals(2, CueTriggerPolicy.freshCursor(rows(3), 0, 132000))
        assertEquals(3, CueTriggerPolicy.freshCursor(rows(3), 0, 140000))
    }
    @Test fun conceptsRequireExactSpeechEvidenceAndHighConfidence() {
        val cue = JSONObject().put("type", "Concepts").put("title", "RAG").put("body", "검색한 문서를 참고해 답을 만드는 방식")
            .put("confidence", 0.95).put("utteranceId", "u0").put("triggerTerm", "RAG").put("triggerQuote", "RAG 도입")
        assertFalse(CuePolicy().offer(cue, JSONArray()))
        assertTrue(CuePolicy().offer(cue, JSONArray(), 110000, rows()))
        assertFalse(CuePolicy().offer(JSONObject(cue.toString()).put("utteranceId", "other"), JSONArray(), 110000, rows()))
        assertFalse(CuePolicy().offer(JSONObject(cue.toString()).put("confidence", 0.89), JSONArray(), 110000, rows()))
        assertFalse(CuePolicy().offer(JSONObject(cue.toString()).put("triggerQuote", "RAG 결정"), JSONArray(), 110000, rows()))
        assertFalse(CuePolicy().offer(JSONObject(cue.toString()).put("title", "GPU"), JSONArray(), 110000, rows()))
        assertFalse(CuePolicy().offer(JSONObject(cue.toString()).put("body", "특정 시스템으로 추정됩니다"), JSONArray(), 110000, rows()))
    }
    @Test fun queuedConceptsAreDeduplicatedAndClearlyLabelledAsModelKnowledge() {
        val policy = CuePolicy()
        val cue = JSONObject().put("type", "Concepts").put("title", "RAG").put("body", "검색한 문서를 참고해 답을 만드는 방식")
            .put("confidence", 0.95).put("utteranceId", "u0").put("triggerTerm", "RAG").put("triggerQuote", "RAG 도입")
        assertTrue(policy.offer(cue, JSONArray(), 110000, rows()))
        assertFalse(policy.offer(JSONObject(cue.toString()).put("body", "또 다른 설명"), JSONArray(), 110000, rows()))
        val shown = policy.tick(110000)!!
        assertEquals("modelKnowledge", shown.getString("sourceKind"))
        assertEquals("u0", shown.getString("utteranceId"))
        assertEquals("", shown.getString("quote"))
        assertEquals(1, policy.seenConcepts().length())
        policy.dismiss()
        assertFalse(policy.offer(cue, JSONArray(), 120000, rows()))
    }
}
