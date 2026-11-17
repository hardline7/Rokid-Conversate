package app.conversate.glass.meeting

import android.content.Context
import android.os.Handler
import android.os.Looper
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

/** Local-only authenticated transport to our own AIUI agent. The agent uses Rokid's
 * documented LanguageModel capability; Rokid account credentials never enter this APK.
 */
class RokidRelayAi(private val context: Context) : NanoWSD("127.0.0.1", 8766), MeetingAi {
    private data class Job(val id: String, val task: String, val system: String, val prompt: String,
        val deadline: Long, val language: String, val callback: (Result<JSONObject>) -> Unit, var claimed: Boolean = false)
    private val main = Handler(Looper.getMainLooper())
    private val jobs = linkedMapOf<String, Job>()
    private val waiter = BridgeWaiter()
    @Volatile private var closed = true
    @Volatile private var epoch = 0L
    private val lifecycleEvents = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "Conversate-lifecycle").apply { isDaemon = true } }
    private val lifecycleSockets = java.util.concurrent.CopyOnWriteArraySet<LifecycleSocket>()
    private var presenceUntil = 0L
    private val received = java.util.concurrent.atomic.AtomicLong()
    private val polls = java.util.concurrent.atomic.AtomicLong()
    private val heartbeats = java.util.concurrent.atomic.AtomicLong()
    private val waiting = java.util.concurrent.atomic.AtomicInteger()
    private val token: String
    private var lastHeartbeat = 0L
    private var hostAvailable = false
    private var hostStatus = L10n.t("Rokid AI 에이전트 연결 대기")
    override val available: Boolean get() = synchronized(jobs) { !closed && hostAvailable && System.currentTimeMillis() < presenceUntil }
    override val status: String get() = synchronized(jobs) {
        if (System.currentTimeMillis() >= presenceUntil) L10n.t("Rokid AI 에이전트 연결 대기") else if (hostAvailable) L10n.t("Rokid AI 연결됨") else L10n.t("Rokid AI 사용 불가")
    }
    init {
        val file = File(context.filesDir, "ai_bridge_token")
        token = if (file.exists()) file.readText().trim() else ByteArray(32).also(SecureRandom()::nextBytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }.also { file.writeText(it) }
        require(token.length == 64)
        // Local lifecycle socket parks indefinitely: no timeout/ping wakeups.
        start(0, true)
    }
    override fun generate(task: String, input: JSONObject, callback: (Result<JSONObject>) -> Unit) {
        if (!available) { callback(Result.failure(IllegalStateException(status))); return }
        val payload = JSONObject(input.toString())
        if (task == "cue") {
            val rows = input.getJSONArray("utterances")
            payload.put("utterances", org.json.JSONArray((maxOf(0, rows.length() - 12) until rows.length()).map { rows.getJSONObject(it) }))
        }
        val outputLanguage = if (task == "localize") L10n.normalize(input.getString("languageTag")) else DeviceLanguage.current(context)
        val prompt = payload.put("outputLanguage", outputLanguage).toString()
        if (prompt.length > 180000) { callback(Result.failure(IllegalStateException(L10n.t("AI 입력 한도를 초과했습니다")))); return }
        val id = UUID.randomUUID().toString()
        val timeout = if (task == "cue") 12000L else 60000L
        synchronized(jobs) {
            if (jobs.size >= 3) { callback(Result.failure(IllegalStateException(L10n.t("Rokid AI 처리 중입니다")))); return }
            jobs[id] = Job(id, task, RokidPrompts.instructions(task, outputLanguage), prompt, System.currentTimeMillis() + timeout, outputLanguage, callback)
        }
        waiter.wake()
        main.postDelayed({ synchronized(jobs) { jobs.remove(id) }?.callback
            ?.invoke(Result.failure(IllegalStateException(L10n.t("Rokid AI 응답 시간 초과")))) }, timeout)
    }
    override fun serveHttp(session: IHTTPSession): Response {
        fun json(code: Int, value: JSONObject): Response = newFixedLengthResponse(Response.Status.lookup(code), "application/json; charset=utf-8", value.toString())
        val supplied = session.headers["x-conversate-bridge"].orEmpty()
        if (!MessageDigest.isEqual(supplied.toByteArray(), token.toByteArray())) return json(401, JSONObject().put("error", "unauthorized"))
        received.incrementAndGet()
        if (session.method == Method.GET && session.uri == "/bridge/status") return json(200, transportStats())
        if (closed) return json(410, JSONObject().put("error", "app_closed"))
        if (session.headers["transfer-encoding"] != null || (session.headers["content-length"]?.toLongOrNull() ?: 0) > 262144) {
            return json(413, JSONObject().put("error", "too_large"))
        }
        return try {
            if (session.method == Method.GET && session.uri == "/bridge/job") {
                val waitMs = (session.parameters["waitMs"]?.firstOrNull()?.toLongOrNull() ?: 0).coerceIn(0, 25000)
                polls.incrementAndGet()
                synchronized(jobs) {
                    lastHeartbeat = System.currentTimeMillis()
                    presenceUntil = maxOf(presenceUntil, lastHeartbeat + waitMs + 15000)
                }
                val job = awaitJob(waitMs)
                synchronized(jobs) { presenceUntil = maxOf(presenceUntil, System.currentTimeMillis() + 15000) }
                return json(200, JSONObject().put("protocol", 2).put("job", if (job == null) JSONObject.NULL else JSONObject()
                    .put("id", job.id).put("task", job.task).put("system", job.system).put("prompt", job.prompt).put("deadline", job.deadline)))
            }
            if (session.method != Method.POST) return json(404, JSONObject().put("error", "not_found"))
            val size = session.headers["content-length"]?.toIntOrNull() ?: return json(411, JSONObject().put("error", "length_required"))
            require(size in 0..262144)
            val bytes = ByteArray(size)
            var offset = 0
            while (offset < size) {
                val count = session.inputStream.read(bytes, offset, size - offset)
                require(count > 0); offset += count
            }
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
            val body = JSONObject(text.ifBlank { "{}" })
            when (session.uri) {
                "/bridge/heartbeat" -> synchronized(jobs) {
                    heartbeats.incrementAndGet()
                    lastHeartbeat = System.currentTimeMillis()
                    presenceUntil = maxOf(presenceUntil, lastHeartbeat + 15000)
                    hostAvailable = body.optBoolean("available")
                    hostStatus = if (hostAvailable) L10n.t("Rokid AI 연결됨") else body.optString("status", L10n.t("Rokid AI 사용 불가")).take(120)
                }
                "/bridge/result" -> {
                    val job = synchronized(jobs) { jobs.remove(body.getString("id")) }
                    if (job != null) {
                        val result = if (job.task != "localize" && job.language != DeviceLanguage.current(context))
                            Result.failure(IllegalStateException("Device language changed; retrying"))
                        else if (body.optBoolean("ok")) runCatching {
                            val text = body.getString("output").trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
                            require(text.length <= 60000); JSONObject(text)
                        } else Result.failure(IllegalStateException(body.optString("error", L10n.t("Rokid AI 요청 실패")).take(200)))
                        main.post { job.callback(result) }
                    }
                }
                else -> return json(404, JSONObject().put("error", "not_found"))
            }
            json(200, JSONObject().put("ok", true))
        } catch (_: Exception) { json(400, JSONObject().put("error", "invalid_payload")) }
    }
    private fun awaitJob(waitMs: Long): Job? {
        val currentEpoch = epoch
        val until = System.nanoTime() + waitMs * 1_000_000
        waiting.incrementAndGet()
        try {
            while (!closed && currentEpoch == epoch) {
                val observed = waiter.version()
                synchronized(jobs) { jobs.values.firstOrNull { !it.claimed }?.also { it.claimed = true } }?.let { return it }
                val left = (until - System.nanoTime()) / 1_000_000
                if (left <= 0) return null
                waiter.awaitAfter(observed, left)
            }
            return null
        } finally { waiting.decrementAndGet() }
    }
    fun transportStats(): JSONObject = JSONObject().put("protocol", 2).put("pollRequests", polls.get())
        .put("heartbeats", heartbeats.get()).put("waitingRequests", waiting.get())
        .put("appActive", !closed).put("lifecycleClients", lifecycleSockets.size).put("receivedRequests", received.get())
    override fun close() {
        closed = true; epoch++; waiter.wake()
        synchronized(jobs) { hostAvailable = false; presenceUntil = 0 }
        notifyLifecycle()
        main.removeCallbacksAndMessages(null)
        val pending = synchronized(jobs) { jobs.values.toList().also { jobs.clear() } }
        pending.forEach { it.callback(Result.failure(IllegalStateException(L10n.t("Rokid AI 연결 종료")))) }
    }
    fun attach() {
        closed = false
        notifyLifecycle()
    }
    private fun notifyLifecycle() {
        val active = !closed
        lifecycleEvents.execute { lifecycleSockets.forEach { it.publish(active) } }
    }
    override fun openWebSocket(handshake: IHTTPSession): WebSocket = LifecycleSocket(handshake)
    private inner class LifecycleSocket(handshake: IHTTPSession) : WebSocket(handshake) {
        @Volatile private var authorized = false
        override fun onOpen() {
            if (handshakeRequest.uri != "/bridge/lifecycle" || lifecycleSockets.size >= 4) {
                close(WebSocketFrame.CloseCode.PolicyViolation, "unavailable", false)
            }
        }
        override fun onMessage(message: WebSocketFrame) {
            if (authorized) return
            val supplied = runCatching { JSONObject(message.textPayload).optString("token") }.getOrDefault("")
            if (!MessageDigest.isEqual(supplied.toByteArray(), token.toByteArray())) {
                close(WebSocketFrame.CloseCode.PolicyViolation, "unauthorized", false); return
            }
            authorized = true
            lifecycleSockets.add(this)
            publish()
        }
        fun publish(active: Boolean = !this@RokidRelayAi.closed) {
            if (!authorized) return
            runCatching { send(JSONObject().put("active", active).toString()) }.onFailure {
                android.util.Log.w("ConversateLifecycle", "send failed: " + it.javaClass.simpleName)
                lifecycleSockets.remove(this)
            }
        }
        override fun onClose(code: WebSocketFrame.CloseCode?, reason: String?, remote: Boolean) { lifecycleSockets.remove(this) }
        override fun onPong(pong: WebSocketFrame) {}
        override fun onException(failure: java.io.IOException) { lifecycleSockets.remove(this) }
    }
    companion object {
        @Volatile private var shared: RokidRelayAi? = null
        @Synchronized fun acquire(context: Context): RokidRelayAi = (shared ?: RokidRelayAi(context.applicationContext).also { shared = it }).also { it.attach() }
    }

}
