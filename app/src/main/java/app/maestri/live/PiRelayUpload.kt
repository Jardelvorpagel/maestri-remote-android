package app.maestri.live

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Upload rejection: message plus the server's stated size cap, when given. */
class UploadRejected(message: String, val sizeCapBytes: Long?) : Exception(message)

/**
 * Relay attachment upload: POST `http(s)://same-host:port/upload`, multipart
 * `file` part plus an `X-Filename` header, same auth gate as the socket
 * (blank token → no `Authorization` header). Call [upload] off the main thread.
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
    ): Attachment = withContext(Dispatchers.IO) {
        val url = uploadUrlFor(relayUrl)
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

/** Upload POST builder: multipart file part, `X-Filename`, optional Bearer. */
fun uploadRequest(uploadUrl: String, token: String, filename: String, bytes: ByteArray): Request {
    val body = MultipartBody.Builder().setType(MultipartBody.FORM)
        .addFormDataPart("file", filename, bytes.toRequestBody("application/octet-stream".toMediaType()))
        .build()
    val builder = Request.Builder()
        .url(uploadUrl)
        .header("X-Filename", filename)
        .post(body)
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
