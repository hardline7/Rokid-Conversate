package app.conversate.glass.meeting

import org.json.JSONArray

/** Analyze new committed speech independently of summary generation. No partial ASR. */
object CueTriggerPolicy {
    fun batch(rows: JSONArray, cursor: Int, now: Long, lastSpeech: Long, lastRequest: Long): JSONArray? {
        if (cursor >= rows.length() || (lastRequest > 0 && now - lastRequest < 8000)) return null
        val firstAt = rows.getJSONObject(cursor).getLong("at")
        if (now - lastSpeech < 1200 && now - firstAt < 6000 && rows.length() - cursor < 3) return null
        val result = JSONArray()
        var chars = 0
        for (i in cursor until minOf(rows.length(), cursor + 6)) {
            val row = rows.getJSONObject(i)
            if (result.length() > 0 && chars + row.getString("text").length > 2400) break
            result.put(row); chars += row.getString("text").length
        }
        return result
    }
    fun freshCursor(rows: JSONArray, cursor: Int, now: Long): Int {
        var next = cursor
        while (next < rows.length() && now - rows.getJSONObject(next).getLong("at") > 30000) next++
        return next
    }
    fun mentions(text: String, term: String): Boolean {
        if (term.isBlank()) return false
        val start = if (term.first().isLetterOrDigit() && term.first().code < 128) "(?<![A-Za-z0-9_])" else ""
        val end = if (term.last().isLetterOrDigit() && term.last().code < 128) "(?![A-Za-z0-9_])" else ""
        return Regex(start + Regex.escape(term) + end, RegexOption.IGNORE_CASE).containsMatchIn(text)
    }
}
