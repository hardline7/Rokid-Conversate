package app.conversate.glass.meeting

import org.json.JSONObject

/** The only AI provider allowed by this app is the paired Rokid runtime. */
interface MeetingAi : AutoCloseable {
    val available: Boolean
    val status: String
    fun generate(task: String, input: JSONObject, callback: (Result<JSONObject>) -> Unit)
    fun onMessage(message: String) {}
    override fun close() {}
}

/** Explicit capability failure, never a generated or extractive stand-in for Rokid AI. */
class UnavailableRokidAi(override val status: String) : MeetingAi {
    override val available = false
    override fun generate(task: String, input: JSONObject, callback: (Result<JSONObject>) -> Unit) {
        callback(Result.failure(IllegalStateException(status)))
    }
}
