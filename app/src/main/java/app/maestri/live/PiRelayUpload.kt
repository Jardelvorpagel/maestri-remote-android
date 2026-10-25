package app.maestri.live

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Upload rejection: message plus the server's stated size cap, when given. */
class UploadRejected(message: String, val sizeCapBytes: Long?) : Exception(message)

/**
 * Relay attachment upload: POST `http(s)://same-host:port/upload`, the file's raw bytes as the
 * body plus an `X-Filename` header (the relay writes the body verbatim), same auth gate as the
 * socket (blank token → no `Authorization` header). Call [upload] off the main thread.
 * Logs carry filename + byte count only — never contents, never the token.
 */
class PiRelayUpload(
    private val okHttp: OkHttpClient = OkHttpClient(),
) {
    suspend fun upload(
        relayUrl: String,
        token: String,
        filename: String,
        bytes: ByteArray,
        terminalId: String = "",
    ): Attachment = withContext(Dispatchers.IO) {
        val url = uploadUrlFor(relayUrl, terminalId)
        relayLog("PiRelayUpload", "uploading name=$filename bytes=${bytes.size} url=$url")
        val response = try {
            okHttp.newCall(uploadRequest(url, token, filename, bytes)).execute()
        } catch (e: Exception) {
            relayLog("PiRelayUpload", "upload failed name=$filename ${e.javaClass.simpleName}: ${e.message}")
            throw UploadRejected("${e.javaClass.simpleName}: ${e.message}", null)
        }
        response.use {
            val bodyText = it.body?.string()
            if (!it.isSuccessful) throw parseUploadRejection(it.code, bodyText)
            return@withContext parseUploadResponse(bodyText ?: "")
                ?: throw UploadRejected("bad upload response (HTTP ${it.code})", null)
        }
    }
}

/** Upload POST builder: the file's raw bytes, `X-Filename`, optional Bearer. */
fun uploadRequest(uploadUrl: String, token: String, filename: String, bytes: ByteArray): Request {
    val builder = Request.Builder()
        .url(uploadUrl)
        .header("X-Filename", filename)
        .post(bytes.toRequestBody("application/octet-stream".toMediaType()))
    if (token.isNotBlank()) builder.header("Authorization", "Bearer $token")
    return builder.build()
}

/** 2xx upload body `{id,name,bytes,path}`. */
fun parseUploadResponse(json: String): Attachment? {
    val root = MiniJson.parse(json) as? Map<*, *> ?: return null
    return parseAttachment(root)
}

/**
 * Rejection from a non-2xx upload: message from `{error|message}`, size cap
 * from `{maxBytes|sizeCap|cap|sizeLimit|limit}` when the server states one.
 */
fun parseUploadRejection(statusCode: Int, body: String?): UploadRejected {
    val root = body?.let { MiniJson.parse(it) as? Map<*, *> }
    val message = (root?.get("error") as? String)
        ?: (root?.get("message") as? String)
        ?: "upload failed (HTTP $statusCode)"
    val cap = root?.let {
        anyUploadLong(it["maxBytes"])
            ?: anyUploadLong(it["sizeCap"])
            ?: anyUploadLong(it["cap"])
            ?: anyUploadLong(it["sizeLimit"])
            ?: anyUploadLong(it["limit"])
    }
    return UploadRejected(message, cap)
}

private fun anyUploadLong(v: Any?): Long? = when (v) {
    is Long -> v
    is Number -> v.toLong()
    is String -> v.toLongOrNull()
    else -> null
}
