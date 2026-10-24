package app.maestri.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * A published build, as described by the `latest.json` manifest of the public releases.
 *
 * [sha256] is the digest of the APK the manifest points at: the app refuses to install
 * anything that does not match it.
 */
data class ReleaseInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val size: Long,
    val publishedAt: String,
    val notes: String,
)

/**
 * A published build is only interesting when its code is GREATER than the running one —
 * the app compares codes, and a re-published build keeps its code.
 */
fun isNewer(publishedVersionCode: Int, installedVersionCode: Int): Boolean =
    publishedVersionCode > installedVersionCode

/**
 * Where to look for the manifest, in order: the relay on the user's own tailnet first —
 * a device on Tailscale reaches its paired host whether or not it has a working route to
 * the public internet — then the public releases URL. [pairedHost] is the host the app is
 * paired with; blank means no tailnet candidate.
 */
fun manifestUrls(
    githubUrl: String,
    pairedHost: String?,
    tailnetHost: String? = TAILNET_HOST,
): List<String> {
    val hosts = listOfNotNull(pairedHost, tailnetHost)
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
    return hosts.map { "http://$it:$TAILNET_RELAY_PORT/release/latest.json" } + githubUrl
}

/** The relay runs on this port on the host (see pi-relay's /release routes). */
const val TAILNET_RELAY_PORT = 7487

/**
 * The Mac's own Tailscale address: the one host a device paired over a LAN still reaches
 * once it leaves the house, and the only cleartext exception the app ships with.
 */
const val TAILNET_HOST = "100.95.36.90"

/** The manifest digest and the digest of the downloaded bytes must be the same one. */
fun digestMatches(expected: String, actual: String): Boolean =
    actual.trim().equals(expected.trim(), ignoreCase = true)

/**
 * Fetcher for the public release manifest and for the APK it points at.
 *
 * The endpoint is a public GitHub Releases URL: no token, no pairing, no relay — and
 * therefore the plain OkHttp client, never the SPKI-pinned one (that pin belongs to the
 * user's own host, not to GitHub).
 */
class UpdateClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .build(),
    /** Candidate manifests, evaluated per check so a pairing made later is honored. */
    private val manifests: () -> List<String> = { listOf(LATEST_URL) },
) {

    /**
     * The published manifest, or null when it is missing, unreadable or offline.
     * A null is never an error the user sees: the check fails silently.
     */
    suspend fun latest(): ReleaseInfo? = withContext(Dispatchers.IO) {
        for (url in manifests().distinct()) {
            val info = fetch(url)
            if (info != null) return@withContext info
        }
        null
    }

    /** One manifest URL. A failure here is a log line, never a user-facing error. */
    private suspend fun fetch(url: String): ReleaseInfo? = runCatching {
        client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) return@use null
            val json = JSONObject(resp.body.string())
            ReleaseInfo(
                versionCode = json.getInt("versionCode"),
                versionName = json.optString("versionName"),
                apkUrl = json.getString("apkUrl"),
                sha256 = json.getString("sha256"),
                size = json.optLong("size"),
                publishedAt = json.optString("publishedAt"),
                notes = json.optString("notes"),
            )
        }
    }.onFailure {
        Log.d("QAUpdate", "manifest fetch failed ($url): ${it.javaClass.simpleName}: ${it.message}")
    }.getOrNull()

    /**
     * Downloads [release] into [target] and checks it against the manifest digest.
     *
     * Returns false on any failure (network, short write, digest mismatch); the partial
     * file is deleted in that case — an APK that does not match the manifest never
     * reaches the installer.
     */
    suspend fun download(release: ReleaseInfo, target: File): Boolean = withContext(Dispatchers.IO) {
        val ok = runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            client.newCall(Request.Builder().url(release.apkUrl).build()).execute().use { resp ->
                if (!resp.isSuccessful) return@use false
                val body = resp.body
                body.byteStream().use { input ->
                    FileOutputStream(target).use { out ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            digest.update(buffer, 0, read)
                            out.write(buffer, 0, read)
                        }
                    }
                }
                digest.digest().joinToString("") { "%02x".format(it) }
                    .let { digestMatches(release.sha256, it) }
            }
        }.getOrElse { false }
        if (!ok) target.delete()
        ok
    }

    /**
     * Digests a file already on disk and compares it with the manifest digest.
     * A cached APK is only reused when it still matches.
     */
    suspend fun verify(file: File, expectedSha256: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
                .let { digestMatches(expectedSha256, it) }
        }.getOrElse { false }
    }

    companion object {
        /** Stable URL of the newest published manifest (redirects to the latest release). */
        const val LATEST_URL =
            "https://github.com/Jardelvorpagel/maestri-remote-android/releases/latest/download/latest.json"
    }
}
