package app.maestri.wire

import android.util.Base64
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Maestri Wire trust model: TLS with NO CA validation and NO hostname check.
 * Trust comes entirely from pinning SHA-256 of the certificate's SubjectPublicKeyInfo
 * (DER) — the "security key" the host shows in Settings → Wire → Manual.
 *
 * SPKI SHA-256 computation: X509Certificate.publicKey.encoded is exactly the DER
 * SubjectPublicKeyInfo encoding in Java, so hashing it reproduces the pin from the
 * QR code (base64) / Manual tab (hex "AB:CD:...").
 */
object Pinning {

    /** SHA-256 over the SPKI DER of the leaf certificate, as base64 (no padding). */
    fun pinB64(cert: Certificate): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(cert.publicKey.encoded)
        return Base64.encodeToString(digest, Base64.NO_PADDING or Base64.NO_WRAP)
    }

    /** The same digest as lowercase hex, colon-separated pairs — the "security key" format. */
    fun pinHex(cert: Certificate): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(cert.publicKey.encoded)
        return digest.joinToString(":") { "%02x".format(it) }
    }

    fun pinHexFromB64(pinB64: String): String {
        val digest = Base64.decode(pinB64, Base64.DEFAULT)
        return digest.joinToString(":") { "%02x".format(it) }
    }

    /**
     * Normalize a key the user might paste: base64 (QR) or colon-hex (Manual tab).
     * Returns the canonical base64 form used for comparison/storage, or null if unparseable.
     */
    fun normalizeKey(input: String): String? {
        val cleaned = input.trim().replace(":", "").replace("-", "").replace(" ", "")
        if (cleaned.isEmpty()) return null
        return when {
            // Hex: 64 hex chars after colon removal
            cleaned.length == 64 && cleaned.all { it in "0123456789abcdefABCDEF" } -> {
                val bytes = cleaned.lowercase().chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                base64NoPad(bytes)
            }
            else -> {
                val decoded = try {
                    Base64.decode(input.trim(), Base64.DEFAULT)
                } catch (e: IllegalArgumentException) {
                    return null
                }
                if (decoded.size != 32) return null
                base64NoPad(decoded)
            }
        }
    }

    fun base64NoPad(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.NO_PADDING or Base64.NO_WRAP)

    /** True when the pinned value matches this certificate. */
    fun matches(pinnedBase64: String, cert: Certificate): Boolean {
        val expected = normalizeKey(pinnedBase64) ?: return false
        return pinB64(cert) == expected
    }
}

/**
 * Accepts ONLY a server certificate whose SPKI SHA-256 matches the stored pin.
 */
class PinnedTrustManager(private val pinnedBase64: String) : X509TrustManager {

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        // No client certificates in this protocol; accept.
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        if (chain.isNullOrEmpty()) throw javax.net.ssl.SSLException("empty certificate chain")
        val cert = chain[0]
        if (!Pinning.matches(pinnedBase64, cert)) {
            throw javax.net.ssl.SSLHandshakeException(
                "server key does not match pinned security key"
            )
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/**
 * First-contact trust capture: accepts any certificate ONCE (trust-on-first-use prompt),
 * records the presented cert so the app can show its fingerprint before pinning.
 */
class CapturingTrustManager : X509TrustManager {
    @Volatile
    var presentedCert: X509Certificate? = null
        private set

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        if (chain.isNullOrEmpty()) throw javax.net.ssl.SSLException("empty certificate chain")
        presentedCert = chain[0]
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    companion object {
        /** SSL socket factory + trust manager pair that captures the presented cert. */
        fun socketFactory(p: CapturingTrustManager): Pair<SSLSocketFactory, X509TrustManager> {
            val ctx = SSLContext.getInstance("TLS")
                .apply {
                    init(null, arrayOf<TrustManager>(p), null)
                }
            return ctx.socketFactory to p
        }
    }
}