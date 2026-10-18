package app.maestri.wire

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.Request

/**
 * Pinned binary GETs for the host's image routes with strong-ETag revalidation —
 * the portal card snapshot (`GET /api/portals/{portalId}/snapshot`, corpus §5ter +
 * docs §227: 200 image/jpeg + ETag, 304 on If-None-Match, 404 while the portal has no
 * live image incl. under unload) and the partitura preview
 * (`GET /api/partituras/{id}/preview?appearance=`, corpus §2).
 *
 * The cache discipline is the route's: a cached copy serves immediately, and a request
 * that does go out always carries If-None-Match so a stored ETag is consumed, never
 * refetched blindly. 304 keeps the stored bytes and refreshes the age stamp.
 */
object SnapshotMedia {

    data class Cached(val bytes: ByteArray, val etag: String?, val atMillis: Long)

    private val lock = Any()
    private val cache = HashMap<String, Cached>()
    private const val MAX_ENTRIES = 32

    sealed class Result {
        /** Fresh bytes — [fromCache] when served from the store (a young copy or a 304). */
        data class Fresh(val bytes: ByteArray, val fromCache: Boolean) : Result()

        /** 404 — no live image right now (unloaded portal / no preview). */
        data object Unavailable : Result()

        data class Failure(val message: String) : Result()
    }

    /** Bytes now: a young cached copy is served as-is, else an If-None-Match revalidation.
     *  [query] rides via addQueryParameter — addPathSegments would percent-encode a '?',
     *  so structured params go through this instead of the path string. */
    suspend fun fetch(
        session: app.maestri.wire.session.WireSession,
        path: String,
        cacheKey: String,
        query: Map<String, String>? = null,
        revalidateAfterMillis: Long = 30_000L,
    ): Result = withContext(Dispatchers.IO) {
        val cached: Cached? = synchronized(lock) { cache[cacheKey] }
        if (cached != null && System.currentTimeMillis() - cached.atMillis < revalidateAfterMillis) {
            return@withContext Result.Fresh(cached.bytes, fromCache = true)
        }

        val p = session.pairing.value ?: return@withContext Result.Failure("not paired")
        try {
            val url = HttpUrl.Builder().scheme("https").host(p.host).port(p.port)
                .addPathSegments(path.trimStart('/'))
                .apply { query?.forEach { (k, v) -> addQueryParameter(k, v) } }
                .build()
            val builder = Request.Builder().url(url)
                .header("Authorization", "Bearer ${p.token}")
            if (cached != null && cached.etag != null) builder.header("If-None-Match", cached.etag)
            session.client.pinned(p.serverKeyHashB64).newCall(builder.get().build()).execute().use { resp ->
                when {
                    resp.code == 304 -> {
                        val c = cached ?: return@use Result.Failure("HTTP 304 without a cached copy")
                        synchronized(lock) {
                            cache[cacheKey] = Cached(c.bytes, c.etag, System.currentTimeMillis())
                        }
                        Result.Fresh(c.bytes, fromCache = true)
                    }

                    resp.isSuccessful -> {
                        val bytes = resp.body?.bytes()
                            ?: return@use Result.Failure("HTTP ${resp.code} with an empty body")
                        val etag = resp.header("ETag")
                        synchronized(lock) {
                            cache[cacheKey] = Cached(bytes, etag, System.currentTimeMillis())
                            if (cache.size > MAX_ENTRIES) {
                                val eldest = cache.entries.minByOrNull { it.value.atMillis }
                                if (eldest != null) cache.remove(eldest.key)
                            }
                        }
                        Result.Fresh(bytes, fromCache = false)
                    }

                    resp.code == 404 -> Result.Unavailable

                    resp.code == 401 -> {
                        session.onAuthLost()
                        Result.Failure("HTTP 401")
                    }

                    else -> {
                        val text = runCatching { resp.body?.string() }.getOrNull()?.take(160)
                        Result.Failure("HTTP ${resp.code}: ${text ?: "no body"}")
                    }
                }
            }
        } catch (e: Exception) {
            Result.Failure(e.message ?: "network error")
        }
    }
}