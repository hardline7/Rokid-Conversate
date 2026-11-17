package app.conversate.glass.meeting

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Looper
import android.view.View
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.min

/**
 * Lens-only rendering. All coordinates are measured in the device's 480 × 640
 * frame. Nothing is drawn below y560; the live transcript leaves the centre clear.
 * The owner handles navigation, consent and session lifecycle.
 */
class MeetingLensView(context: Context) : View(context) {
    private var snapshot = JSONObject()
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GREEN
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }

    fun render(state: JSONObject) {
        val copy = JSONObject(state.toString())
        if (Looper.myLooper() != Looper.getMainLooper()) {
            post { snapshot = copy; invalidate() }
        } else {
            snapshot = copy
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.BLACK)
        if (width <= 0 || height <= 0) return
        val scale = min(width / 480f, height / 640f)
        canvas.save()
        canvas.translate((width - 480f * scale) / 2f, (height - 640f * scale) / 2f)
        canvas.scale(scale, scale)
        canvas.clipRect(16f, 40f, 464f, 560f)
        val session = snapshot.optJSONObject("active")
        val confirmation = snapshot.optString("confirmation")
        when {
            confirmation == "start" -> drawConfirmation(canvas, false)
            confirmation == "stop" -> drawConfirmation(canvas, true)
            session == null -> drawIdle(canvas)
            else -> drawSession(canvas, session)
        }
        canvas.restore()
    }

    private fun drawIdle(canvas: Canvas) {
        line(canvas, "CONVERSATE", 16f, 69f, 28f, bold = true)
        right(canvas, battery(), 464f, 68f, 20f, 175)
        line(canvas, L10n.t("탭하여 회의 시작"), 16f, 121f, 25f)
        val connected = snapshot.optBoolean("sttConnected", false)
        lines(canvas, snapshot.optString("status").ifBlank {
            if (connected) L10n.t("Rokid 음성 인식 연결됨") else L10n.t("Rokid 연결을 기다리는 중")
        }, 16f, 154f, 19f, 448f, 2, 170)
        val ai = snapshot.optJSONObject("ai")
        val aiText = if (ai?.optBoolean("available", false) == true) {
            L10n.t("Rokid AI 연결됨")
        } else {
            ai?.optString("status")?.takeIf { it.isNotBlank() } ?: L10n.t("Rokid AI 연결 확인 중")
        }
        lines(canvas, aiText, 16f, 214f, 18f, 448f, 2, 155)

        line(canvas, L10n.t("폰·PC 관리 화면"), 16f, 301f, 20f, 185)
        val url = snapshot.optString("serverUrl")
        lines(canvas, url.ifBlank { L10n.t("Wi-Fi 연결 후 주소 표시") }, 16f, 336f, 22f, 448f, 2)
        val pin = snapshot.optString("pairingPin")
        line(canvas, L10n.t("연결 PIN"), 16f, 415f, 18f, 165)
        line(canvas, pin.ifBlank { "······" }, 16f, 463f, 41f, bold = true)
        lines(canvas, L10n.t("자료 올리기 · 회의 기록 · 설정"), 16f, 517f, 19f, 448f, 1, 160)
        line(canvas, L10n.t("음성 파일은 저장하지 않습니다"), 16f, 550f, 17f, 135)
    }

    private fun drawConfirmation(canvas: Canvas, stop: Boolean) {
        line(canvas, "CONVERSATE", 16f, 69f, 24f, bold = true)
        line(canvas, if (stop) L10n.t("회의를 종료할까요?") else L10n.t("회의를 시작할까요?"), 16f, 182f, 29f, bold = true)
        val description = if (stop) {
            L10n.t("확정된 대화와 Cue를 보관합니다.\n요약 상태는 관리 화면에서 확인합니다.")
        } else {
            L10n.t("참석자에게 기록을 알렸습니다.\n대화를 Rokid 음성 인식·AI로 보냅니다.")
        }
        lines(canvas, description, 16f, 232f, 23f, 448f, 4, 195)
        line(canvas, if (stop) L10n.t("탭: 종료") else L10n.t("탭: 확인하고 시작"), 16f, 461f, 26f, bold = true)
        line(canvas, L10n.t("두 번 탭: 돌아가기"), 16f, 510f, 22f, 160)
    }

    private fun drawSession(canvas: Canvas, session: JSONObject) {
        val paused = session.optString("status") == "paused"
        val elapsed = ((System.currentTimeMillis() - session.optLong("createdAt", System.currentTimeMillis())) / 1000).coerceAtLeast(0)
        val clock = String.format(Locale.US, "%02d:%02d", elapsed / 60, elapsed % 60)
        val mode = if (paused) "Ⅱ PAUSED" else if (session.optString("status") == "stopping") L10n.t("종료 중") else "● LIVE"
        line(canvas, "$mode  $clock", 16f, 65f, 22f, bold = true)
        right(canvas, battery(), 464f, 65f, 20f, 170)
        val view = snapshot.optInt("view", 0).coerceIn(0, 2)
        val labels = listOf(L10n.t("요약"), "Cue", L10n.t("자료"))
        val navigation = labels.mapIndexed { index, label -> if (index == view) "[$label]" else label }.joinToString("  ·  ")
        line(canvas, navigation, 16f, 98f, 18f, 160)
        if (!snapshot.optBoolean("networkOnline", true) && !paused) line(canvas, L10n.t("인터넷 연결 확인"), 16f, 124f, 16f, 170)
        if (paused) {
            lines(canvas, L10n.t("일시정지 · 탭하여 계속"), 16f, 141f, 23f, 448f, 2)
        }
        when (view) {
            0 -> drawTranscript(canvas, session, paused)
            1 -> drawCueHistory(canvas, session)
            2 -> drawPrep(canvas, session)
        }
    }

    private fun drawTranscript(canvas: Canvas, session: JSONObject, paused: Boolean) {
        val settings = snapshot.optJSONObject("settings") ?: JSONObject()
        val currentCue = snapshot.optJSONObject("currentCue")
        if (settings.optBoolean("summaries", true)) {
            drawLiveSummary(canvas, session, paused)
        } else if (!paused && settings.optBoolean("cues", true) && currentCue != null) {
            val shownAt = currentCue.optLong("shownAt", currentCue.optLong("at", System.currentTimeMillis()))
            val age = (System.currentTimeMillis() - shownAt).coerceAtLeast(0)
            val opacity = when {
                age < 8000 -> 255
                age < 9000 -> ((9000 - age) * 255 / 1000).toInt()
                else -> 0
            }
            if (opacity > 0) drawCue(canvas, currentCue, if (paused) 175f else 143f, opacity, false)
            // Only the final second needs animation; the owner's regular state tick
            // advances the clock and moves from the hold interval into the fade.
            if (age in 8000..8999) postInvalidateDelayed(50)
        }
        if (!settings.optBoolean("captions", true)) {
            line(canvas, L10n.t("자막 표시 꺼짐"), 16f, 532f, 21f, 150)
            return
        }
        val size = fontSize()
        val utterances = session.optJSONArray("utterances") ?: JSONArray()
        val texts = ArrayList<String>()
        for (i in (utterances.length() - 4).coerceAtLeast(0) until utterances.length()) {
            utterances.optJSONObject(i)?.optString("text")?.takeIf { it.isNotBlank() }?.let(texts::add)
        }
        snapshot.optString("partial").takeIf { it.isNotBlank() }?.let { texts.add(it + "_") }
        val all = wrap(texts.joinToString(" "), size, 448f)
        val tail = all.takeLast(3)
        if (tail.isEmpty()) {
            lines(canvas, if (paused) L10n.t("대화 기록이 보관되었습니다") else L10n.t("대화를 기다리는 중"), 16f, 530f, size, 448f, 1, 165)
        } else {
            val height = size * 1.28f
            val firstBaseline = 550f - (tail.size - 1) * height
            tail.forEachIndexed { index, value -> line(canvas, value, 16f, firstBaseline + index * height, size) }
        }
    }

    private fun drawLiveSummary(canvas: Canvas, session: JSONObject, paused: Boolean) {
        val history = session.optJSONArray("liveSummaries") ?: JSONArray()
        val top = if (paused) 194f else 145f
        if (history.length() == 0) {
            val ai = snapshot.optJSONObject("ai")
            val label = if (ai?.optBoolean("available") == true) snapshot.optString("liveSummaryStatus", L10n.t("발화 묶음 대기"))
                else ai?.optString("status") ?: L10n.t("Rokid AI 연결 대기")
            lines(canvas, label, 16f, top, 23f, 448f, 2, 180)
            return
        }
        val selected = snapshot.optInt("liveIndex", -1)
        val index = if (selected < 0) history.length() - 1 else selected.coerceIn(0, history.length() - 1)
        val card = history.getJSONObject(index)
        line(canvas, L10n.t("요약 {0}/{1}" , index + 1, history.length()) + if (selected < 0) L10n.t(" · 최신") else L10n.t(" · 이전 내용"), 16f, top, 18f, 165)
        lines(canvas, card.optString("title"), 16f, top + 33f, 25f, 448f, 1, bold = true)
        lines(canvas, card.optString("body"), 16f, top + 69f, 23f, 448f, 4)
        if (snapshot.optJSONObject("settings")?.optBoolean("cues", true) == true) {
            card.optJSONArray("terms")?.optJSONObject(0)?.let {
                lines(canvas, L10n.t("[용어] {0}: {1}" , it.optString("term"), it.optString("definition")),
                    16f, top + 198f, 20f, 448f, 3, 190)
            }
        }
        if (!paused) line(canvas, L10n.t("스와이프: 이전·다음 요약"), 16f, 453f, 17f, 140)
    }

    private fun drawCueHistory(canvas: Canvas, session: JSONObject) {
        val cues = session.optJSONArray("cues") ?: JSONArray()
        if (cues.length() == 0) {
            lines(canvas, L10n.t("아직 표시된 Cue가 없습니다"), 16f, 203f, 25f, 448f, 2, 190)
            val ai = snapshot.optJSONObject("ai")
            if (ai?.optBoolean("available", false) != true) {
                lines(canvas, ai?.optString("status") ?: L10n.t("Rokid AI 연결 확인 중"), 16f, 282f, 20f, 448f, 3, 150)
            }
            return
        }
        val index = snapshot.optInt("cueIndex", cues.length() - 1).coerceIn(0, cues.length() - 1)
        cues.optJSONObject(index)?.let { drawCue(canvas, it, 179f, 255, true) }
        line(canvas, "Cue ${index + 1}/${cues.length()}", 16f, 507f, 20f, 170)
        line(canvas, L10n.t("앞뒤로 쓸어 이전·다음"), 16f, 549f, 19f, 140)
    }

    private fun drawPrep(canvas: Canvas, session: JSONObject) {
        val notes = session.optJSONArray("noteSnapshots") ?: JSONArray()
        if (notes.length() == 0) {
            lines(canvas, L10n.t("선택한 사전 자료가 없습니다"), 16f, 205f, 25f, 448f, 2, 190)
            lines(canvas, L10n.t("관리 화면에서 자료를 선택하고\n새 회의를 시작하세요"), 16f, 284f, 22f, 448f, 3, 155)
            return
        }
        val index = snapshot.optInt("prepIndex", 0).coerceIn(0, notes.length() - 1)
        val note = notes.optJSONObject(index) ?: return
        val title = note.optString("title", L10n.t("사전 자료"))
        lines(canvas, title, 16f, 159f, 27f, 448f, 2, bold = true)
        val paragraphs = note.optString("text").trim().replace(Regex("\n{3,}"), "\n\n")
        val bodySize = fontSize()
        val wrapped = wrap(paragraphs, bodySize, 448f)
        val maxLines = 8
        wrapped.take(maxLines).forEachIndexed { lineIndex, text ->
            val shown = if (lineIndex == maxLines - 1 && wrapped.size > maxLines) ellipsis(text + "…", bodySize, 448f) else text
            line(canvas, shown, 16f, 234f + lineIndex * bodySize * 1.23f, bodySize, 210)
        }
        line(canvas, L10n.t("자료 {0}/{1} · 앞뒤로 쓸어 넘기기" , index + 1, notes.length()), 16f, 550f, 18f, 150)
    }

    private fun drawCue(canvas: Canvas, cue: JSONObject, top: Float, alpha: Int, large: Boolean) {
        val type = when (cue.optString("type").lowercase(Locale.ROOT)) {
            "answers", "answer" -> L10n.t("답변")
            "bios", "bio" -> L10n.t("인물")
            "concepts", "concept" -> L10n.t("개념")
            else -> L10n.t("제안")
        }
        val title = cue.optString("title").take(16)
        lines(canvas, "[$type] $title", 16f, top, if (large) 28f else 24f, 448f, 2, alpha, true)
        val bodySize = if (large) 29f else 24f
        val bodyStart = top + if (large) 79f else 66f
        lines(canvas, cue.optString("body").take(36), 16f, bodyStart, bodySize, 448f, if (large) 4 else 3, alpha)
        cue.optString("sourceTitle").takeIf { it.isNotBlank() }?.let {
            lines(canvas, "($it)", 16f, bodyStart + if (large) 146f else 103f, 18f, 448f, 1, (alpha * 0.65f).toInt())
        }
    }

    private fun fontSize(): Float = (snapshot.optJSONObject("settings")?.optInt("fontSize", 16) ?: 16).coerceIn(14, 18) * 1.5f
    private fun battery(): String = snapshot.optInt("battery", -1).let { if (it in 0..100) "$it%" else "--%" }

    private fun style(size: Float, alpha: Int, bold: Boolean = false) {
        ink.textSize = size
        ink.alpha = alpha.coerceIn(0, 255)
        ink.typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
        ink.textAlign = Paint.Align.LEFT
    }

    private fun line(canvas: Canvas, text: String, x: Float, baseline: Float, size: Float, alpha: Int = 255, bold: Boolean = false) {
        style(size, alpha, bold)
        canvas.drawText(ellipsis(text.replace('\n', ' '), size, 464f - x), x, baseline, ink)
    }

    private fun right(canvas: Canvas, text: String, x: Float, baseline: Float, size: Float, alpha: Int) {
        style(size, alpha)
        ink.textAlign = Paint.Align.RIGHT
        canvas.drawText(text, x, baseline, ink)
        ink.textAlign = Paint.Align.LEFT
    }

    private fun lines(canvas: Canvas, text: String, x: Float, firstBaseline: Float, size: Float, available: Float, maxLines: Int, alpha: Int = 255, bold: Boolean = false) {
        style(size, alpha, bold)
        val rows = wrap(text, size, available, bold)
        rows.take(maxLines).forEachIndexed { index, row ->
            val value = if (index == maxLines - 1 && rows.size > maxLines) ellipsis(row + "…", size, available) else row
            style(size, alpha, bold)
            canvas.drawText(value, x, firstBaseline + index * size * 1.28f, ink)
        }
    }

    private fun ellipsis(text: String, size: Float, available: Float): String {
        ink.textSize = size
        if (ink.measureText(text) <= available) return text
        val tail = "…"
        val count = ink.breakText(text, true, (available - ink.measureText(tail)).coerceAtLeast(0f), null)
        return text.take(safeCount(text, count)).trimEnd() + tail
    }

    private fun wrap(text: String, size: Float, available: Float, bold: Boolean = false): List<String> {
        style(size, 255, bold)
        if (text.isEmpty()) return emptyList()
        val output = ArrayList<String>()
        for (paragraph in text.replace("\r", "").split('\n')) {
            var remaining = paragraph.trim()
            if (remaining.isEmpty()) { output.add(""); continue }
            while (remaining.isNotEmpty()) {
                var count = safeCount(remaining, ink.breakText(remaining, true, available, null))
                if (count <= 0) count = Character.charCount(remaining.codePointAt(0))
                if (count < remaining.length) {
                    val space = remaining.lastIndexOf(' ', count - 1)
                    if (space > count / 2) count = space
                }
                output.add(remaining.take(count).trimEnd())
                remaining = remaining.drop(count).trimStart()
                if (output.size >= 200) return output
            }
        }
        return output
    }

    private fun safeCount(text: String, candidate: Int): Int {
        var count = candidate.coerceIn(0, text.length)
        if (count > 0 && count < text.length && Character.isHighSurrogate(text[count - 1]) && Character.isLowSurrogate(text[count])) count--
        return count
    }
}
