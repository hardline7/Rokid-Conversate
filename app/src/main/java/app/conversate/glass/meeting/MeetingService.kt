package app.conversate.glass.meeting

import android.app.*
import android.content.*
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.*
import app.conversate.glass.R
import app.conversate.glass.stt.RokidSttClient
import app.conversate.glass.stt.RokidTranscript
import org.json.JSONArray
import org.json.JSONObject
import java.net.NetworkInterface
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Owns recognition and encrypted records independently of lens rendering. */
class MeetingService : Service(), RokidSttClient.Listener, CompanionServer.Backend {
    private val main = Handler(Looper.getMainLooper())
    lateinit var store: MeetingStore; private set
    private lateinit var stt: RokidSttClient
    private lateinit var web: CompanionServer
    private lateinit var importer: PrepImporter
    private var ai: MeetingAi = UnavailableRokidAi(L10n.t("Rokid AI 연결 확인 중"))
    private var session: JSONObject? = null
    private var partial = ""
    private var status = L10n.t("Rokid 연결 중")
    private var confirmation = ""
    private var viewIndex = 0
    private var prepIndex = 0
    private var cueIndex = -1
    private var segment = 0
    private var lastSpeech = 0L
    private var stoppingUntil = 0L
    private var lastSummary = 0L
    private var cueBusy = false
    private var cueCursor = 0
    private var cueLastRequest = 0L
    private var cueRetryAt = 0L
    private var cueEpoch = 0
    private var summaryBusy = false
    private val pendingLive = java.util.ArrayDeque<String>()
    private var localeBusy = false
    private var localeRetryAt = 0L
    private var liveBusy = false
    private var liveRetryAt = 0L
    private var liveIndex = -1 // -1 follows newest; reading history stays pinned
    private var liveStatus = L10n.t("발화 묶음 대기")
    private val pendingRecaps = java.util.ArrayDeque<String>()
    private var captureOwned = false
    private var recoveryStopRequested = false
    private var drainAction: String? = null
    private var drainReason = ""
    private val drainTimeout = Runnable { finishDrain(false) }
    private var aiTest = JSONObject().put("status", "not_run")
    private var closing = false
    private var serviceTicks = 0L
    private var nextTickMs = 1000L
    var lensVisible = false
        set(value) {
            if (field == value) return
            field = value
            if (value) kickTick()
        }
    private fun kickTick() { main.removeCallbacks(tick); main.post(tick) }
    private var wake: PowerManager.WakeLock? = null
    private val cues = CuePolicy()
    private val recovery by lazy { getSharedPreferences("native_recovery", MODE_PRIVATE) }
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { if (intent.action == Intent.ACTION_SCREEN_OFF) pause(L10n.t("화면 꺼짐 · 일시정지")) }
    }
    private val tick = object : Runnable {
        override fun run() {
            if (closing) return
            serviceTicks++
            val now = System.currentTimeMillis()
            if (L10n.switchLanguage(DeviceLanguage.current(this@MeetingService))) {
                status = if (session?.optString("status") == "running") L10n.t("● 인식 중") else L10n.t("Rokid 연결 중")
                liveStatus = L10n.t("발화 묶음 대기")
            }
            requestCue()
            dispatchLiveSummary()
            dispatchRecaps()
            translateInterface()
            val current = session
            if (current?.optString("status") == "running") {
                if (now - lastSpeech >= 10 * 60 * 1000) stop(L10n.t("10분간 발화 없음 · 자동 종료"))
                if (store.settings().optBoolean("cues")) cues.tick(now)?.let { cue ->
                    current.getJSONArray("cues").put(cue); cueIndex = current.getJSONArray("cues").length() - 1
                    store.saveSession(current)
                }
                if (now - lastSummary >= 90000) { lastSummary = now; summarize(false) }
            }
            nextTickMs = 1000L // This loop exists only while the meeting screen is open.
            main.removeCallbacks(this)
            main.postDelayed(this, nextTickMs)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        L10n.initialize(JSONObject(assets.open("companion/messages.json").bufferedReader().use { it.readText() }),
            java.io.File(filesDir, "ui_locales"), DeviceLanguage.current(this))
        status = L10n.t("Rokid 연결 중"); liveStatus = L10n.t("발화 묶음 대기")
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("meeting", L10n.t("회의 및 관리 화면"), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MeetingActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        startForeground(1, Notification.Builder(this, "meeting").setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Conversate").setContentText(L10n.t("회의 준비 · 안경에서 관리 화면 주소 확인"))
            .setContentIntent(open).setOngoing(true).build())
        try {
            store = MeetingStore(this)
            importer = PrepImporter(this)
            web = CompanionServer(this, this)
            web.startSecure()
            stt = RokidSttClient(this, this)
            stt.connect()
            ai = RokidRelayAi.acquire(this)
            registerReceiver(screenReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
            main.post(tick)
        } catch (e: Exception) {
            status = L10n.t("초기화 실패: {0}" , e.message)
            android.util.Log.e("Conversate", "Service initialization failed", e)
        }
    }

    fun showLens() {
        if (!closing) lensVisible = true
    }

    fun closeFromLens() {
        if (closing) return
        closing = true
        main.removeCallbacks(tick)
        if (session?.optString("status") == "running") pause(L10n.t("안경 화면을 벗어나 일시정지"))
        if (drainAction == null) stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_NOT_STICKY
    override fun onStatus(value: String) {
        status = value
        if (::stt.isInitialized && stt.isConnected && recovery.getBoolean("ownsStt", false) && !captureOwned &&
            drainAction == null && !recoveryStopRequested) {
            recoveryStopRequested = true
            runCatching { stt.requestStt(false) }.onFailure { recoveryStopRequested = false }
            status = L10n.t("중단된 인식 종료 요청 · 기록 복구됨")
        }
    }
    override fun onMessage(message: RokidSttClient.Message) {
        val body = message.body ?: return
        ai.onMessage(body)
        val envelope = RokidTranscript.envelope(body) ?: return
        if (envelope.optString("key") == "Accessibility_end") {
            recovery.edit().putBoolean("ownsStt", false).apply(); recoveryStopRequested = false
            if (drainAction != null) finishDrain(true)
            else if (captureOwned) pause(L10n.t("Rokid 인식 종료 · 일시정지"), sendStop = false)
            return
        }
        val current = session ?: return
        if (!(captureOwned && current.optString("status") == "running") && drainAction == null) return
        val event = RokidTranscript.parse(body) ?: return
        if (event.content.isBlank()) return
        lastSpeech = System.currentTimeMillis()
        if (drainAction == null) status = L10n.t("● 인식 중")
        if (!event.isFinal) { if (drainAction == null) partial = event.content; return }
        try {
            val row = JSONObject().put("id", "$segment:${event.audioPath}:${event.number}")
                .put("text", event.content).put("at", lastSpeech).put("final", true)
                .put("number", event.number).put("audioPath", event.audioPath).put("role", event.role)
            if (store.appendUtterance(current.getString("id"), row)) {
                session = store.session(current.getString("id"))
                partial = ""
            }
        } catch (e: Exception) { pause(L10n.t("저장 실패 · 인식 중지: {0}" , e.message)) }
    }

    fun lensState(): JSONObject = snapshot(true)
    private fun snapshot(includePin: Boolean): JSONObject {
        if (!::store.isInitialized) return JSONObject().put("status", L10n.label(status))
        val current = session?.let { source ->
            JSONObject(source.toString()).apply {
                val all = source.getJSONArray("utterances")
                put("utterances", JSONArray((maxOf(0, all.length() - 15) until all.length()).map { all.getJSONObject(it) }))
                put("noteSnapshots", prepPages(source.getJSONArray("noteSnapshots")))
            }
        }
        val result = JSONObject().put("active", current ?: JSONObject.NULL).put("partial", partial)
            .put("status", L10n.label(status)).put("sttConnected", ::stt.isInitialized && stt.isConnected)
            .put("networkOnline", networkOnline())
            .put("battery", getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
            .put("languageTag", L10n.tag).put("uiTranslationReady", L10n.isReady())
            .put("settings", store.settings()).put("serverUrl", serverUrl())
            .put("ai", JSONObject().put("available", ai.available).put("status", ai.status).put("test", aiTest)
                .put("transport", (ai as? RokidRelayAi)?.transportStats() ?: JSONObject.NULL))
            .put("power", JSONObject().put("serviceTicks", serviceTicks).put("nextTickMs", nextTickMs).put("lensVisible", lensVisible))
            .put("currentCue", cues.current ?: JSONObject.NULL).put("view", viewIndex)
            .put("liveIndex", liveIndex).put("liveSummaryStatus", L10n.label(liveStatus))
            .put("prepIndex", prepIndex).put("cueIndex", cueIndex).put("confirmation", confirmation)
        if (includePin && ::web.isInitialized) result.put("pairingPin", web.pairingPin)
        return result
    }
    private fun serverUrl(): String {
        val address = runCatching { NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }.flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is java.net.Inet4Address && !it.isLoopbackAddress }?.hostAddress }.getOrNull()
        return if (address != null) "https://$address:8443" else ""
    }
    private fun networkOnline(): Boolean {
        val connectivity = getSystemService(ConnectivityManager::class.java)
        return connectivity.getNetworkCapabilities(connectivity.activeNetwork)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    }

    fun tap() {
        if (!::store.isInitialized) return
        when (confirmation) {
            "start" -> { confirmation = ""; runCatching { start(true) }.onFailure { status = it.message ?: L10n.t("시작 실패") } }
            "stop" -> { confirmation = ""; stop(L10n.t("회의 종료")) }
            else -> when {
                session == null -> confirmation = "start"
                session?.optString("status") == "paused" -> runCatching { resume() }.onFailure { status = it.message ?: L10n.t("재개 실패") }
                else -> viewIndex = (viewIndex + 1) % 3
            }
        }
    }
    fun back(): Boolean {
        when {
            confirmation.isNotEmpty() -> confirmation = ""
            cues.current != null -> cues.dismiss()
            session != null -> confirmation = "stop"
            else -> return false
        }
        return true
    }
    fun swipe(direction: Int) {
        val current = session ?: return
        when (viewIndex) {
            0 -> {
                val count = current.optJSONArray("liveSummaries")?.length() ?: 0
                liveIndex = LiveSummaryPolicy.navigate(liveIndex, direction, count)
            }
            1 -> cueIndex = (cueIndex + direction).coerceIn(0, maxOf(0, current.getJSONArray("cues").length() - 1))
            2 -> prepIndex = (prepIndex + direction).coerceIn(0, maxOf(0, prepPages(current.getJSONArray("noteSnapshots")).length() - 1))
        }
    }
    private fun requireReady() {
        check(::stt.isInitialized && stt.isConnected) { L10n.t("Rokid 음성 인식 연결을 기다려 주세요") }
        check(System.currentTimeMillis() >= stoppingUntil) { L10n.t("인식 종료 처리 중입니다. 잠시 후 시작해 주세요") }
        check(getSystemService(PowerManager::class.java).isInteractive) { L10n.t("안경을 착용하고 화면을 켜 주세요") }
        check(lensVisible) { L10n.t("안경에서 Conversate 화면을 열어 주세요") }
        val settings = store.settings()
        check(settings.getBoolean("captions") || settings.getBoolean("cues") || settings.getBoolean("summaries")) { L10n.t("자막·Cue·요약 중 하나를 켜 주세요") }
    }
    private fun prepPages(notes: JSONArray): JSONArray {
        val pages = JSONArray()
        for (i in 0 until notes.length()) {
            val note = notes.getJSONObject(i)
            val chunks = note.getString("text").replace(Regex("\\s+"), " ").chunked(140)
            chunks.forEachIndexed { index, text -> pages.put(JSONObject().put("id", note.getString("id"))
                .put("title", note.getString("title") + if (chunks.size > 1) " ${index + 1}/${chunks.size}" else "")
                .put("text", text)) }
        }
        return pages
    }
    private fun start(consent: Boolean) {
        require(consent) { L10n.t("참석자에게 알렸는지 확인해 주세요") }
        check(session == null) { L10n.t("진행 중인 회의가 있습니다") }
        requireReady()
        session = store.createSession()
        liveIndex = -1; liveStatus = L10n.t("발화 묶음 대기"); liveRetryAt = 0L
        cueCursor = 0; cueLastRequest = 0L; cueRetryAt = 0L
        segment = 1; partial = ""; viewIndex = 0; prepIndex = 0; cueIndex = -1; cues.clear()
        lastSpeech = System.currentTimeMillis(); lastSummary = lastSpeech
        kickTick()
        try { startNative() } catch (e: Exception) {
            session!!.put("status", "interrupted").put("endedAt", System.currentTimeMillis())
            store.saveSession(session!!); session = null; throw e
        }
    }
    private fun startNative() {
        recovery.edit().putBoolean("ownsStt", true).commit()
        stt.requestStt(true); captureOwned = true
        wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "conversate:meeting")
            .apply { acquire(4 * 60 * 60 * 1000L) }
        status = L10n.t("인식 시작 요청 · 결과 대기")
    }
    fun pause(reason: String = L10n.t("회의 일시정지"), sendStop: Boolean = true) {
        val current = session ?: return
        if (current.optString("status") != "running") return
        if (sendStop) beginDrain("pause", reason)
        else {
            captureOwned = false; releaseWake(); cueEpoch++; cues.suspend()
            current.put("status", "paused"); store.saveSession(current)
            partial = ""; status = reason
        }
    }
    private fun resume() {
        val current = session ?: error(L10n.t("진행 중인 회의가 없습니다"))
        check(current.optString("status") == "paused") { L10n.t("이미 진행 중입니다") }
        requireReady(); segment++; cueEpoch++; startNative(); kickTick()
        current.put("status", "running"); store.saveSession(current); lastSpeech = System.currentTimeMillis()
    }
    private fun stopNative(): Boolean {
        var delivered = true
        if (captureOwned) {
            delivered = runCatching { stt.requestStt(false) }.isSuccess
            if (!delivered) status = L10n.t("인식 종료 실패 · 폰에서 자막을 종료해 주세요")
            captureOwned = false; stoppingUntil = System.currentTimeMillis() + 3000
        }
        releaseWake()
        return delivered
    }
    private fun releaseWake() { wake?.let { if (it.isHeld) it.release() }; wake = null }
    private fun stop(reason: String) {
        if (session == null) return
        if (drainAction != null) { drainAction = "end"; drainReason = reason; return }
        beginDrain("end", reason)
    }
    private fun beginDrain(action: String, reason: String) {
        val current = session ?: return
        drainAction = action; drainReason = reason; cueEpoch++; cues.suspend()
        current.put("status", "stopping"); store.saveSession(current)
        val wasOwned = captureOwned
        val delivered = stopNative()
        status = if (delivered) L10n.t("인식 종료 요청 · 마지막 문장 저장 중") else L10n.t("인식 종료 실패 · 폰에서 자막을 종료해 주세요")
        if (!wasOwned) { finishDrain(!recovery.getBoolean("ownsStt", false)); return }
        main.removeCallbacks(drainTimeout); main.postDelayed(drainTimeout, 2500)
    }
    private fun finishDrain(acknowledged: Boolean) {
        val current = session ?: return
        val action = drainAction ?: return
        main.removeCallbacks(drainTimeout); drainAction = null
        status = if (acknowledged) drainReason else L10n.t("종료 응답 없음 · 폰에서 자막을 확인해 주세요")
        partial = ""; if (!closing) kickTick()
        if (action == "pause") {
            current.put("status", "paused"); store.saveSession(current); if (closing) stopSelf(); return
        }
        current.put("status", "ended").put("endedAt", System.currentTimeMillis())
        current.put("summaryStatus", if (store.settings().getBoolean("summaries")) "unavailable" else "disabled")
        store.saveSession(current)
        val endedId = current.getString("id")
        if (!pendingLive.contains(endedId)) pendingLive.addLast(endedId)
        session = null; confirmation = ""; cues.clear()
        if (closing) { stopSelf(); return }
        if (ai.available && store.settings().getBoolean("summaries")) summarizeSession(endedId, true)
    }

    private fun aiInput(current: JSONObject): JSONObject = JSONObject().put("notes", current.getJSONArray("noteSnapshots"))
        .put("utterances", current.getJSONArray("utterances")).put("previousSummary", current.getJSONObject("summary"))
    private fun dispatchLiveSummary() {
        if (liveBusy || !ai.available || !::store.isInitialized || !store.settings().getBoolean("summaries")) return
        val current = if (pendingLive.isNotEmpty()) store.session(pendingLive.first).also {
            if (it == null) pendingLive.removeFirst()
        } ?: return else session ?: return
        if (current.optString("status") !in listOf("running", "paused", "ended")) return
        val now = System.currentTimeMillis()
        if (now < liveRetryAt) return
        val cursor = current.optInt("liveCursor", 0)
        val rows = LiveSummaryPolicy.batch(current.getJSONArray("utterances"), cursor, now, lastSpeech,
            current.optString("status") != "running") ?: run {
            if (pendingLive.peekFirst() == current.getString("id")) pendingLive.removeFirst()
            return
        }
        val id = current.getString("id")
        val includeTerms = false // Independent contextual cues now own glossary explanations.
        liveBusy = true; liveStatus = L10n.t("Rokid AI 요약 중")
        ai.generate("liveSummary", JSONObject().put("utterances", rows).put("includeTerms", includeTerms)) { result -> main.post {
            if (closing) return@post
            liveBusy = false
            val latest = store.session(id)
            if (latest != null && store.settings().getBoolean("summaries")) {
                val valid = result.getOrNull()?.let { LiveSummaryPolicy.validate(it, rows) }
                if (valid != null) {
                    if (!includeTerms || !store.settings().getBoolean("cues")) valid.put("terms", JSONArray())
                    val history = latest.optJSONArray("liveSummaries") ?: JSONArray()
                    history.put(valid.put("createdAt", System.currentTimeMillis()))
                    latest.put("liveSummaries", history).put("liveCursor", cursor + rows.length())
                    store.saveSession(latest)
                    if (session?.optString("id") == id) { session = store.session(id) }
                    liveStatus = L10n.t("요약 갱신됨")
                } else {
                    liveStatus = L10n.t("요약 응답 실패 · 다시 시도 중")
                    liveRetryAt = System.currentTimeMillis() + 10000
                }
            }
        } }
    }
    private fun translateInterface() {
        if (localeBusy || liveBusy || summaryBusy || cueBusy || !ai.available || System.currentTimeMillis() < localeRetryAt) return
        val keys = L10n.missing()
        if (keys.isEmpty()) return
        val language = L10n.tag
        localeBusy = true
        ai.generate("localize", L10n.input(keys)) { result -> main.post {
            if (closing) return@post
            localeBusy = false
            val accepted = runCatching { result.getOrNull()?.let { L10n.accept(language, keys, it) } == true }.getOrDefault(false)
            if (!accepted) localeRetryAt = System.currentTimeMillis() + 30000
        } }
    }

    private fun requestCue() {
        val current = session ?: return
        if (closing || !ai.available || cueBusy || liveBusy || summaryBusy || localeBusy ||
            current.optString("status") != "running" || !store.settings().getBoolean("cues")) return
        val now = System.currentTimeMillis()
        if (now < cueRetryAt) return
        val all = current.getJSONArray("utterances")
        cueCursor = CueTriggerPolicy.freshCursor(all, cueCursor, now)
        val rows = CueTriggerPolicy.batch(all, cueCursor, now, lastSpeech, cueLastRequest) ?: return
        val nextCursor = cueCursor + rows.length()
        val context = JSONArray((maxOf(0, cueCursor - 6) until cueCursor).map { all.getJSONObject(it) })
        cueBusy = true; cueLastRequest = now
        val id = current.getString("id")
        val epoch = cueEpoch
        val notes = current.getJSONArray("noteSnapshots")
        val input = JSONObject().put("notes", notes).put("utterances", rows).put("context", context).put("seenConcepts", cues.seenConcepts())
        ai.generate("cue", input) { result -> main.post {
            if (closing) return@post
            cueBusy = false
            if (session?.optString("id") != id || session?.optString("status") != "running" || epoch != cueEpoch || !store.settings().getBoolean("cues")) return@post
            val generated = result.getOrNull()
            if (generated != null && generated.has("cue")) {
                cueCursor = nextCursor
                generated.optJSONObject("cue")?.let { cues.offer(it, notes, System.currentTimeMillis(), rows) }
            } else {
                cueRetryAt = System.currentTimeMillis() + 10000
                status = L10n.t("Rokid AI 응답 실패 · 자막은 계속")
            }
        } }
    }
    private fun summarize(final: Boolean) { session?.optString("id")?.let { summarizeSession(it, final) } }
    private fun summarizeSession(id: String, final: Boolean) {
        if (!ai.available || !store.settings().getBoolean("summaries")) return
        if (summaryBusy) {
            if (final && !pendingRecaps.contains(id)) pendingRecaps.addLast(id)
            return
        }
        val current = store.session(id) ?: return
        summaryBusy = true
        current.put("summaryStatus", "pending"); store.saveSession(current)
        // Prep documents are not meeting decisions. Never send them (or an earlier
        // potentially mistaken summary) as conversation evidence for a recap.
        val summaryInput = JSONObject().put("utterances", current.getJSONArray("utterances"))
        ai.generate(if (final) "recap" else "summary", summaryInput) { result -> main.post {
            if (closing) return@post
            summaryBusy = false
            val latest = store.session(id)
            if (latest != null) result.onSuccess { generated ->
                val summary = generated.optJSONObject("summary") ?: generated
                if (!validSummary(summary)) {
                    latest.put("summaryStatus", "error")
                } else {
                    latest.put("summary", summary).put("summaryStatus", "complete")
                    generated.optString("title").takeIf { it.isNotBlank() }?.let { latest.put("title", it.take(100)) }
                }
            }.onFailure { latest.put("summaryStatus", "error") }
            if (latest != null) {
                store.saveSession(latest)
                if (session?.optString("id") == id) session = store.session(id)
            }
            dispatchRecaps()
        } }
    }
    private fun dispatchRecaps() {
        if (summaryBusy || !ai.available || !::store.isInitialized || !store.settings().getBoolean("summaries")) return
        while (!summaryBusy && pendingRecaps.isNotEmpty()) summarizeSession(pendingRecaps.removeFirst(), true)
    }
    private fun validSummary(summary: JSONObject): Boolean {
        if (summary.opt("overview") !is String || summary.getString("overview").length > 12000) return false
        return listOf("conclusions", "discussion", "nextSteps").all { key ->
            val list = summary.optJSONArray(key) ?: return@all false
            list.length() <= 100 && (0 until list.length()).all { list.opt(it) is String && list.getString(it).length <= 4000 }
        }
    }
    private fun testAi() {
        check(session == null) { L10n.t("회의 종료 후 연결 테스트를 실행해 주세요") }
        check(ai.available) { ai.status }
        check(aiTest.optString("status") != "pending") { L10n.t("연결 테스트 중입니다") }
        val notes = JSONArray().put(JSONObject().put("id", "connection-test").put("title", L10n.t("연결 테스트용 자료"))
            .put("text", L10n.t("이번 프로젝트의 확정 예산은 100만 원입니다.")))
        val rows = JSONArray().put(JSONObject().put("id", "test-1").put("text", L10n.t("이번 프로젝트의 예산은 얼마인가요?")))
        aiTest = JSONObject().put("status", "pending").put("synthetic", true)
        ai.generate("cue", JSONObject().put("notes", notes).put("utterances", rows)) { result -> main.post {
            if (closing) return@post
            result.onSuccess { generated ->
                val cue = generated.optJSONObject("cue")
                val valid = cue != null && CuePolicy().offer(cue, notes)
                aiTest = JSONObject().put("status", if (valid) "passed" else "no_valid_cue")
                    .put("synthetic", true).put("cue", cue ?: JSONObject.NULL)
            }.onFailure { error -> aiTest = JSONObject().put("status", "error").put("error", error.message ?: L10n.t("요청 실패")) }
        } }
    }

    private fun testConceptCue() {
        check(ai.available) { ai.status }
        check(!cueBusy && !liveBusy && !summaryBusy) { L10n.t("Rokid AI 처리 중입니다") }
        val rows = JSONArray().put(JSONObject().put("id", "concept-test-1")
            .put("text", "이번 AI 검색 시스템에 RAG를 적용하는 방안을 검토합시다."))
        aiTest = JSONObject().put("status", "pending").put("synthetic", true)
        ai.generate("cue", JSONObject().put("notes", JSONArray()).put("utterances", rows)
            .put("context", JSONArray()).put("seenConcepts", JSONArray())) { result -> main.post {
            if (closing) return@post
            val raw = result.getOrNull()?.optJSONObject("cue")
            val policy = CuePolicy()
            val valid = raw?.optString("type") == "Concepts" && policy.offer(raw, JSONArray(), System.currentTimeMillis(), rows)
            aiTest = JSONObject().put("status", if (valid) "passed" else "error").put("synthetic", true)
                .put("conceptCue", if (valid) policy.tick(System.currentTimeMillis()) else JSONObject.NULL)
        } }
    }

    private fun testLiveSummary() {
        check(session == null) { L10n.t("회의 종료 후 테스트해 주세요") }
        check(ai.available) { ai.status }
        val rows = JSONArray().put(JSONObject().put("id", "synthetic-live-1").put("at", 1)
            .put("text", L10n.t("이번에는 RAG를 검토해 봅시다. 검색한 문서를 참고해 AI가 답하는 방식입니다. 아직 도입은 결정하지 않았습니다.")))
        aiTest = JSONObject().put("status", "pending").put("synthetic", true)
        ai.generate("liveSummary", JSONObject().put("utterances", rows).put("includeTerms", true)) { result -> main.post {
            if (closing) return@post
            val card = result.getOrNull()?.let { LiveSummaryPolicy.validate(it, rows) }
            aiTest = JSONObject().put("status", if (card == null) "error" else "passed").put("synthetic", true)
                .put("liveSummary", card ?: JSONObject.NULL)
        } }
    }

    private fun testLanguage(language: String) {
        check(session == null) { L10n.t("회의 종료 후 테스트해 주세요") }
        check(ai.available) { ai.status }
        aiTest = JSONObject().put("status", "pending").put("synthetic", true)
        val input = JSONObject().put("languageTag", L10n.normalize(language)).put("messages", JSONObject()
            .put("m0", "Tap to start a meeting").put("m1", "Summary {0}/{1}"))
        ai.generate("localize", input) { result -> main.post {
            if (closing) return@post
            val translations = result.getOrNull()?.optJSONObject("translations")
            val valid = translations?.optString("m0")?.isNotBlank() == true &&
                translations.optString("m1").contains("{0}") && translations.optString("m1").contains("{1}")
            aiTest = JSONObject().put("status", if (valid) "passed" else "error").put("synthetic", true)
                .put("languageTag", input.getString("languageTag")).put("translations", translations ?: JSONObject.NULL)
        } }
    }

    private fun <T> onMain(work: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return work()
        val task = FutureTask(work); main.post(task); return task.get(5, TimeUnit.SECONDS)
    }
    override fun handle(method: String, path: String, body: JSONObject, query: Map<String, String>): CompanionServer.Reply {
        try {
            fun json(value: Any) = CompanionServer.Reply(body = value.toString())
            val id = path.substringAfterLast('/')
            return when {
                method == "GET" && path == "/api/state" -> json(onMain { snapshot(false) })
                method == "GET" && path == "/api/notes" -> json(JSONObject().put("notes", store.notes()))
                method == "POST" && path == "/api/notes" -> json(store.saveNote(body))
                method == "POST" && path == "/api/import" -> json(store.saveNote(importer.extract(body)))
                method == "DELETE" && path.startsWith("/api/notes/") -> { store.deleteNote(id); json(JSONObject().put("ok", true)) }
                method == "GET" && path == "/api/settings" -> json(store.settings())
                method == "POST" && path == "/api/settings" -> json(onMain {
                    store.saveSettings(body).also { if (!it.getBoolean("cues")) { cueEpoch++; cues.suspend() } }
                })
                method == "GET" && path == "/api/sessions" -> json(JSONObject().put("sessions", store.sessions(query["q"] ?: "")))
                method == "GET" && path.startsWith("/api/sessions/") -> store.session(id)?.let { json(it) }
                    ?: CompanionServer.Reply(404, body = JSONObject().put("error", L10n.t("회의 기록을 찾을 수 없습니다")).toString())
                method == "DELETE" && path.startsWith("/api/sessions/") -> onMain {
                    require(session?.optString("id") != id) { L10n.t("진행 중인 회의는 먼저 종료해 주세요") }
                    store.deleteSession(id); json(JSONObject().put("ok", true))
                }
                method == "GET" && path.startsWith("/api/export/") -> CompanionServer.Reply(200, "text/markdown; charset=utf-8", store.exportMarkdown(id))
                method == "POST" && path == "/api/action" -> onMain {
                    when (body.getString("action")) {
                        "start" -> start(body.optBoolean("consent"))
                        "stop" -> stop(L10n.t("회의 종료"))
                        "pause" -> pause()
                        "resume" -> resume()
                        "dismissCue" -> cues.dismiss()
                        "testAi" -> testAi()
                        "testLiveSummary" -> testLiveSummary()
                        "testConceptCue" -> testConceptCue()
                        "testLanguage" -> testLanguage(body.getString("languageTag"))
                        "rebuildSummary" -> {
                            check(ai.available) { ai.status }; summarizeSession(body.getString("id"), true)
                        }
                        else -> error(L10n.t("지원하지 않는 동작입니다"))
                    }
                    json(snapshot(false))
                }
                else -> CompanionServer.Reply(404, body = JSONObject().put("error", L10n.t("페이지를 찾을 수 없습니다")).toString())
            }
        } catch (e: Exception) {
            val message = e.cause?.message ?: e.message ?: L10n.t("요청 처리 실패")
            return CompanionServer.Reply(400, body = JSONObject().put("error", message.take(250)).toString())
        }
    }
    override fun onDestroy() {
        closing = true
        main.removeCallbacksAndMessages(null)
        if (captureOwned) stopNative()
        session?.let { it.put("status", "interrupted").put("endedAt", System.currentTimeMillis()); store.saveSession(it) }
        if (::stt.isInitialized) stt.close()
        if (::web.isInitialized) web.close()
        ai.close(); runCatching { unregisterReceiver(screenReceiver) }
        releaseWake(); instance = null; super.onDestroy()
    }
    companion object { @Volatile var instance: MeetingService? = null; private set }
}
