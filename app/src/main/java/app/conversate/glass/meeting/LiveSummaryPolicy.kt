package app.conversate.glass.meeting

import org.json.JSONArray
import org.json.JSONObject

/** Batches committed speech only. A cursor advances only after a valid AI result. */
object LiveSummaryPolicy {
    fun navigate(selected: Int, direction: Int, count: Int): Int {
        if (count == 0) return -1
        val index = if (selected < 0) count - 1 else selected
        val next = (index + direction).coerceIn(0, count - 1)
        return if (next == count - 1) -1 else next
    }

    fun batch(rows: JSONArray, cursor: Int, now: Long, lastSpeech: Long, force: Boolean = false): JSONArray? {
        if (cursor >= rows.length()) return null
        val first = rows.getJSONObject(cursor).getLong("at")
        val count = rows.length() - cursor
        if (!force && now - lastSpeech < 3000 && now - first < 25000 && count < 6) return null
        val result = JSONArray()
        var chars = 0
        for (i in cursor until minOf(rows.length(), cursor + 8)) {
            val row = rows.getJSONObject(i)
            if (result.length() > 0 && chars + row.getString("text").length > 2400) break
            result.put(row); chars += row.getString("text").length
        }
        return result
    }

    fun validate(generated: JSONObject, rows: JSONArray): JSONObject? {
        val title = generated.opt("title") as? String ?: return null
        val body = generated.opt("body") as? String ?: return null
        if (title.isBlank() || title.length > 16 || body.isBlank() || body.length > 72) return null
        val terms = JSONArray()
        val text = (0 until rows.length()).joinToString(" ") { rows.getJSONObject(it).getString("text") }
        val candidates = generated.optJSONArray("terms") ?: JSONArray()
        for (i in 0 until minOf(2, candidates.length())) {
            val term = candidates.optJSONObject(i) ?: continue
            val name = term.opt("term") as? String ?: continue
            val definition = term.opt("definition") as? String ?: continue
            val confidence = (term.opt("confidence") as? Number)?.toDouble() ?: 0.0
            val vague = listOf("언급된", "특정 시스템", "문맥에 따라", "확인 필요", "알 수 없", "추정")
                .any { definition.contains(it) }
            if (confidence.isFinite() && confidence >= 0.9 && !vague && name.length in 2..16 &&
                definition.length in 1..40 && text.contains(name, ignoreCase = true)) {
                terms.put(JSONObject().put("term", name).put("definition", definition))
            }
        }
        return JSONObject().put("title", title).put("body", body).put("terms", terms)
            .put("utteranceIds", JSONArray((0 until rows.length()).map { rows.getJSONObject(it).getString("id") }))
            .put("fromAt", rows.getJSONObject(0).getLong("at"))
            .put("at", rows.getJSONObject(rows.length() - 1).getLong("at"))
    }
}
