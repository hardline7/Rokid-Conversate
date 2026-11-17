package app.conversate.glass.meeting

import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.UUID

/** All model output remains untrusted. Grounded cues require an exact quote. */
class CuePolicy {
    private val priority = mapOf("Answers" to 0, "Bios" to 1, "Concepts" to 2, "Suggestions" to 3)
    private val queue = mutableListOf<JSONObject>()
    private val shown = ArrayDeque<Long>()
    private val concepts = mutableSetOf<String>()
    private var nextAt = 0L
    var current: JSONObject? = null
        private set

    fun offer(raw: JSONObject, notes: JSONArray, now: Long? = null, utterances: JSONArray = JSONArray()): Boolean {
        val type = raw.optString("type")
        val title = raw.optString("title").trim()
        val body = raw.optString("body").trim()
        if (type !in priority || title.isEmpty() || title.length > 16 || body.isEmpty() || body.length > 72) return false
        val confidence = (raw.opt("confidence") as? Number)?.toDouble() ?: return false
        if (!confidence.isFinite() || confidence !in 0.8..1.0) return false
        if (type == "Concepts" && (title.lowercase() in concepts || queue.any {
            it.optString("type") == "Concepts" && it.optString("title").equals(title, ignoreCase = true)
        })) return false
        val cue = JSONObject(raw.toString())
        if (type == "Answers" || type == "Bios") {
            val note = (0 until notes.length()).map { notes.getJSONObject(it) }
                .find { it.optString("id") == raw.optString("sourceId") } ?: return false
            val quote = raw.optString("quote")
            if (quote.isBlank() || !note.getString("text").contains(quote)) return false
            val numerals = Regex("\\d+(?:[.,]\\d+)*(?:%)?")
            val supported = numerals.findAll(quote).map { it.value }.toSet()
            if (numerals.findAll(body).any { it.value !in supported }) return false
            cue.put("sourceTitle", note.getString("title"))
        } else if (type == "Concepts") {
            if (confidence < 0.9) return false
            val row = (0 until utterances.length()).map { utterances.getJSONObject(it) }
                .find { it.optString("id") == raw.optString("utteranceId") } ?: return false
            val term = raw.optString("triggerTerm")
            val quote = raw.optString("triggerQuote")
            if (!title.equals(term, ignoreCase = true) || term.length !in 2..16 || quote.isBlank() || quote.length > 300 ||
                !row.getString("text").contains(quote) || !CueTriggerPolicy.mentions(quote, term)) return false
            if (Regex("언급된|특정 시스템|확인 필요|알 수 없|추정|might refer|not sure|unknown|mentioned in", RegexOption.IGNORE_CASE).containsMatchIn(body)) return false
            cue.put("sourceId", "").put("sourceTitle", "").put("quote", "").put("sourceKind", "modelKnowledge")
        } else { cue.put("sourceId", "").put("sourceTitle", "").put("quote", "") }
        if (queue.size >= 8 || queue.any { it.optString("title") == title && it.optString("body") == body }) return false
        cue.put("id", UUID.randomUUID().toString()).put("title", title).put("body", body)
        cue.remove("queuedAt")
        if (now != null) cue.put("queuedAt", now)
        queue.add(cue); queue.sortBy { priority[it.getString("type")] }
        return true
    }
    fun tick(now: Long): JSONObject? {
        queue.removeAll { it.has("queuedAt") && now - it.getLong("queuedAt") > 15000 }
        if (current != null && now - current!!.getLong("shownAt") >= 9000) current = null
        while (shown.isNotEmpty() && now - shown.first >= 60000) shown.removeFirst()
        if (current != null || now < nextAt || shown.size >= 4 || queue.isEmpty()) return null
        val cue = queue.removeAt(0)
        if (cue.getString("type") == "Concepts" && !concepts.add(cue.getString("title").lowercase())) return tick(now)
        cue.put("at", now).put("shownAt", now)
        current = cue; shown.add(now); nextAt = now + 13000
        return JSONObject(cue.toString())
    }
    fun seenConcepts(): JSONArray = JSONArray((concepts + queue.filter { it.optString("type") == "Concepts" }.map { it.optString("title").lowercase() }).toList())
    fun dismiss() { current = null }
    fun suspend() { queue.clear(); current = null }
    fun clear() { queue.clear(); shown.clear(); concepts.clear(); current = null; nextAt = 0 }
}
