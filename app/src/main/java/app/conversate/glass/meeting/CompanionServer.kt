package app.conversate.glass.meeting

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.io.Closeable
import java.io.InputStream
import java.math.BigInteger
import java.net.NetworkInterface
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager
import javax.security.auth.x500.X500Principal

/** Local HTTPS companion. The certificate and its private key stay in AndroidKeyStore.
 * A browser needs to accept this device's self-signed certificate once.
 */
class CompanionServer(private val context: Context, private val backend: Backend) : NanoHTTPD(PORT), Closeable {
    interface Backend {
        fun handle(method: String, path: String, body: JSONObject, query: Map<String, String>): Reply
    }
    data class Reply(val status: Int = 200, val mime: String = "application/json", val body: String)
    private data class Attempts(var count: Int = 0, var since: Long = System.currentTimeMillis())
    private val random = SecureRandom()
    private val auth = mutableMapOf<String, Long>()
    private val attempts = mutableMapOf<String, Attempts>()
    @Volatile var pairingPin: String = newPin()
        private set
    @Volatile var certificateFingerprint: String = ""
        private set

    fun rotatePin() = synchronized(auth) { pairingPin = newPin(); auth.clear(); attempts.clear() }
    private fun newPin() = "%06d".format(java.util.Locale.US, random.nextInt(1_000_000))

