package app.conversate.glass.lenspreview

import android.app.Activity
import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Bundle
import app.conversate.glass.meeting.L10n
import app.conversate.glass.meeting.MeetingLensView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Renders the production View with device fonts. Never opens or instruments the meeting app. */
class LensPreviewInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val result = Bundle()
        try {
            val directory = File(targetContext.filesDir, "lens-previews").apply { mkdirs() }
            val catalog = JSONObject(targetContext.assets.open("companion/messages.json").bufferedReader().use { it.readText() })
            val cases = listOf("idle", "summary", "history", "captions-off", "paused", "cue", "notes", "empty", "confirm", "offline", "large", "long", "cue-only", "cue-only-captions-off", "stop-confirm", "empty-notes", "empty-cues", "long-notes")
            var rendered = 0
            for (language in listOf("ko-KR", "en-US")) {
                L10n.initialize(catalog, null, language)
                for (name in cases) {
                    val bitmap = Bitmap.createBitmap(480, 640, Bitmap.Config.ARGB_8888)
                    runOnMainSync {
                        val view = MeetingLensView(targetContext)
                        view.layout(0, 0, 480, 640)
                        view.render(fixture(name, language))
                        view.draw(Canvas(bitmap))
                    }
                    val pixels = IntArray(480 * 640)
                    bitmap.getPixels(pixels, 0, 480, 0, 0, 480, 640)
                    var light = 0
                    pixels.forEachIndexed { index, pixel ->
                        val x = index % 480; val y = index / 480
                        check(Color.red(pixel) == 0 && Color.blue(pixel) == 0) { "Non-green pixel in $name" }
                        if (Color.green(pixel) != 0) {
                            light++
                            check(x in 16..463 && y in 40..559) { "Unsafe pixel in $name: $x,$y" }
                            check(y < 558) { "Content touches the lower clipping edge: $name" }
                        }
                    }
                    check(light > 1200) { "Empty render: $name" }
                    File(directory, "${language.substringBefore('-')}-$name.png").outputStream().use {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                    bitmap.recycle(); rendered++
                }
            }
            result.putString("rendered", rendered.toString())
            result.putString("directory", directory.absolutePath)
            finish(Activity.RESULT_OK, result)
        } catch (failure: Throwable) {
            result.putString("error", failure.stackTraceToString().take(4000))
            finish(Activity.RESULT_CANCELED, result)
        }
    }

    private fun fixture(name: String, language: String): JSONObject {
        val ko = language == "ko-KR"
        val now = System.currentTimeMillis()
        val settings = JSONObject().put("captions", !name.endsWith("captions-off")).put("cues", true)
            .put("summaries", !name.startsWith("cue-only")).put("fontSize", if (name == "large" || name.startsWith("long")) 18 else 16)
        val term = JSONObject().put("term", "RAG").put("definition", if (ko) "검색한 자료를 근거로 답변을 생성하는 방식" else "AI answers based on retrieved sources.")
        val card = JSONObject().put("title", if (ko) "다음 파일럿을 위한 제안" else "Pilot proposal")
            .put("body", if (ko) "다음 주 소규모 파일럿을 검토합니다. 비용과 일정은 확인 후 결정할 예정입니다." else "A small pilot is proposed. Budget and timing still need confirmation.")
            .put("terms", JSONArray().put(term))
        if (name == "long") {
            card.put("title", "가".repeat(16)).put("body", "가".repeat(72))
            term.put("term", "나".repeat(16)).put("definition", "다".repeat(40))
        }
        val history = JSONArray().put(JSONObject().put("title", if (ko) "이번 회의에서 확인한 내용" else "Earlier discussion")
            .put("body", if (ko) "고객의 요구 사항을 먼저 정리하고, 필요한 자료를 함께 검토했습니다." else "The discussion reviewed customer needs and supporting material.").put("terms", JSONArray())).put(card)
        val cue = JSONObject().put("type", "Answers").put("title", if (ko) "파일럿 예산 확인" else "Pilot budget")
            .put("body", if (ko) "확정 예산은 100만 원입니다." else "The agreed budget is KRW 1 million.")
            .put("sourceTitle", if (ko) "11월 프로젝트 사전 자료" else "November project notes")
        val noteText = if (ko) "이번 파일럿은 고객 요청을 검증하기 위한 단계입니다. 먼저 실제 사용 환경을 확인하고, 결과를 토대로 범위와 일정을 논의합니다. 담당자는 검토 자료를 준비하고 다음 회의에서 공유합니다." else "The pilot will validate customer needs. First review the real usage environment, then discuss scope and timing using the findings."
        val session = JSONObject().put("status", if (name == "paused") "paused" else "running").put("createdAt", now - 728000)
            .put("liveSummaries", if (name == "empty") JSONArray() else history)
            .put("cues", if (name == "empty-cues") JSONArray() else JSONArray().put(cue))
            .put("noteSnapshots", if (name == "empty-notes") JSONArray() else JSONArray().put(JSONObject().put("title", if (ko) "파일럿 검토 메모" else "Pilot review notes").put("text", if (name == "long-notes") "가".repeat(140) else noteText)))
            .put("utterances", JSONArray().put(JSONObject().put("text", if (ko) "먼저 작은 규모로 시작하고, 결과를 보면서 다음 단계를 정하면 좋겠습니다." else "Let's start small and use the results to decide the next step.")))
        val state = JSONObject().put("active", if (name == "idle") JSONObject.NULL else session)
            .put("settings", settings).put("battery", 82).put("networkOnline", name != "offline")
            .put("status", L10n.t("준비되면 탭하여 시작하세요")).put("sttConnected", true)
            .put("ai", JSONObject().put("available", name != "offline").put("status", L10n.t("Rokid AI 연결 대기")))
            .put("serverUrl", "https://192.0.2.10:8443").put("pairingPin", "000000")
            .put("liveSummaryStatus", L10n.t("발화 묶음 대기"))
            .put("view", when (name) { "cue", "empty-cues" -> 1; "notes", "empty-notes", "long-notes" -> 2; else -> 0 })
            .put("liveIndex", if (name == "history") 0 else -1).put("cueIndex", 0).put("prepIndex", 0)
        if (name == "confirm") state.put("confirmation", "start")
        if (name == "stop-confirm") state.put("confirmation", "stop")
        if (name.startsWith("cue-only")) state.put("currentCue", cue.put("shownAt", now))
        return state
    }
}
