package app.conversate.glass.stt

import app.conversate.glass.meeting.L10n

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.util.Log
import java.io.Closeable
import org.json.JSONObject

/** Firmware-specific ABI recovered from AssistServer 0.4.1 (4124).
 * No Rokid library, signature permission, microphone capture, or system patch.
 */
class RokidSttClient(context: Context, private val listener: Listener) : Closeable {
    interface Listener {
        fun onStatus(status: String)
        fun onMessage(message: Message)
    }
    data class Message(val id: Long, val packageName: String?, val type: String?, val time: Long, val body: String?)
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var remote: IBinder? = null
    private var bound = false
    private var closed = false
    private val cleanup = Handler(Looper.getMainLooper())
    val isConnected: Boolean get() = remote != null
    private val death = IBinder.DeathRecipient { main.post { remote = null; status(L10n.t("서비스 연결 끊김")) } }
    private fun status(text: String) { Log.i(TAG, text); main.post { if (!closed) listener.onStatus(text) } }

    private val callback = object : Binder() {
        init { attachInterface(null, CLIENT) }
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) { reply?.writeString(CLIENT); return true }
            if (code !in 1..3) return super.onTransact(code, data, reply, flags)
            data.enforceInterface(CLIENT)
            when (code) {
                1 -> {
                    data.readInt() // RegisterResult presence; recovered parcelable has no fields.
                    reply?.writeNoException()
                    status(L10n.t("등록 확인 · 자막 대기"))
                }
                2 -> {
                    val message = if (data.readInt() != 0) Message(
                        data.readLong(), data.readString(), data.readString(), data.readLong(), data.readString()
                    ) else null
                    reply?.writeNoException()
                    reply?.writeInt(0) // Observe without consuming messages intended for the launcher.
                    if (message != null) main.post { if (!closed) listener.onMessage(message) }
                }
                3 -> {
                    data.readString(); data.readString()
                    val bytes = data.createByteArray()
                    reply?.writeNoException(); reply?.writeByteArray(bytes)
                }
            }
            return true
        }
    }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            if (closed) return
            try {
                check(service.interfaceDescriptor == SERVER) { L10n.t("지원하지 않는 Binder 인터페이스") }
                remote = service
                service.linkToDeath(death, 0)
                transact(1) { writeString(context.packageName); writeStrongBinder(callback) }
                status(L10n.t("서비스 연결 · 등록 요청 완료"))
            } catch (e: Exception) { remote = null; status(L10n.t("등록 실패: {0}" , e.message)) }
        }
        override fun onServiceDisconnected(name: ComponentName) { remote = null; status(L10n.t("서비스 연결 끊김")) }
        override fun onBindingDied(name: ComponentName) { remote = null; status(L10n.t("서비스 재연결 필요")) }
        override fun onNullBinding(name: ComponentName) { status(L10n.t("서비스가 연결을 거절함")) }
    }

    fun connect() {
        check(!closed)
        if (bound) return
        try {
            bound = context.bindService(Intent().setComponent(ComponentName(
                "com.rokid.os.sprite.assistserver", "com.rokid.os.sprite.assist.MasterAssistService"
            )), connection, Context.BIND_AUTO_CREATE)
            status(if (bound) L10n.t("서비스 연결 중") else L10n.t("서비스를 찾을 수 없음"))
        } catch (e: Exception) { status(L10n.t("연결 실패: {0}" , e.message)) }
    }

    private fun control(json: String) {
        check(!closed)
        transact(3) { writeString(context.packageName); writeString(json) }
    }

    /** Send the native phone command directly, without asking the launcher to open a scene. */
    fun requestStt(start: Boolean) {
        val generation = requestGeneration.incrementAndGet()
        val payload = JSONObject().put("cmd", "Accessibility")
            .put("key", if (start) "Accessibility_start" else "Accessibility_end")
        if (!start) payload.put("data", JSONObject().put("isExit", true).toString())
        val json = JSONObject().put("type", "cmd_phone_gatt_send_data").put("data", payload).toString()
        control(json)
        // A rapid close can race the phone's asynchronous start. Repeat the idempotent
        // end after startup settles, including if the Activity has already unbound.
        if (!start) {
            val service = remote
            cleanup.postDelayed({
                if (requestGeneration.get() == generation && service?.isBinderAlive == true) {
                    runCatching { transactOn(service, 3) { writeString(context.packageName); writeString(json) } }
                }
            }, 1500)
        }
        status(if (start) L10n.t("인식 시작 요청 · 결과 대기") else L10n.t("인식 종료 요청"))
    }

    private fun transact(code: Int, write: Parcel.() -> Unit) {
        val service = checkNotNull(remote) { L10n.t("서비스 연결 전입니다") }
        transactOn(service, code, write)
    }
    private fun transactOn(service: IBinder, code: Int, write: Parcel.() -> Unit) {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(SERVER); data.write()
            check(service.transact(code, data, reply, 0)) { L10n.t("지원하지 않는 호출 {0}" , code) }
            reply.readException()
        } finally { data.recycle(); reply.recycle() }
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { if (remote != null) transact(2) { writeString(context.packageName) } }
        runCatching { remote?.unlinkToDeath(death, 0) }
        if (bound) context.unbindService(connection)
        bound = false; remote = null
        main.removeCallbacksAndMessages(null)
    }
    companion object {
        private val requestGeneration = java.util.concurrent.atomic.AtomicLong()
        const val TAG = "RokidSTT"
        private const val SERVER = "com.rokid.os.sprite.assist.server.IAssistServer"
        private const val CLIENT = "com.rokid.os.sprite.assist.client.IAssistClient"
    }
}
