package app.conversate.glass.probe

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * M0 measurement screen. Answers the open hardware questions in docs/PRD.md section 3:
 * which part of the 480x640 frame is visible, how much Korean text fits, whether a
 * third-party app can record from the microphone, which key codes the touchpad sends,
 * and how fast the battery drains with screen + microphone + Wi-Fi upload all on.
 *
 * Tap (ENTER) moves to the next page. Every key event is logged under the CVProbe tag.
 */
class ProbeActivity : Activity() {

    private val pageNames = listOf("화면 영역", "가독성", "마이크", "배터리")
    @Volatile private var page = 0

    private lateinit var header: TextView
    private lateinit var content: FrameLayout
    private lateinit var keyStrip: TextView

    private val ui = Handler(Looper.getMainLooper())
    private val keyLog = ArrayDeque<String>()
    private val keyDownAt = HashMap<Int, Long>()
    private var lastBackAt = 0L

    // Microphone meter state, written by the capture thread and read by the UI.
    @Volatile private var micRunning = false
    @Volatile private var rmsDb = -120.0
    @Volatile private var peakDb = -120.0
    @Volatile private var micStatus = "대기"
    private var micThread: Thread? = null
    private var meterSource = MediaRecorder.AudioSource.UNPROCESSED

    // Battery run state.
    @Volatile private var batteryRunning = false
    @Volatile private var uploadedBytes = 0L
    @Volatile private var uploadStatus = "업로드 안 함"
    private var batteryStartLevel = -1
    private var batteryStartAt = 0L
    private var sinkAddress: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        header = label(12f).apply { alpha = 0.7f }
        keyStrip = label(11f).apply { alpha = 0.7f }
        content = FrameLayout(this)

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            addView(header, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(content, LinearLayout.LayoutParams(MATCH, 0, 1f))
            addView(keyStrip, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        setContentView(column)

        applyIntent(intent)
        showPage(page)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        applyIntent(intent)
        showPage(page)
    }

    /** `adb shell am start ... --ei page 2 --es sink 10.0.11.11:8765` drives the probe remotely. */
    private fun applyIntent(intent: Intent?) {
        intent ?: return
        if (intent.hasExtra("page")) page = intent.getIntExtra("page", 0).coerceIn(0, pageNames.lastIndex)
        intent.getStringExtra("sink")?.let { sinkAddress = it }
        if (intent.hasExtra("source")) meterSource = intent.getIntExtra("source", meterSource)
    }

    override fun onDestroy() {
        stopMic()
        batteryRunning = false
        ui.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ---------------------------------------------------------------- pages

    private fun showPage(index: Int) {
        page = index
        stopMic()
        batteryRunning = false
        ui.removeCallbacksAndMessages(null)
        content.removeAllViews()
        header.text = "M0 ${index + 1}/${pageNames.size} ${pageNames[index]} · 탭=다음"
        note("page ${index + 1} ${pageNames[index]}")
        when (index) {
            0 -> showFrameGrid()
            1 -> showReadability()
            2 -> showMicrophone()
            3 -> showBattery()
        }
    }

    /** Numbered grid over the whole frame so the wearer can report which rows and columns are visible. */
    private fun showFrameGrid() {
        content.addView(GridView(this), FrameLayout.LayoutParams(MATCH, MATCH))
    }

    /** One line per text size, cut at the edge, plus the computed Korean characters per line. */
    private fun showReadability() {
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(column, FrameLayout.LayoutParams(MATCH, MATCH))
        val sample = "가나다라마바사아자차카타파하거너더러머버서어저처커터퍼허고노도로모보소오조초"
        column.post {
            val width = column.width
            for (sp in listOf(12f, 14f, 16f, 18f, 20f, 24f)) {
                val line = label(sp).apply {
                    maxLines = 1
                    text = sample
                }
                val perLine = floor(width / line.paint.measureText("가")).toInt()
                val px = line.textSize
                column.addView(label(11f).apply {
                    alpha = 0.6f
                    text = "${sp.toInt()}sp (${px.toInt()}px) · 한 줄 한글 ${perLine}자"
                })
                column.addView(line)
                note("readability sp=${sp.toInt()} px=${px.toInt()} width=$width charsPerLine=$perLine")
            }
            column.addView(label(16f).apply {
                text = "작년 리텐션이 어떻게 되죠? M12 41% · 업계 평균 25% (IR덱 p.7)"
            })
        }
    }

    private fun showMicrophone() {
        val out = label(12f).apply { typeface = Typeface.MONOSPACE }
        content.addView(out, FrameLayout.LayoutParams(MATCH, MATCH))
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            out.text = "마이크 권한 없음. 권한 요청 중"
            Log.w(TAG, "mic permission missing")
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        out.text = "오디오 소스 검사 중"
        thread(name = "mic-survey") {
            val report = StringBuilder()
            // With a speech clip on the device, every source is measured twice: in the
            // quiet room, then while the glasses' own speaker plays the clip. A source
            // that stays at digital silence in the second pass does not pass speech.
            val stimulus = File(getExternalFilesDir(null), "stim.wav")
            val passes = if (stimulus.exists()) listOf("조용", "음성") else listOf("조용")
            for (pass in passes) {
                val player = if (pass == "음성") startStimulus(stimulus) else null
                if (player != null) Thread.sleep(500)
                report.append("[$pass] 평균/최대 dBFS, z=0인 샘플\n")
                for (config in MIC_CONFIGS) {
                    // Leaving the page cancels the survey, so it cannot fight the
                    // next page for the microphone.
                    if (page != 2) {
                        player?.release()
                        return@thread
                    }
                    val line = surveyConfig(config)
                    report.append(line).append("\n")
                    note("mic-survey $pass $line")
                    ui.post { if (page == 2) out.text = report.toString() }
                }
                player?.let {
                    runCatching { it.stop() }
                    it.release()
                }
            }
            ui.post {
                if (page != 2) return@post
                startMic()
                tickMeter(out, report.toString())
            }
        }
    }

    private fun tickMeter(out: TextView, report: String) {
        if (page != 2) return
        val filled = (((rmsDb + 80) / 80) * 24).toInt().coerceIn(0, 24)
        val bar = "#".repeat(filled) + ".".repeat(24 - filled)
        out.text = report + "\n실시간 (소스 $meterSource, 16k)\n$bar\n" +
            "rms %.1f dBFS  peak %.1f dBFS\n%s".format(rmsDb, peakDb, micStatus)
        ui.postDelayed({ tickMeter(out, report) }, 100)
    }

    private fun showBattery() {
        val out = label(14f)
        content.addView(out, FrameLayout.LayoutParams(MATCH, MATCH))
        val granted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (granted) startMic()
        batteryRunning = true
        batteryStartAt = SystemClock.elapsedRealtime()
        batteryStartLevel = readBattery().level
        val logFile = File(getExternalFilesDir(null), "battery_log.csv")
        logFile.writeText("time,elapsed_min,level,temp_c,voltage_mv,plugged,mic,uploaded_kb\n")
        sinkAddress?.let { startUpload(it) }
        tickBattery(out, logFile, lastLoggedMinute = -1)
    }

    private fun tickBattery(out: TextView, logFile: File, lastLoggedMinute: Int) {
        if (page != 3 || !batteryRunning) return
        val now = readBattery()
        val elapsedMin = ((SystemClock.elapsedRealtime() - batteryStartAt) / 60_000).toInt()
        var logged = lastLoggedMinute
        if (elapsedMin != lastLoggedMinute) {
            logged = elapsedMin
            val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            val row = "$stamp,$elapsedMin,${now.level},${now.tempC},${now.voltageMv},${now.plugged},$micRunning,${uploadedBytes / 1024}"
            logFile.appendText(row + "\n")
            note("battery $row")
        }
        out.text = buildString {
            append(if (now.plugged) "USB 연결됨: 측정값 무효\nUSB를 뽑으면 실제 소모가 기록됨\n\n" else "배터리로 동작 중\n\n")
            append("경과 ${elapsedMin}분\n")
            append("시작 ${batteryStartLevel}% → 현재 ${now.level}%\n")
            append("온도 ${now.tempC}°C  전압 ${now.voltageMv}mV\n")
            append("마이크 ${if (micRunning) "켜짐" else "꺼짐"} · $micStatus\n")
            append("$uploadStatus ${uploadedBytes / 1024}KB\n")
        }
        ui.postDelayed({ tickBattery(out, logFile, logged) }, 1000)
    }

    // ------------------------------------------------------------ microphone

    private data class MicConfig(val name: String, val source: Int, val rate: Int, val stereo: Boolean)

    private fun startStimulus(file: File): MediaPlayer? = try {
        MediaPlayer().apply {
            setDataSource(file.absolutePath)
            isLooping = true
            prepare()
            start()
        }
    } catch (e: Exception) {
        Log.w(TAG, "stimulus playback failed: ${e.message}")
        null
    }

    /** Opens one source for 2 s and reports whether real audio arrives. */
    private fun surveyConfig(config: MicConfig): String {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) return "마이크 권한 없음"
        val channelMask = if (config.stereo) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        val channels = if (config.stereo) 2 else 1
        val label = "%-10s %2dk %s".format(config.name, config.rate / 1000, if (config.stereo) "st" else "mo")
        val minBuffer = AudioRecord.getMinBufferSize(config.rate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) return "$label 미지원"
        val record = try {
            AudioRecord(config.source, config.rate, channelMask, AudioFormat.ENCODING_PCM_16BIT, minBuffer * 4)
        } catch (e: Exception) {
            return "$label 생성 실패 ${e.javaClass.simpleName}"
        }
        try {
            if (record.state != AudioRecord.STATE_INITIALIZED) return "$label 초기화 실패"
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) return "$label 녹음 시작 거부"
            val total = config.rate * channels * 2
            val buffer = ShortArray(total)
            var read = 0
            val deadline = SystemClock.elapsedRealtime() + 4000
            while (read < total && SystemClock.elapsedRealtime() < deadline) {
                val n = record.read(buffer, read, total - read)
                if (n <= 0) break
                read += n
            }
            if (read == 0) return "$label 데이터 없음"
            // Skip the first 0.3 s: the input path ramps up and would skew the level.
            val skip = (config.rate * channels * 0.3).toInt().coerceAtMost(read / 2)
            val frameSamples = config.rate / 10 // 100 ms per channel
            val perChannel = (0 until channels).joinToString(" ") { ch ->
                var sum = 0.0
                var count = 0
                var zeros = 0
                var frameSum = 0.0
                var frameCount = 0
                var loudestFrame = 0.0
                var i = skip + ch
                while (i < read) {
                    val s = buffer[i].toDouble()
                    sum += s * s
                    frameSum += s * s
                    if (buffer[i].toInt() == 0) zeros++
                    count++
                    if (++frameCount == frameSamples) {
                        loudestFrame = max(loudestFrame, frameSum / frameCount)
                        frameSum = 0.0
                        frameCount = 0
                    }
                    i += channels
                }
                val average = toDb(sqrt(sum / max(count, 1)))
                val loudest = toDb(sqrt(loudestFrame))
                val zeroPct = 100 * zeros / max(count, 1)
                "%.0f/%.0f z%d%%".format(average, loudest, zeroPct)
            }
            return "$label $perChannel"
        } catch (e: Exception) {
            return "$label 오류 ${e.javaClass.simpleName}"
        } finally {
            runCatching { record.stop() }
            record.release()
        }
    }