    fun startSecure() {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!store.containsAlias(TLS_ALIAS)) {
            val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
            generator.initialize(KeyGenParameterSpec.Builder(TLS_ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                // Conscrypt can request raw (NONE) TLS signatures over an already
                // hashed handshake. Also allow SHA384 used by TLS cipher suites.
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256,
                    KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
                .setCertificateSubject(X500Principal("CN=Conversate.local"))
                .setCertificateSerialNumber(BigInteger(120, random))
                .setCertificateNotBefore(Date(System.currentTimeMillis() - 86400000))
                .setCertificateNotAfter(Date(System.currentTimeMillis() + 315360000000L)).build())
            generator.generateKeyPair()
        }
        val chain = store.getCertificateChain(TLS_ALIAS).map { it as X509Certificate }.toTypedArray()
        val key = store.getKey(TLS_ALIAS, null) as PrivateKey
        certificateFingerprint = MessageDigest.getInstance("SHA-256").digest(chain[0].encoded)
            .joinToString(":") { "%02X".format(it.toInt() and 255) }
        val manager = object : X509ExtendedKeyManager() {
            override fun getPrivateKey(alias: String?) = if (alias == TLS_ALIAS) key else null
            override fun getCertificateChain(alias: String?) = if (alias == TLS_ALIAS) chain else null
            override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = if (keyType?.startsWith("EC") == true) arrayOf(TLS_ALIAS) else null
            override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) = if (keyType?.startsWith("EC") == true) TLS_ALIAS else null
            override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?) = if (keyType?.startsWith("EC") == true) TLS_ALIAS else null
            override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = null
            override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = null
        }
        val tls = SSLContext.getInstance("TLS").apply { init(arrayOf(manager), null, random) }
        makeSecure(tls.serverSocketFactory, arrayOf("TLSv1.2", "TLSv1.3"))
        start(SOCKET_READ_TIMEOUT, true)
    }

    override fun serve(session: IHTTPSession): Response {
        return try { serveChecked(session) } catch (_: org.json.JSONException) {
            error(400, L10n.t("요청 형식을 확인해 주세요"))
        } catch (_: java.nio.charset.CharacterCodingException) {
            error(400, L10n.t("UTF-8 텍스트로 요청해 주세요"))
        } catch (e: IllegalArgumentException) {
            error(400, e.message?.take(160) ?: L10n.t("입력을 확인해 주세요"))
        } catch (_: Exception) { error(500, L10n.t("처리하지 못했습니다. 안경 연결을 확인해 주세요")) }
    }

    private fun serveChecked(session: IHTTPSession): Response {
        val method = session.method.name
        if (method !in setOf("GET", "POST", "DELETE")) return error(405, L10n.t("지원하지 않는 요청입니다"))
        val host = session.headers["host"]?.lowercase()?.trim() ?: return error(400, L10n.t("주소를 확인해 주세요"))
        if (!validHost(host)) return error(403, L10n.t("안경에 표시된 주소로 연결해 주세요"))
        if (method != "GET" && session.headers["origin"] != "https://$host") {
            return error(403, L10n.t("같은 안경 관리 화면에서 요청해 주세요"))
        }
        if (session.headers["transfer-encoding"] != null) return error(411, L10n.t("파일 크기를 확인할 수 없습니다"))
        val size = session.headers["content-length"]?.toLongOrNull()
        if (size != null && (size < 0 || size > MAX_BODY)) return error(413, L10n.t("파일은 8MB 이하로 올려 주세요"))
        if (method == "POST" && size == null) return error(411, L10n.t("요청 크기가 필요합니다"))
        val path = session.uri
        if (method == "GET" && path == "/locale") return json(L10n.snapshot())
        if (method == "GET" && path == "/locale.js") {
            val script = "globalThis.ConversateLocale=" + L10n.snapshot().toString() + ";\n" +
                context.assets.open("companion/i18n.js").bufferedReader().use { it.readText() }
            return headers(newFixedLengthResponse(Response.Status.OK, "application/javascript; charset=utf-8", script))
        }
        if (method == "GET" && path in setOf("/", "/app.js", "/style.css")) {
            val asset = if (path == "/") "index.html" else path.removePrefix("/")
            val mime = when (asset) { "app.js" -> "application/javascript"; "style.css" -> "text/css"; else -> "text/html" }
            val bytes = context.assets.open("companion/$asset").use { it.readBytes() }
            return headers(newFixedLengthResponse(Response.Status.OK, "$mime; charset=utf-8", bytes.inputStream(), bytes.size.toLong()))
        }
        if (path == "/auth" && method == "POST") {
            if ((size ?: 0) > 1024) return error(413, L10n.t("PIN을 확인해 주세요"))
            return login(session, readBody(session))
        }
        if (!authorized(session)) return error(401, L10n.t("안경에 표시된 PIN으로 연결해 주세요"))
        if (path == "/logout" && method == "POST") {
            synchronized(auth) { auth.remove(cookie(session)) }
            return json(JSONObject().put("ok", true)).apply { addHeader("Set-Cookie", "conversate=; Path=/; Max-Age=0; Secure; HttpOnly; SameSite=Strict") }
        }
        if (!path.startsWith("/api/")) return error(404, L10n.t("페이지를 찾을 수 없습니다"))
        if (method == "POST" && !session.headers["content-type"].orEmpty().startsWith("application/json")) return error(415, L10n.t("JSON 형식이 필요합니다"))
        val body = if (method == "POST") readBody(session) else JSONObject()
        val query = session.parameters.mapValues { it.value.firstOrNull().orEmpty() }
        val reply = backend.handle(method, path, body, query)
        val status = Response.Status.lookup(reply.status) ?: Response.Status.INTERNAL_ERROR
        val mime = if (reply.mime.contains("charset=", ignoreCase = true)) reply.mime else "${reply.mime}; charset=utf-8"
        return headers(newFixedLengthResponse(status, mime, reply.body)).apply {
            if (path.startsWith("/api/export/")) addHeader("Content-Disposition", "attachment; filename=\"conversate.md\"")
        }
    }

    private fun readBody(session: IHTTPSession): JSONObject {
        val length = session.headers["content-length"]?.toLongOrNull()
            ?: throw IllegalArgumentException(L10n.t("요청 크기가 필요합니다"))
        return readJsonUtf8(session.inputStream, length)
    }
    private fun login(session: IHTTPSession, body: JSONObject): Response = synchronized(auth) {
        val now = System.currentTimeMillis()
        auth.entries.removeAll { it.value < now }
        attempts.entries.removeAll { now - it.value.since > 300000 }
        if (attempts.size > 128) return@synchronized error(429, L10n.t("잠시 후 다시 연결해 주세요"))
        val attempt = attempts.getOrPut(session.remoteIpAddress) { Attempts() }
        if (attempt.count >= 5) return@synchronized error(429, L10n.t("PIN 입력 횟수를 초과했습니다. 5분 후 다시 시도해 주세요"))
        attempt.count++
        val provided = body.optString("pin")
        if (!MessageDigest.isEqual(provided.toByteArray(), pairingPin.toByteArray())) return@synchronized error(401, L10n.t("PIN이 맞지 않습니다"))
        attempts.remove(session.remoteIpAddress)
        if (auth.size >= 16) auth.remove(auth.minByOrNull { it.value }?.key)
        val token = ByteArray(32).also(random::nextBytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        auth[token] = now + AUTH_TTL
        json(JSONObject().put("ok", true)).apply { addHeader("Set-Cookie", "conversate=$token; Path=/; Max-Age=43200; Secure; HttpOnly; SameSite=Strict") }
    }
    private fun cookie(session: IHTTPSession): String? = session.headers["cookie"].orEmpty().split(';')
        .map { it.trim().split('=', limit = 2) }.firstOrNull { it.size == 2 && it[0] == "conversate" }?.get(1)
    private fun authorized(session: IHTTPSession): Boolean = synchronized(auth) {
        val token = cookie(session) ?: return@synchronized false
        val expiry = auth[token] ?: return@synchronized false
        if (expiry < System.currentTimeMillis()) { auth.remove(token); false } else true
    }
    private fun validHost(host: String): Boolean {
        val allowed = mutableSetOf("localhost:$PORT", "127.0.0.1:$PORT", "[::1]:$PORT")
        NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { network ->
            network.inetAddresses.toList().forEach { address ->
                val ip = address.hostAddress?.substringBefore('%') ?: return@forEach
                allowed += if (ip.contains(':')) "[$ip]:$PORT" else "$ip:$PORT"
            }
        }
        return host in allowed
    }
    private fun json(value: JSONObject) = headers(newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", value.toString()))
    private fun error(status: Int, message: String) = headers(newFixedLengthResponse(Response.Status.lookup(status), "application/json; charset=utf-8", JSONObject().put("error", message).toString())).apply { closeConnection(true) }
    private fun headers(response: Response): Response = response.apply {
        addHeader("Cache-Control", "no-store")
        addHeader("X-Content-Type-Options", "nosniff")
        addHeader("Referrer-Policy", "no-referrer")
        addHeader("X-Frame-Options", "DENY")
        addHeader("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self' data:; base-uri 'none'; frame-ancestors 'none'; form-action 'self'")
    }
    override fun close() { stop(); synchronized(auth) { auth.clear(); attempts.clear() } }
    companion object {
        const val PORT = 8443
        private const val TLS_ALIAS = "conversate_companion_tls_v3_ec"
        private const val MAX_BODY = 12L * 1024 * 1024
        private const val AUTH_TTL = 12L * 60 * 60 * 1000
        /** NanoHTTPD 2.3.1 defaults JSON without a charset to ASCII. Read bounded
         * UTF-8 directly, without its parseBody temporary files or replacement decoding. */
        internal fun readJsonUtf8(input: InputStream, length: Long): JSONObject {
            require(length in 0..MAX_BODY) { L10n.t("요청 크기를 확인해 주세요") }
            val bytes = ByteArray(length.toInt())
            var offset = 0
            while (offset < bytes.size) {
                val count = input.read(bytes, offset, bytes.size - offset)
                require(count > 0) { L10n.t("요청이 중간에 끊겼습니다") }
                offset += count
            }
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            return JSONObject(text.takeIf { it.isNotBlank() } ?: "{}")
        }
    }
}
