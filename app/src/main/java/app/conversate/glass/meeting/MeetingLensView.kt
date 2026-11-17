package app.conversate.glass.meeting

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Looper
import android.view.View
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.min

/** Monochrome lens UI. Coordinates use the measured 480×640 frame and y40–560 safe area. */
class MeetingLensView(context: Context) : View(context) {
    private var snapshot = JSONObject()
    private val regular = Typeface.create("sans-serif", Typeface.NORMAL)
    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val mono = Typeface.create("monospace", Typeface.NORMAL)
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.GREEN }
    private val chrome = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.GREEN }

    fun render(state: JSONObject) {
        val copy = JSONObject(state.toString())
        if (Looper.myLooper() != Looper.getMainLooper()) post { snapshot = copy; invalidate() }
        else { snapshot = copy; invalidate() }
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
        when (snapshot.optString("confirmation")) {
            "start" -> drawConfirmation(canvas, false)
            "stop" -> drawConfirmation(canvas, true)
            else -> if (session == null) drawIdle(canvas) else drawSession(canvas, session)
        }
        canvas.restore()
    }

    private fun drawBrand(canvas: Canvas) {
        // A small conversation mark, drawn rather than supplied as a bitmap.
        stroke(205, 1.6f)
        canvas.drawRoundRect(RectF(25f, 49f, 39f, 60f), 3f, 3f, chrome)
        canvas.drawLine(29f, 60f, 27f, 64f, chrome)
        text(canvas, "CONVERSATE", 49f, 64f, 18f, 205, bold = true, available = 250f)
        drawBattery(canvas)
    }

    private fun drawBattery(canvas: Canvas) {
        val value = snapshot.optInt("battery", -1)
        right(canvas, if (value in 0..100) "$value%" else "--%", 424f, 64f, 16f, 150, 64f, monospace = true)
        stroke(150, 1.4f)
        canvas.drawRoundRect(RectF(434f, 52f, 453f, 63f), 2f, 2f, chrome)
        canvas.drawLine(455f, 55f, 455f, 60f, chrome)
        if (value > 0) {
            fill(if (value <= 20) 230 else 155)
            canvas.drawRect(437f, 55f, 437f + 13f * value.coerceIn(0, 100) / 100f, 60f, chrome)
        }
    }

    private fun drawIdle(canvas: Canvas) {
        drawBrand(canvas)
        lines(canvas, L10n.t("대화에 집중하세요."), 24f, 145f, 30f, 432f, 2, bold = true)
        val status = snapshot.optString("status").ifBlank { L10n.t("준비되면 탭하여 시작하세요") }
        text(canvas, status, 24f, 211f, 18f, 145, available = 432f)
        connectionChip(canvas, 24f, L10n.t("음성 인식"), snapshot.optBoolean("sttConnected"))
        connectionChip(canvas, 246f, "Rokid AI", snapshot.optJSONObject("ai")?.optBoolean("available") == true)
        text(canvas, L10n.t("음성 파일은 저장하지 않습니다"), 24f, 292f, 17f, 115, available = 432f)

        panel(canvas, 24f, 308f, 456f, 489f)
        text(canvas, L10n.t("폰·PC 관리 화면"), 40f, 337f, 17f, 160, available = 400f)
        val url = snapshot.optString("serverUrl").ifBlank { L10n.t("Wi-Fi 연결 후 주소 표시") }
        text(canvas, url, 40f, 369f, 21f, 235, available = 400f)
        text(canvas, L10n.t("연결 PIN"), 40f, 402f, 17f, 150, available = 400f)
        val pin = snapshot.optString("pairingPin").ifBlank { "······" }.padEnd(6, '·').take(6)
        pin.forEachIndexed { index, digit ->
            val left = 40f + index * 68f
            panel(canvas, left, 415f, left + 60f, 473f, radius = 8f, alpha = 65)
            centered(canvas, digit.toString(), left + 30f, 454f, 34f, 255, 52f, monospace = true)
        }
        action(canvas, L10n.t("탭하여 회의 시작"), 511f)
    }

    private fun connectionChip(canvas: Canvas, left: Float, label: String, connected: Boolean) {
        panel(canvas, left, 230f, left + 210f, 272f, radius = 11f, alpha = 65)
        if (connected) fill(235) else stroke(135, 1.5f)
        canvas.drawCircle(left + 16f, 251f, 3.5f, chrome)
        text(canvas, label, left + 28f, 257f, 17f, 205, available = 88f)
        right(canvas, if (connected) L10n.t("연결됨") else L10n.t("연결 대기"), left + 196f, 257f, 16f, 140, 80f)
    }

    private fun drawConfirmation(canvas: Canvas, stop: Boolean) {
        drawBrand(canvas)
        panel(canvas, 24f, 140f, 456f, 430f, alpha = 95)
        stroke(230, 2f)
        canvas.drawCircle(240f, 197f, 26f, chrome)
        if (stop) canvas.drawRoundRect(RectF(231f, 188f, 249f, 206f), 3f, 3f, chrome)
        else {
            canvas.drawLine(228f, 197f, 237f, 205f, chrome)
            canvas.drawLine(237f, 205f, 253f, 188f, chrome)
        }
        val title = if (stop) L10n.t("회의를 종료할까요?") else L10n.t("회의를 시작할까요?")
        centered(canvas, title, 240f, 267f, 28f, 255, 400f, bold = true)
        val description = if (stop) L10n.t("확정된 대화와 Cue를 보관합니다.\n요약 상태는 관리 화면에서 확인합니다.")
            else L10n.t("참석자에게 기록을 알렸습니다.\n대화를 Rokid 음성 인식·AI로 보냅니다.")
        val size = fitSize(description, 384f, 4, 22f, 20f)
        lines(canvas, description, 48f, 316f, size, 384f, 4, 180)
        action(canvas, if (stop) L10n.t("탭: 종료") else L10n.t("탭: 확인하고 시작"), 465f)
        centered(canvas, L10n.t("두 번 탭: 돌아가기"), 240f, 549f, 18f, 140, 432f)
    }

    private fun drawSession(canvas: Canvas, session: JSONObject) {
        val paused = session.optString("status") == "paused"
        val online = snapshot.optBoolean("networkOnline", true)
        val mode = when {
            paused -> L10n.t("일시정지")
            session.optString("status") == "stopping" -> L10n.t("종료 중")
            !online -> L10n.t("인터넷 연결 확인")
            else -> L10n.t("진행 중")
        }
        if (paused || !online) stroke(170, 1.5f) else fill(245)
        canvas.drawCircle(29f, 58f, 3.5f, chrome)
        text(canvas, mode, 42f, 64f, 17f, 215, bold = true, available = 226f)
        val elapsed = ((System.currentTimeMillis() - session.optLong("createdAt", System.currentTimeMillis())) / 1000).coerceAtLeast(0)
        right(canvas, String.format(Locale.US, "%02d:%02d", elapsed / 60, elapsed % 60), 338f, 64f, 17f, 145, 65f, monospace = true)
        drawBattery(canvas)
        val view = snapshot.optInt("view", 0).coerceIn(0, 2)
        val primary = if (snapshot.optJSONObject("settings")?.optBoolean("summaries", true) != false) L10n.t("요약") else L10n.t("지금 회의")
        val labels = listOf(primary, L10n.t("AI Cue"), L10n.t("자료"))
        labels.forEachIndexed { index, label ->
            val left = 24f + index * 148f
            if (index == view) {
                fill(15); canvas.drawRoundRect(RectF(left, 85f, left + 136f, 118f), 9f, 9f, chrome)
                stroke(150, 1.3f); canvas.drawRoundRect(RectF(left, 85f, left + 136f, 118f), 9f, 9f, chrome)
            }
            centered(canvas, label, left + 68f, 107f, 18f, if (index == view) 245 else 110, 120f, bold = index == view)
        }
        when (view) {
            0 -> drawConversation(canvas, session, paused)
            1 -> drawCueHistory(canvas, session)
            2 -> drawPrep(canvas, session)
        }
    }

    private fun drawConversation(canvas: Canvas, session: JSONObject, paused: Boolean) {
        val settings = snapshot.optJSONObject("settings") ?: JSONObject()
        val cue = snapshot.optJSONObject("currentCue")
        if (settings.optBoolean("summaries", true)) drawLiveSummary(canvas, session)
        else if (!paused && settings.optBoolean("cues", true) && cue != null) {
            val age = (System.currentTimeMillis() - cue.optLong("shownAt", cue.optLong("at", System.currentTimeMillis()))).coerceAtLeast(0)
            val opacity = when { age < 8000 -> 255; age < 9000 -> ((9000 - age) * 255 / 1000).toInt(); else -> 0 }
            if (opacity > 0) drawCue(canvas, cue, opacity, compact = true)
            if (age in 8000..8999) postInvalidateDelayed(50)
        }
        if (!settings.optBoolean("captions", true)) {
            rule(canvas, 24f, 497f, 456f, 60)
            centered(canvas, if (paused) L10n.t("일시정지 · 탭하여 계속") else if (settings.optBoolean("summaries", true)) L10n.t("스와이프: 이전·다음 요약") else L10n.t("탭하여 Cue와 자료 보기"), 240f, 524f, 18f, 155, 432f)
            centered(canvas, L10n.t("자막 표시 꺼짐"), 240f, 550f, 16f, 100, 432f)
            return
        }
        rule(canvas, 24f, 460f, 456f, 80)
        text(canvas, if (paused) L10n.t("일시정지 · 탭하여 계속") else L10n.t("실시간 자막"), 24f, 482f, 17f, 135, available = 382f)
        activityMark(canvas, 440f, 475f, paused)
        val size = (fontSize() - 3f).coerceIn(20f, 24f)
        val maxLines = if (size > 21f) 2 else 3
        val utterances = session.optJSONArray("utterances") ?: JSONArray()
        val texts = ArrayList<String>()
        for (i in (utterances.length() - 4).coerceAtLeast(0) until utterances.length()) {
            utterances.optJSONObject(i)?.optString("text")?.takeIf { it.isNotBlank() }?.let(texts::add)
        }
        val partial = snapshot.optString("partial")
        if (partial.isNotBlank()) texts.add(partial)
        val tail = wrap(texts.joinToString(" "), size, 432f).takeLast(maxLines)
        if (tail.isEmpty()) {
            text(canvas, if (paused) L10n.t("대화 기록이 보관되었습니다") else L10n.t("대화를 기다리는 중"), 24f, 531f, 21f, 150, available = 432f)
        } else {
            val gap = size * 1.25f
            tail.forEachIndexed { i, row -> text(canvas, row, 24f, 550f - (tail.size - 1 - i) * gap, size, 195, available = 432f) }
            if (partial.isNotBlank() && !paused) {
                style(size, 180)
                val x = (24f + ink.measureText(tail.last()) + 4f).coerceAtMost(453f)
                stroke(150, 1.5f); canvas.drawLine(x, 535f, x, 551f, chrome)
            }
        }
    }

    private fun drawLiveSummary(canvas: Canvas, session: JSONObject) {
        val history = session.optJSONArray("liveSummaries") ?: JSONArray()
        panel(canvas, 24f, 139f, 456f, 348f)
        if (history.length() == 0) {
            text(canvas, L10n.t("요약"), 42f, 166f, 17f, 145, available = 396f)
            stroke(150, 2f)
            canvas.drawLine(218f, 196f, 262f, 196f, chrome)
            canvas.drawLine(218f, 207f, 254f, 207f, chrome)
            canvas.drawLine(218f, 218f, 244f, 218f, chrome)
            val ai = snapshot.optJSONObject("ai")
            val status = if (ai?.optBoolean("available") == true) snapshot.optString("liveSummaryStatus", L10n.t("발화 묶음 대기"))
                else ai?.optString("status") ?: L10n.t("Rokid AI 연결 대기")
            lines(canvas, status, 42f, 265f, 22f, 396f, 2, 210)
            text(canvas, L10n.t("대화가 정리되면 여기에 표시됩니다."), 42f, 326f, 18f, 115, available = 396f)
            return
        }
        val selected = snapshot.optInt("liveIndex", -1)
        val index = if (selected < 0) history.length() - 1 else selected.coerceIn(0, history.length() - 1)
        val card = history.getJSONObject(index)
        text(canvas, L10n.t("요약 {0}/{1}", index + 1, history.length()), 42f, 166f, 17f, 150, available = 250f)
        val label = (if (selected < 0) L10n.t(" · 최신") else L10n.t(" · 이전 내용")).trim().removePrefix("·").trim()
        badge(canvas, label, 438f, 150f, 130f)
        val title = card.optString("title")
        text(canvas, title, 42f, 203f, fitSize(title, 396f, 1, 25f, 21f), 255, bold = true, available = 396f)
        val body = card.optString("body")
        val size = fitSize(body, 396f, 4, fontSize(), 20f)
        lines(canvas, body, 42f, 238f, size, 396f, 4, 235, lineHeight = 1.18f)
        // One narrow accent, not a filled panel, keeps the outside world visible.
        stroke(205, 2.2f); canvas.drawLine(25f, 185f, 25f, 211f, chrome)
        if (snapshot.optJSONObject("settings")?.optBoolean("cues", true) == true) {
            card.optJSONArray("terms")?.optJSONObject(0)?.let { term ->
                panel(canvas, 24f, 360f, 456f, 446f, alpha = 65)
                text(canvas, L10n.t("용어"), 40f, 383f, 16f, 135, available = 60f)
                text(canvas, term.optString("term"), 106f, 383f, 19f, 215, bold = true, available = 334f)
                lines(canvas, term.optString("definition"), 40f, 408f, 20f, 400f, 2, 185, lineHeight = 1.2f)
            }
        }
    }

    private fun drawCueHistory(canvas: Canvas, session: JSONObject) {
        val cues = session.optJSONArray("cues") ?: JSONArray()
        if (cues.length() == 0) {
            empty(canvas, L10n.t("아직 표시된 Cue가 없습니다"), snapshot.optJSONObject("ai")?.optString("status") ?: L10n.t("대화를 기다리는 중"))
            return
        }
        val index = snapshot.optInt("cueIndex", cues.length() - 1).coerceIn(0, cues.length() - 1)
        drawCue(canvas, cues.getJSONObject(index), 255, compact = false)
        pager(canvas, "Cue ${index + 1}/${cues.length()}", index, cues.length())
    }

    private fun drawCue(canvas: Canvas, cue: JSONObject, alpha: Int, compact: Boolean) {
        val bottom = if (compact) 446f else 494f
        panel(canvas, 24f, 139f, 456f, bottom, alpha = (90 * alpha / 255))
        val type = when (cue.optString("type").lowercase(Locale.ROOT)) {
            "answers", "answer" -> L10n.t("답변")
            "bios", "bio" -> L10n.t("인물")
            "concepts", "concept" -> L10n.t("개념")
            else -> L10n.t("제안")
        }
        badge(canvas, type, 438f, 155f, 180f, alpha)
        text(canvas, L10n.t("AI Cue"), 42f, 173f, 17f, alpha * 145 / 255, available = 200f)
        lines(canvas, cue.optString("title").take(16), 42f, 220f, 28f, 396f, 2, alpha, bold = true)
        val body = cue.optString("body").take(36)
        val size = fitSize(body, 396f, if (compact) 3 else 4, 27f, 22f)
        lines(canvas, body, 42f, 299f, size, 396f, if (compact) 3 else 4, alpha * 225 / 255)
        cue.optString("sourceTitle").takeIf { it.isNotBlank() }?.let { source ->
            val y = if (compact) 418f else 449f
            lines(canvas, L10n.t("출처: {0}", source), 42f, y, 18f, 396f, if (compact) 1 else 2, alpha * 135 / 255)
        }
    }

    private fun drawPrep(canvas: Canvas, session: JSONObject) {
        val notes = session.optJSONArray("noteSnapshots") ?: JSONArray()
        if (notes.length() == 0) {
            empty(canvas, L10n.t("선택한 사전 자료가 없습니다"), L10n.t("관리 화면에서 자료를 선택하고\n새 회의를 시작하세요"))
            return
        }
        val index = snapshot.optInt("prepIndex", 0).coerceIn(0, notes.length() - 1)
        val note = notes.getJSONObject(index)
        panel(canvas, 24f, 139f, 456f, 500f)
        text(canvas, L10n.t("사전 자료"), 42f, 167f, 17f, 145, available = 396f)
        lines(canvas, note.optString("title", L10n.t("사전 자료")), 42f, 203f, 26f, 396f, 2, 255, bold = true)
        val body = note.optString("text").trim().replace(Regex("\n{3,}"), "\n\n")
        val size = fitSize(body, 396f, 9, fontSize().coerceAtMost(24.5f), 20f)
        lines(canvas, body, 42f, 267f, size, 396f, 9, 215, lineHeight = 1.12f)
        pager(canvas, L10n.t("자료 {0}/{1}", index + 1, notes.length()), index, notes.length())
    }

    private fun empty(canvas: Canvas, title: String, description: String) {
        panel(canvas, 24f, 139f, 456f, 494f, alpha = 65)
        stroke(125, 1.8f)
        canvas.drawRoundRect(RectF(221f, 205f, 259f, 247f), 7f, 7f, chrome)
        canvas.drawLine(230f, 219f, 250f, 219f, chrome)
        canvas.drawLine(230f, 230f, 244f, 230f, chrome)
        lines(canvas, title, 42f, 302f, 25f, 396f, 2, 225, bold = true)
        lines(canvas, description, 42f, 391f, 20f, 396f, 3, 145)
    }

    private fun pager(canvas: Canvas, label: String, index: Int, count: Int) {
        chevron(canvas, 31f, 521f, false, if (index > 0) 200 else 60)
        chevron(canvas, 449f, 521f, true, if (index < count - 1) 200 else 60)
        centered(canvas, label, 240f, 527f, 18f, 175, 360f)
        centered(canvas, L10n.t("앞뒤로 쓸어 이전·다음"), 240f, 550f, 16f, 100, 400f)
    }

    private fun action(canvas: Canvas, label: String, top: Float) {
        panel(canvas, 24f, top, 456f, top + 44f, radius = 12f, alpha = 175)
        text(canvas, label, 42f, top + 29f, 21f, 245, bold = true, available = 354f)
        chevron(canvas, 430f, top + 22f, true, 210)
    }

    private fun badge(canvas: Canvas, label: String, right: Float, top: Float, maxWidth: Float, alpha: Int = 255) {
        style(16f, alpha)
        val shown = ellipsis(label, 16f, maxWidth - 18f)
        val width = ink.measureText(shown) + 18f
        panel(canvas, right - width, top, right, top + 25f, radius = 7f, alpha = alpha * 90 / 255)
        centered(canvas, shown, right - width / 2, top + 18f, 16f, alpha * 185 / 255, width - 10f)
    }

    private fun chevron(canvas: Canvas, x: Float, y: Float, forward: Boolean, alpha: Int) {
        stroke(alpha, 1.6f)
        val d = if (forward) 1f else -1f
        canvas.drawLine(x - d * 3f, y - 5f, x + d * 2f, y, chrome)
        canvas.drawLine(x + d * 2f, y, x - d * 3f, y + 5f, chrome)
    }

    private fun activityMark(canvas: Canvas, x: Float, y: Float, paused: Boolean) {
        stroke(145, 1.5f)
        if (paused) {
            canvas.drawLine(x - 3f, y - 4f, x - 3f, y + 4f, chrome)
            canvas.drawLine(x + 3f, y - 4f, x + 3f, y + 4f, chrome)
        } else listOf(3f, 7f, 11f, 7f, 3f).forEachIndexed { i, h ->
            canvas.drawLine(x - 8f + i * 4f, y - h / 2, x - 8f + i * 4f, y + h / 2, chrome)
        }
    }

    private fun panel(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float, radius: Float = 14f, alpha: Int = 90) {
        stroke(alpha, 1.2f)
        canvas.drawRoundRect(RectF(left, top, right, bottom), radius, radius, chrome)
    }
    private fun rule(canvas: Canvas, left: Float, y: Float, right: Float, alpha: Int) {
        stroke(alpha, 1f); canvas.drawLine(left, y, right, y, chrome)
    }
    private fun stroke(alpha: Int, width: Float) { chrome.style = Paint.Style.STROKE; chrome.alpha = alpha.coerceIn(0, 255); chrome.strokeWidth = width; chrome.strokeCap = Paint.Cap.ROUND }
    private fun fill(alpha: Int) { chrome.style = Paint.Style.FILL; chrome.alpha = alpha.coerceIn(0, 255) }
    private fun fontSize(): Float = (snapshot.optJSONObject("settings")?.optInt("fontSize", 16) ?: 16).coerceIn(14, 18) * 1.5f
    private fun style(size: Float, alpha: Int, bold: Boolean = false, monospace: Boolean = false) {
        ink.textSize = size; ink.alpha = alpha.coerceIn(0, 255)
        ink.typeface = if (monospace) mono else if (bold) medium else regular
        ink.textAlign = Paint.Align.LEFT
    }
    private fun text(canvas: Canvas, value: String, x: Float, baseline: Float, size: Float, alpha: Int = 255, bold: Boolean = false, available: Float = 464f - x) {
        style(size, alpha, bold); canvas.drawText(ellipsis(value.replace('\n', ' '), size, available), x, baseline, ink)
    }
    private fun right(canvas: Canvas, value: String, x: Float, baseline: Float, size: Float, alpha: Int, available: Float, monospace: Boolean = false) {
        style(size, alpha, monospace = monospace); val shown = ellipsis(value, size, available)
        ink.textAlign = Paint.Align.RIGHT; canvas.drawText(shown, x, baseline, ink); ink.textAlign = Paint.Align.LEFT
    }
    private fun centered(canvas: Canvas, value: String, x: Float, baseline: Float, size: Float, alpha: Int, available: Float, bold: Boolean = false, monospace: Boolean = false) {
        style(size, alpha, bold, monospace); val shown = ellipsis(value, size, available)
        ink.textAlign = Paint.Align.CENTER; canvas.drawText(shown, x, baseline, ink); ink.textAlign = Paint.Align.LEFT
    }
    private fun lines(canvas: Canvas, value: String, x: Float, baseline: Float, size: Float, available: Float, maxLines: Int, alpha: Int = 255, bold: Boolean = false, lineHeight: Float = 1.24f) {
        val rows = wrap(value, size, available, bold)
        rows.take(maxLines).forEachIndexed { index, row ->
            val shown = if (index == maxLines - 1 && rows.size > maxLines) row + "…" else row
            text(canvas, shown, x, baseline + index * size * lineHeight, size, alpha, bold, available)
        }
    }
    private fun fitSize(value: String, available: Float, maxLines: Int, preferred: Float, minimum: Float): Float {
        var size = preferred
        while (size > minimum && wrap(value, size, available).size > maxLines) size -= 0.5f
        return size.coerceAtLeast(minimum)
    }
    private fun ellipsis(value: String, size: Float, available: Float): String {
        ink.textSize = size
        if (ink.measureText(value) <= available) return value
        val count = ink.breakText(value, true, (available - ink.measureText("…")).coerceAtLeast(0f), null)
        return value.take(safeCount(value, count)).trimEnd() + "…"
    }
    private fun wrap(value: String, size: Float, available: Float, bold: Boolean = false): List<String> {
        style(size, 255, bold)
        if (value.isEmpty()) return emptyList()
        val result = ArrayList<String>()
        for (paragraph in value.replace("\r", "").split('\n')) {
            var remaining = paragraph.trim()
            if (remaining.isEmpty()) { result.add(""); continue }
            while (remaining.isNotEmpty()) {
                var count = safeCount(remaining, ink.breakText(remaining, true, available, null))
                if (count <= 0) count = Character.charCount(remaining.codePointAt(0))
                if (count < remaining.length) {
                    val space = remaining.lastIndexOf(' ', count - 1)
                    if (space > count / 2) count = space
                }
                result.add(remaining.take(count).trimEnd()); remaining = remaining.drop(count).trimStart()
                if (result.size >= 200) return result
            }
        }
        return result
    }
    private fun safeCount(value: String, candidate: Int): Int {
        var count = candidate.coerceIn(0, value.length)
        if (count > 0 && count < value.length && Character.isHighSurrogate(value[count - 1]) && Character.isLowSurrogate(value[count])) count--
        return count
    }
}
