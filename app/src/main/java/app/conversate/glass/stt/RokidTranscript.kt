package app.conversate.glass.stt

import org.json.JSONObject

/** role remains raw firmware metadata, not a verified speaker identity. */
data class RokidTranscript(val content: String, val isFinal: Boolean, val number: Int, val audioPath: String, val role: String) {
    companion object {
        fun envelope(body: String): JSONObject? = runCatching {
            val root = JSONObject(body)
            if (root.optString("type") != "cmd_bluetooth_accessibility_data") return null
            root.optJSONObject("data")
        }.getOrNull()
        fun parse(body: String): RokidTranscript? = runCatching {
            val envelope = envelope(body) ?: return null
            if (envelope.optString("key") != "Accessibility_ASR") return null
            val raw = envelope.opt("data") ?: return null
            val data = if (raw is JSONObject) raw else JSONObject(raw.toString())
            val content = data.opt("content") as? String ?: return null
            val final = data.opt("final") as? Boolean ?: return null
            val number = (data.opt("number") as? Number)?.toInt() ?: return null
            if (number < 0) return null
            RokidTranscript(content, final, number, data.optString("audio_path", ""), data.optString("role", ""))
        }.getOrNull()
    }
}
