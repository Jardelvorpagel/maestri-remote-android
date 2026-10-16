package app.maestri.wire

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * HTTP layer over the pinned TLS channel (Maestri Wire v1).
 *
 * Transport rules honored:
 *  - HTTP/1.1 only, no ALPN: OkHttp forced to [Protocol.HTTP_1_1].
 *  - `Authorization: Bearer <token>` on every request under /api/.
 *  - Host header is the IP literal the user typed, so no Host-name 403s.
 *  - Errors surface as [ApiException] carrying the host's error payload.
 */
class WireClient {

    private var pinnedClient: OkHttpClient? = null
    private var pinnedValue: String? = null

    @get:Synchronized
    var currentPinnedValue: String? = null
        private set

    /** OkHttp client pinned to `pinnedBase64`; rebuilt whenever the pin changes. */
    @Synchronized
    fun pinned(pinnedBase64: String): OkHttpClient {
        if (pinnedClient != null && pinnedValue == pinnedBase64) return pinnedClient!!
        val tm = PinnedTrustManager(pinnedBase64)
        val factory: SSLSocketFactory = sslFactory(tm)
        pinnedClient = OkHttpClient.Builder()
            .sslSocketFactory(factory, tm)
            .hostnameVerifier { _, _ -> true } // trust model is the SPKI pin, not the name
            .protocols(listOf(Protocol.HTTP_1_1))
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
        pinnedValue = pinnedBase64
        currentPinnedValue = pinnedBase64
        return pinnedClient!!
    }

    /** A WebSocket-capable client with the same pin. */
    @Synchronized
    fun pinnedForSocket(pinnedBase64: String): OkHttpClient {
        val base = pinned(pinnedBase64)
        return base.newBuilder()
            .readTimeout(0, TimeUnit.MILLISECONDS) // sockets live forever
            .pingInterval(25, TimeUnit.SECONDS)   // TCP keepalive + RFC6455 ping
            .build()
    }

    private fun sslFactory(tm: X509TrustManager): SSLSocketFactory =
        SSLContext.getInstance("TLS")
            .apply { init(null, arrayOf<TrustManager>(tm), null) }
            .socketFactory

    /** Raw client for probing/pairing (first contact, trust-on-first-use capture). */
    private fun unpinnedClient(capture: CapturingTrustManager): OkHttpClient =
        OkHttpClient.Builder()
            .sslSocketFactory(
                SSLContext.getInstance("TLS")
                    .apply { init(null, arrayOf<TrustManager>(capture), null) }
                    .socketFactory,
                capture
            )
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .build()

    override fun toString(): String = "WireClient(pin=${currentPinnedValue?.take(8)}…)"

    companion object {
        /**
         * GET /api/info without auth or pin. Returns the info plus the SPKI pin (base64)
         * of the certificate the server presented — the manual-pairing "security key".
         * Enforces protocolVersion == 1.
         */
        suspend fun probeInfo(host: String, port: Int): Pair<ServerInfo, String> =
            withContext(Dispatchers.IO) {
                val capture = CapturingTrustManager()
                val temp = WireClient().unpinnedClient(capture)
                val url = HttpUrl.Builder()
                    .scheme("https").host(host).port(port)
                    .addPathSegments("api/info")
                    .build()
                temp.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
                    val text = resp.body?.string() ?: ""
                    if (!resp.isSuccessful) throw ApiException(resp.code, null, "HTTP ${resp.code}")
                    val info = ServerInfo.parse(JSONObject(text))
                    if (info.protocolVersion != 1) {
                        throw ApiException(0, "protocolVersion", "protocolVersion ${info.protocolVersion} != 1")
                    }
                    val cert = capture.presentedCert
                        ?: throw IOException("server presented no certificate")
                    info to MessageDigest.getInstance("SHA-256")
                        .digest(cert.publicKey.encoded)
                        .let { b64(it) }
                }
            }

        /** POST /pair {deviceName, code?|password?} — no auth. */
        suspend fun pair(
            host: String,
            port: Int,
            deviceName: String,
            code: String?,
            password: String?,
        ): JSONObject = withContext(Dispatchers.IO) {
            val capture = CapturingTrustManager()
            val temp = WireClient().unpinnedClient(capture)
            val payload = JSONObject().apply {
                put("deviceName", deviceName)
                if (code != null) put("code", code)
                if (password != null) put("password", password)
            }
            val req = Request.Builder()
                .url(HttpUrl.Builder().scheme("https").host(host).port(port)
                    .addPathSegment("pair").build())
                .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            temp.newCall(req).execute().use { resp ->
                val text = resp.body?.string() ?: ""
                if (!resp.isSuccessful) throw parseWireError(resp.code, text)
                JSONObject(text)
            }
        }

        fun b64(bytes: ByteArray): String =
            android.util.Base64.encodeToString(bytes, android.util.Base64.DEFAULT).trim()
    }

    // ---------------------------------------------------------------------------

    /**
     * Authenticated JSON call. Throws [ApiException]; 401 surfaces with status 401 so the
     * session can return to the pairing UX when the token is revoked or forgotten.
     */
    suspend fun api(
        host: String,
        port: Int,
        path: String,
        token: String,
        method: String = "GET",
        body: JSONObject? = null,
    ): JSONObject = withContext(Dispatchers.IO) {
        val ok = pinnedOkHttpSafe()
        val url = HttpUrl.Builder().scheme("https").host(host).port(port)
            .addPathSegments(path.trimStart('/'))
            .build()
        val builder = Request.Builder().url(url)
            .header("Authorization", "Bearer $token")
        when (method.uppercase()) {
            "POST" -> builder.post(
                (body ?: JSONObject()).toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            )
            "PUT" -> builder.put(
                (body ?: JSONObject()).toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            )
            "DELETE" -> builder.delete(
                (body ?: JSONObject()).toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            )
            else -> builder.get()
        }
        ok.newCall(builder.build()).execute().use { resp: Response ->
            val text = resp.body?.string() ?: ""
            if (resp.code == 401) throw parseWireError(401, text)
            if (!resp.isSuccessful) throw parseWireError(resp.code, text)
            if (text.isBlank()) JSONObject().put("ok", true) else JSONObject(text)
        }
    }

    @Synchronized
    private fun pinnedOkHttpSafe(): OkHttpClient {
        val pin = pinnedValue ?: throw IllegalStateException("WireClient has no pinned key yet")
        return pinned(pin)
    }
}