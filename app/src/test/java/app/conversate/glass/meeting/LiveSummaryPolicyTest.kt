package app.conversate.glass.meeting

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LiveSummaryPolicyTest {
    private fun rows(count: Int = 1) = JSONArray((0 until count).map {
        JSONObject().put("id", "u$it").put("at", 1000L + it * 1000).put("text", "RAG 도입을 검토하자는 제안입니다.")
    })
    @Test fun waitsForPauseButContinuousSpeechHasBoundedWindow() {
        assertNull(LiveSummaryPolicy.batch(rows(), 0, 3500, 1000))
        assertNotNull(LiveSummaryPolicy.batch(rows(), 0, 4000, 1000))
        assertNotNull(LiveSummaryPolicy.batch(rows(), 0, 26000, 26000))
        assertNotNull(LiveSummaryPolicy.batch(rows(6), 0, 6500, 6500))
    }
    @Test fun cursorAndChunkLimitDoNotDropLaterSpeech() {
        assertEquals(8, LiveSummaryPolicy.batch(rows(12), 0, 30000, 29000)!!.length())
        val tail = LiveSummaryPolicy.batch(rows(12), 8, 35000, 29000)!!
        assertEquals("u8", tail.getJSONObject(0).getString("id"))
        assertEquals(4, tail.length())
        assertNull(LiveSummaryPolicy.batch(rows(12), 12, 30000, 29000))
        assertNotNull(LiveSummaryPolicy.batch(rows(), 0, 1001, 1000, true))
    }
    @Test fun swipesPinOlderCardAndReturnToLiveAtNewest() {
        assertEquals(1, LiveSummaryPolicy.navigate(-1, -1, 3))
        assertEquals(0, LiveSummaryPolicy.navigate(1, -1, 4))
        assertEquals(-1, LiveSummaryPolicy.navigate(2, 1, 4))
        assertEquals(-1, LiveSummaryPolicy.navigate(-1, 1, 0))
    }
    @Test fun ambiguousOrLowConfidenceGlossaryIsOmitted() {
        val raw = JSONObject().put("title", "검토").put("body", "도입을 검토함")
            .put("terms", JSONArray().put(JSONObject().put("term", "RAG").put("definition", "회의에서 언급된 특정 시스템 또는 기술 용어").put("confidence", 0.99)))
        assertEquals(0, LiveSummaryPolicy.validate(raw, rows())!!.getJSONArray("terms").length())
        raw.getJSONArray("terms").getJSONObject(0).put("definition", "검색 증강 생성").put("confidence", 0.7)
        assertEquals(0, LiveSummaryPolicy.validate(raw, rows())!!.getJSONArray("terms").length())
    }
    @Test fun rejectsOversizeCardsAndUngroundedTermNames() {
        val output = JSONObject().put("title", "도입 제안").put("body", "RAG 도입을 검토하자는 제안이며 결정되지 않음.")
            .put("terms", JSONArray().put(JSONObject().put("term", "RAG").put("definition", "검색한 문서를 참고해 답변하는 방식").put("confidence", 0.95))
                .put(JSONObject().put("term", "양자컴퓨터").put("definition", "없는 용어")))
        val card = LiveSummaryPolicy.validate(output, rows())!!
        assertEquals(1, card.getJSONArray("terms").length())
        assertEquals("u0", card.getJSONArray("utteranceIds").getString(0))
        assertNull(LiveSummaryPolicy.validate(output.put("body", "가".repeat(73)), rows()))
        assertNull(LiveSummaryPolicy.validate(JSONObject().put("title", "제목").put("body", 12), rows()))
    }
}