    /** Continuous 16 kHz mono capture. Samples are measured and dropped, never stored. */
    private fun startMic() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            micStatus = "마이크 권한 없음"; return
        }
        if (micRunning) return
        micRunning = true
        micThread = thread(name = "mic-meter") {
            val rate = 16000
            val minBuffer = AudioRecord.getMinBufferSize(
                rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            var record: AudioRecord? = null
            try {
                record = AudioRecord(
                    meterSource, rate,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minBuffer, 3200) * 4
                )
                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    micStatus = "초기화 실패"
                    return@thread
                }
                record.startRecording()
                micStatus = "녹음 중"
                val frame = ShortArray(1600) // 100 ms
                var frames = 0
                var windowPeak = -120.0
                var windowSum = 0.0
                while (micRunning) {
                    val n = record.read(frame, 0, frame.size)
                    if (n <= 0) {
                        micStatus = "읽기 실패 $n"
                        break
                    }
                    var sum = 0.0
                    var peak = 0
                    for (i in 0 until n) {
                        val s = frame[i].toInt()
                        sum += s.toDouble() * s
                        val a = if (s < 0) -s else s
                        if (a > peak) peak = a
                    }
                    rmsDb = toDb(sqrt(sum / n))
                    peakDb = toDb(peak.toDouble())
                    windowSum += rmsDb
                    windowPeak = max(windowPeak, rmsDb)
                    frames++
                    if (frames % 10 == 0) {
                        // One line per second: average and loudest 100 ms frame.
                        note("mic-level avg=%.1f max=%.1f".format(windowSum / 10, windowPeak))
                        windowSum = 0.0
                        windowPeak = -120.0
                    }
                }
            } catch (e: Exception) {
                micStatus = "오류 ${e.javaClass.simpleName}"
                Log.e(TAG, "mic meter failed", e)
            } finally {
                runCatching { record?.stop() }
                record?.release()
                micRunning = false
            }
        }
    }

    private fun stopMic() {
        micRunning = false
        micThread?.join(500)
        micThread = null
        micStatus = "대기"
    }

    // --------------------------------------------------------------- battery

    private data class BatteryState(val level: Int, val tempC: Double, val voltageMv: Int, val plugged: Boolean)

    private fun readBattery(): BatteryState {
        val sticky = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = sticky?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = sticky?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        return BatteryState(
            level = if (scale > 0) level * 100 / scale else level,
            tempC = (sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0,
            voltageMv = sticky?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0,
            plugged = (sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0,
        )
    }

    /**
     * Sends 32 KB/s of silence to a sink on the local network. This stands in for the
     * radio load of streaming 16 kHz PCM to a speech service. No microphone data leaves
     * the device here.
     */
    private fun startUpload(address: String) {
        val host = address.substringBefore(':')
        val port = address.substringAfter(':', "8765").toIntOrNull() ?: 8765
        uploadedBytes = 0
        thread(name = "upload-load") {
            val chunk = ByteArray(3200)
            while (batteryRunning) {
                try {
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress(host, port), 3000)
                        uploadStatus = "업로드 중 $host"
                        val stream = socket.getOutputStream()
                        var sinceStatus = 0
                        while (batteryRunning) {
                            stream.write(chunk)
                            uploadedBytes += chunk.size
                            if (++sinceStatus >= 100) {
                                sinceStatus = 0
                                val b = readBattery()
                                stream.write("\nBAT,${b.level},${b.tempC},${b.plugged}\n".toByteArray())
                            }
                            stream.flush()
                            Thread.sleep(100)
                        }
                    }
                } catch (e: Exception) {
                    uploadStatus = "업로드 실패 ${e.javaClass.simpleName}"
                    Log.w(TAG, "upload failed: ${e.message}")
                    Thread.sleep(3000)
                }
            }
        }
    }

    // ------------------------------------------------------------------ input

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val name = KeyEvent.keyCodeToString(event.keyCode).removePrefix("KEYCODE_")
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) keyDownAt[event.keyCode] = event.eventTime
            KeyEvent.ACTION_UP -> {
                val held = event.eventTime - (keyDownAt.remove(event.keyCode) ?: event.eventTime)
                recordInput("$name ${held}ms" + if (held >= 500) " 길게" else "")
                handleKeyUp(event.keyCode, held)
            }
        }
        return true
    }

    private fun handleKeyUp(keyCode: Int, heldMs: Long) {
        when (keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER ->
                if (heldMs < 500) showPage((page + 1) % pageNames.size)
            KeyEvent.KEYCODE_BACK -> {
                // Two presses within a second leave the probe. One press is only logged.
                val now = SystemClock.elapsedRealtime()
                if (now - lastBackAt < 1000) finish()
                lastBackAt = now
            }
        }
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        recordInput("MOTION ${MotionEvent.actionToString(event.action)} src=0x${Integer.toHexString(event.source)}")
        return true
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_MOVE) {
            recordInput("TOUCH ${MotionEvent.actionToString(event.action)} ${event.x.toInt()},${event.y.toInt()}")
        }
        return true
    }

    private fun recordInput(entry: String) {
        note("input $entry")
        keyLog.addLast(entry)
        while (keyLog.size > 3) keyLog.removeFirst()
        keyStrip.text = keyLog.joinToString("\n")
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        note("permission result ${grantResults.joinToString()}")
        showPage(page)
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Logs to logcat and to probe_events.log on the device. The file survives a USB
     * unplug and logcat rotation, so a test done away from the PC can still be read.
     */
    private fun note(message: String) {
        Log.i(TAG, message)
        val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        synchronized(eventLogLock) {
            runCatching {
                File(getExternalFilesDir(null), "probe_events.log").appendText("$stamp $message\n")
            }
        }
    }

    private val eventLogLock = Any()

    private fun label(sp: Float) = TextView(this).apply {
        setTextColor(GREEN)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        gravity = Gravity.START
        includeFontPadding = false
    }

    private fun toDb(amplitude: Double): Double =
        if (amplitude < 1.0) -90.3 else 20 * log10(amplitude / 32768.0)

    /** Draws a line every 40 px with its coordinate, so visible bounds can be read off by eye. */
    private class GridView(context: Context) : View(context) {
        private val line = Paint().apply { color = GREEN; strokeWidth = 1f; alpha = 110 }
        private val border = Paint().apply { color = GREEN; strokeWidth = 3f; style = Paint.Style.STROKE }
        private val text = Paint().apply { color = GREEN; textSize = 18f; isAntiAlias = true }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            // Row labels use absolute screen coordinates, not coordinates inside this view.
            val origin = IntArray(2).also { getLocationOnScreen(it) }
            var y = 40 - origin[1] % 40
            while (y < h) {
                canvas.drawLine(0f, y.toFloat(), w, y.toFloat(), line)
                val value = "y${y + origin[1]}"
                canvas.drawText(value, 6f, y - 4f, text)
                canvas.drawText(value, w / 2 - 24f, y - 4f, text)
                canvas.drawText(value, w - 60f, y - 4f, text)
                y += 40
            }
            var x = 80
            while (x < w) {
                canvas.drawLine(x.toFloat(), 0f, x.toFloat(), h, line)
                canvas.drawText("x$x", x + 3f, 20f, text)
                canvas.drawText("x$x", x + 3f, h / 2 + 18f, text)
                canvas.drawText("x$x", x + 3f, h - 6f, text)
                x += 80
            }
            canvas.drawRect(1.5f, 1.5f, w - 1.5f, h - 1.5f, border)
        }
    }

    companion object {
        const val TAG = "CVProbe"
        val GREEN: Int = Color.rgb(0, 255, 0)
        private const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        private const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT

        private val MIC_CONFIGS = listOf(
            MicConfig("MIC", MediaRecorder.AudioSource.MIC, 16000, false),
            MicConfig("VOICE_REC", MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000, false),
            MicConfig("CAMCORDER", MediaRecorder.AudioSource.CAMCORDER, 16000, false),
            MicConfig("UNPROC", MediaRecorder.AudioSource.UNPROCESSED, 16000, false),
            MicConfig("VOICE_COM", MediaRecorder.AudioSource.VOICE_COMMUNICATION, 16000, false),
            MicConfig("MIC", MediaRecorder.AudioSource.MIC, 48000, true),
            MicConfig("UNPROC", MediaRecorder.AudioSource.UNPROCESSED, 48000, true),
        )
    }
}
