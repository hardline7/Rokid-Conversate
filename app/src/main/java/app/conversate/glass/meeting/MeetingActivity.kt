package app.conversate.glass.meeting

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.WindowManager
import org.json.JSONObject

class MeetingActivity : Activity() {
    private lateinit var lens: MeetingLensView
    private val main = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            if (MeetingService.instance == null) startForegroundService(Intent(this@MeetingActivity, MeetingService::class.java))
            val state = MeetingService.instance?.also { it.showLens() }?.lensState() ?: JSONObject().put("status", L10n.t("준비 중"))
            lens.render(state)
            main.postDelayed(this, if (state.optJSONObject("active")?.optString("status") == "running") 250 else 1000)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        lens = MeetingLensView(this); setContentView(lens)
    }
    override fun onResume() { super.onResume(); startForegroundService(Intent(this, MeetingService::class.java)); MeetingService.instance?.showLens(); main.removeCallbacks(refresh); main.post(refresh) }
    override fun onStop() {
        main.removeCallbacks(refresh)
        MeetingService.instance?.lensVisible = false
        MeetingService.instance?.closeFromLens()
        super.onStop()
    }
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_UP) return true
        val service = MeetingService.instance ?: return true
        when (event.keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> service.tap()
            KeyEvent.KEYCODE_BACK -> if (!service.back()) finish()
            KeyEvent.KEYCODE_DPAD_DOWN -> service.swipe(1)
            KeyEvent.KEYCODE_DPAD_UP -> service.swipe(-1)
        }
        lens.render(service.lensState()); return true
    }
    override fun onDestroy() { main.removeCallbacksAndMessages(null); super.onDestroy() }
}
