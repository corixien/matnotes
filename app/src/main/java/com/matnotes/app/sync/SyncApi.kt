package com.matnotes.app.sync

import com.matnotes.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** An error answer of the server (4xx/5xx). [serverVersion] is set for 409 conflicts on documents. */
class ApiException(val status: Int, val code: String, message: String, val serverVersion: Int? = null, val deleted: Boolean = false) : IOException(message)

/** Plain HTTP client for the sync server (no dependency). Binary document bodies are already encrypted. */
object SyncApi {
    var baseUrl: String = BuildConfig.SYNC_URL

    class Response(val status: Int, val body: ByteArray, val headers: Map<String, String>)

    private fun open(method: String, path: String, token: String?, headers: Map<String, String>, contentType: String?, body: ByteArray?): Response {
        val c = URL(baseUrl + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 30_000 // a sleeping free-tier server needs up to a minute to wake up
            c.readTimeout = 90_000
            c.setRequestProperty("Accept", "application/json, application/octet-stream")
            if (token != null) c.setRequestProperty("Authorization", "Bearer $token")
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", contentType ?: "application/json")
                c.setFixedLengthStreamingMode(body.size)
                c.outputStream.use { it.write(body) }
            }
            val status = c.responseCode
            val stream = if (status in 200..399) c.inputStream else c.errorStream
            val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
            val h = c.headerFields.filterKeys { it != null }.mapKeys { it.key.lowercase() }.mapValues { it.value.firstOrNull().orEmpty() }
            return Response(status, bytes, h)
        } finally {
            c.disconnect()
        }
    }

    private fun check(r: Response): Response {
        if (r.status in 200..399) return r
        val j = runCatching { JSONObject(String(r.body)) }.getOrNull()
        throw ApiException(
            r.status,
            j?.optString("error").takeUnless { it.isNullOrEmpty() } ?: "http_${r.status}",
            j?.optString("message").takeUnless { it.isNullOrEmpty() } ?: "HTTP ${r.status}",
            j?.takeIf { it.has("serverVersion") && !it.isNull("serverVersion") }?.getInt("serverVersion"),
            j?.optBoolean("deleted") ?: false,
        )
    }

    /** JSON request. [token] is the access token; callers that need automatic refresh go through [Account.call]. */
    suspend fun json(method: String, path: String, body: JSONObject? = null, token: String? = null, headers: Map<String, String> = emptyMap()): JSONObject = withContext(Dispatchers.IO) {
        val r = check(open(method, path, token, headers, "application/json", body?.toString()?.toByteArray()))
        if (r.body.isEmpty()) JSONObject() else JSONObject(String(r.body))
    }

    suspend fun bytes(method: String, path: String, token: String, headers: Map<String, String> = emptyMap(), body: ByteArray? = null): Response = withContext(Dispatchers.IO) {
        check(open(method, path, token, headers, "application/octet-stream", body))
    }
}
