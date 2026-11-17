package app.conversate.glass.stt

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File

/** Foreground-only native STT prototype. Opening it never starts recognition. */
class SttActivity : Activity(), RokidSttClient.Listener {
    private lateinit var client: RokidSttClient
    private lateinit var state: TextView
    private lateinit var transcript: TextView
    private val main = Handler(Looper.getMainLooper())
    private var active = false
    private var stopping = false
    private var pendingStart = false
    private var pendingStop = false
    private var journal: TranscriptJournal? = null
    private val confirmed = linkedMapOf<Pair<String, Int>, String>()
    private var partial = ""
    private var frames = 0
    private val noResult = Runnable { if (active && frames == 0) state.text = "결과 대기 · 폰 Rokid 연결 확인" }
    private val stopTimeout = Runnable { if (stopping) state.text = "종료 응답 없음 · 폰에서 자막 확인" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK); setPadding(16, 40, 16, 80)
        }
        fun label(size: Float) = TextView(this).apply { setTextColor(Color.GREEN); textSize = size; layout.addView(this) }
        label(18f).text = "CONVERSATE · ROKID STT"
        state = label(13f)
        label(12f).text = "폰의 Rokid 앱 연결 필요\n탭: 시작/종료 · 두 번 탭: 종료/나가기"
        transcript = label(16f).apply { maxLines = 10 }
        label(11f).text = "확정 문장만 안경에 저장 · 음성 파일 미저장"
        setContentView(layout)
        pendingStart = intent.getStringExtra("stt") == "start"
        pendingStop = intent.getStringExtra("stt") == "stop"
        client = RokidSttClient(this, this)
        client.connect()
    }

    override fun onStatus(status: String) {
        state.text = status
        if (pendingStop && client.isConnected) { pendingStop = false; stopSession(force = true); return }
        if (pendingStart && client.isConnected) { pendingStart = false; startSession() }
    }
    private fun startSession() {
        if (active || stopping) return
        if (!client.isConnected) { state.text = "서비스 연결을 기다려 주세요"; return }
        if (!getSystemService(PowerManager::class.java).isInteractive) {
            state.text = "안경을 깨운 뒤 탭하여 시작"; return
        }
        try {
            journal = TranscriptJournal(File(filesDir, "transcripts"))
            confirmed.clear(); partial = ""; frames = 0
            client.requestStt(true)
            active = true
            transcript.text = "말씀하시면 자막이 표시됩니다"
            main.postDelayed(noResult, 15000)
        } catch (e: Exception) { state.text = "시작 실패: ${e.message}" }
    }
    private fun stopSession(force: Boolean = false) {
        pendingStart = false
        if (!active && !stopping && !force) return
        try {
            client.requestStt(false)
            active = false; stopping = true
            main.removeCallbacks(noResult); main.removeCallbacks(stopTimeout)
            main.postDelayed(stopTimeout, 8000)
        } catch (e: Exception) { state.text = "종료 실패 · 폰에서 자막 종료 필요" }
    }
    override fun onMessage(message: RokidSttClient.Message) {
        val body = message.body ?: return
        val envelope = RokidTranscript.envelope(body) ?: return
        if (envelope.optString("key") == "Accessibility_end") {
            active = false; stopping = false
            main.removeCallbacks(noResult); main.removeCallbacks(stopTimeout)
            state.text = "인식 종료 확인 · 탭하여 새 회의"
            Log.i(RokidSttClient.TAG, "native stop confirmed")
            return
        }
        if (!active && !stopping) return
        val event = RokidTranscript.parse(body) ?: return
        frames++
        if (event.isFinal) {
            try { journal?.append(event) } catch (e: Exception) {
                stopSession(); state.text = "저장 실패 · 인식 종료 요청"; return
            }
            confirmed[event.audioPath to event.number] = event.content
            while (confirmed.size > 3) confirmed.remove(confirmed.keys.first())
            partial = ""
        } else { partial = event.content }
        transcript.text = (confirmed.values.map { it.takeLast(70) } + listOfNotNull(partial.takeIf { it.isNotEmpty() }?.takeLast(100)))
            .joinToString("\n\n").takeLast(220)
        if (!stopping) state.text = "● 인식 중 · 탭하여 종료"
        Log.i(RokidSttClient.TAG, "transcript final=${event.isFinal} number=${event.number} path=${event.audioPath} frames=$frames")
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        when (intent.getStringExtra("stt")) {
            "start" -> if (client.isConnected) startSession() else { pendingStart = true }
            "stop" -> if (client.isConnected) stopSession(force = true) else { pendingStop = true; pendingStart = false }
        }
    }
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_UP) return true
        when (event.keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> if (active) stopSession() else startSession()
            KeyEvent.KEYCODE_BACK -> if (active || stopping) stopSession() else finish()
        }
        return true
    }
    override fun onStop() { if (active || stopping) stopSession(); super.onStop() }
    override fun onDestroy() {
        if (active || stopping) stopSession()
        main.removeCallbacksAndMessages(null)
        client.close(); super.onDestroy()
    }
}
