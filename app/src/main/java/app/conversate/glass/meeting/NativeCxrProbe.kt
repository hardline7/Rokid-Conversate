package app.conversate.glass.meeting

import android.content.Context
import android.os.Process
import android.util.Log
import dalvik.system.DexClassLoader
import org.json.JSONObject
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Ordinary-UID diagnostic adapter to the installed device's own CXR bridge.
 * It never edits the source APK, starts audio, submits a model request automatically,
 * or impersonates a system package. Native availability must be observed at runtime.
 * The vendor API has no unsubscribe/dispose: one bounded shared subscription lives
 * until process exit; close() detaches this instance's consumer only.
 */
class NativeCxrProbe(context: Context) : AutoCloseable {
    private val context = context.applicationContext
    private val consumerId = nextId.incrementAndGet()
    @Volatile private var closed = false

    fun connect(onMessage: (String) -> Unit): JSONObject {
        if (closed) return diagnostic(false).put("error", "probe_closed")
        synchronized(lock) {
            try {
                if (bridge == null) initialize(context)
                consumers[consumerId] = onMessage
                return diagnostic(true)
            } catch (failure: Throwable) {
                consumers.remove(consumerId)
                val cause = if (failure is InvocationTargetException) failure.targetException else failure
                // Do not include app payloads, account data, or request text in diagnostics.
                Log.w(TAG, "connect failed: ${cause.javaClass.simpleName}")
                return diagnostic(false).put("error", cause.javaClass.simpleName)
                    .put("message", (cause.message ?: "CXR native bridge unavailable").take(300))
            }
        }
    }

    /** Delivery acceptance is not a successful model result. Results arrive on connect's callback. */
    fun send(key: String, json: String): Boolean {
        if (closed || key != REQUEST || json.toByteArray(Charsets.UTF_8).size > MAX_BYTES) return false
        if (runCatching { JSONObject(json) }.isFailure) return false
        synchronized(lock) {
            val target = bridge ?: return false
            val capsType = capsClass ?: return false
            return try {
                val caps = capsType.getConstructor().newInstance()
                val write = capsType.getMethod("write", String::class.java)
                write.invoke(caps, key); write.invoke(caps, json)
                val code = (target.javaClass.getMethod("sendMessage", String::class.java, capsType)
                    .invoke(target, COMMAND, caps) as Number).toInt()
                Log.i(TAG, "send accepted=${code >= 0} code=$code bytes=${json.toByteArray(Charsets.UTF_8).size}")
                code >= 0
            } catch (failure: Throwable) {
                Log.w(TAG, "send failed: ${failure.javaClass.simpleName}")
                false
            }
        }
    }

    override fun close() {
        closed = true
        consumers.remove(consumerId)
    }

    private fun diagnostic(connected: Boolean) = JSONObject().put("connected", connected)
        .put("uid", Process.myUid()).put("packageName", context.packageName)
        .put("sourceApk", sourceApk).put("subscribedCommand", COMMAND)
        .put("subscribeCode", subscribeCode).put("maxRequestBytes", MAX_BYTES)
        .put("nativeUnsubscribeAvailable", false)
        .put("cleanup", "close detaches consumer; native subscription lasts until process exit")

    companion object {
        private const val TAG = "NativeCxrProbe"
        private const val PACKAGE = "com.rokid.os.sprite.assistserver"
        private const val BRIDGE = "com.rokid.cxr.CXRServiceBridge"
        private const val CALLBACK = "com.rokid.cxr.CXRServiceBridge\$MsgCallback"
        private const val COMMAND = "Jsai"
        const val REQUEST = "Jsai_AgentSessionRequest"
        const val RESULT = "Jsai_AgentSessionResult"
        private const val MAX_BYTES = 16384
        private val lock = Any()
        private val nextId = AtomicInteger()
        private val consumers = ConcurrentHashMap<Int, (String) -> Unit>()
        private var bridge: Any? = null
        private var capsClass: Class<*>? = null
        private var callbackReference: Any? = null
        private var sourceApk = ""
        private var subscribeCode = Int.MIN_VALUE

        private fun initialize(context: Context) {
            val info = context.packageManager.getApplicationInfo(PACKAGE, 0)
            sourceApk = info.sourceDir
            check(File(sourceApk).canRead()) { "Installed source APK is not readable" }
            val libraryPaths = listOfNotNull(info.nativeLibraryDir?.takeIf { it.isNotBlank() }, "$sourceApk!/lib/arm64-v8a")
                .distinct().joinToString(File.pathSeparator)
            val optimized = File(context.codeCacheDir, "native-cxr-probe").apply { mkdirs() }
            val loader = DexClassLoader(sourceApk, optimized.absolutePath, libraryPaths, context.classLoader)
            val type = loader.loadClass(BRIDGE)
            val capsType = loader.loadClass("com.rokid.cxr.Caps")
            val callbackType = loader.loadClass(CALLBACK)
            check(callbackType.isInterface) { "Unexpected native callback shape" }
            // Constructor/native API shape recovered in bridge-init.json and MsgCallback-class.json.
            // Vendor construction starts only its audio-service helper binding, not AudioRecord.
            val instance = type.getConstructor(Context::class.java).newInstance(context)
            val callback = Proxy.newProxyInstance(loader, arrayOf(callbackType)) { proxy, method, args ->
                when (method.name) {
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.getOrNull(0)
                    "toString" -> "ConversateNativeCxrCallback"
                    "onReceive" -> {
                        try {
                            val cmd = args?.getOrNull(0) as? String
                            val caps = args?.getOrNull(1)
                            if (cmd == COMMAND && caps != null) {
                                val size = (capsType.getMethod("size").invoke(caps) as Number).toInt()
                                if (size >= 2) {
                                    val at = capsType.getMethod("at", Int::class.javaPrimitiveType)
                                    fun readString(index: Int): String {
                                        val value = at.invoke(caps, index)
                                        return value.javaClass.getMethod("getString").invoke(value) as String
                                    }
                                    val key = readString(0)
                                    if (key == RESULT) {
                                        val payload = readString(1)
                                        if (payload.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) {
                                            val message = JSONObject().put("cmd", cmd).put("key", key)
                                                .put("data", payload).put("capsCount", size).toString()
                                            for (consumer in consumers.values) runCatching { consumer(message) }
                                        }
                                    }
                                }
                            }
                        } catch (failure: Throwable) { Log.w(TAG, "callback decode failed: ${failure.javaClass.simpleName}") }
                        null
                    }
                    else -> null
                }
            }
            subscribeCode = (type.getMethod("subscribe", String::class.java, callbackType)
                .invoke(instance, COMMAND, callback) as Number).toInt()
            check(subscribeCode == 0) { "Native subscribe rejected code=$subscribeCode" }
            capsClass = capsType
            callbackReference = callback
            bridge = instance
            Log.i(TAG, "connected uid=${Process.myUid()} subscription=$COMMAND code=$subscribeCode")
        }
    }
}
