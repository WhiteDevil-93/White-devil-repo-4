package com.whitedevil.relay

import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

object RelayHttp {
    fun get(baseUrl: String, authorization: String, path: String): String =
        request(baseUrl, authorization, path, "GET", null)

    fun post(baseUrl: String, authorization: String, path: String, jsonBody: String): String =
        request(baseUrl, authorization, path, "POST", jsonBody)

    /** PUT or DELETE (HttpURLConnection cannot send PATCH; the hub offers POST aliases for those). */
    fun send(baseUrl: String, authorization: String, path: String, method: String, jsonBody: String? = null): String =
        request(baseUrl, authorization, path, method, jsonBody)

    /**
     * One file as multipart/form-data field [field], streamed in chunks (never held in memory, so multi-GB clips are
     * fine). [onProgress] gets the bytes sent so far. The read timeout is long because the hub converts the file
     * (ffmpeg) before it answers.
     */
    fun upload(
        baseUrl: String,
        authorization: String,
        path: String,
        field: String,
        fileName: String,
        mime: String,
        input: InputStream,
        onProgress: (Long) -> Unit = {},
    ): String {
        val boundary = "----forge" + System.nanoTime()
        val safeName = fileName.replace("\"", "").replace("\r", "").replace("\n", "")
        val head = ("--$boundary\r\nContent-Disposition: form-data; name=\"$field\"; filename=\"$safeName\"\r\n" +
            "Content-Type: $mime\r\n\r\n").toByteArray()
        val tail = "\r\n--$boundary--\r\n".toByteArray()
        val conn = (URL("$baseUrl$path").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 30 * 60_000
            doOutput = true
            setChunkedStreamingMode(256 * 1024)
            setRequestProperty("Authorization", authorization)
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        conn.outputStream.use { out ->
            out.write(head)
            val buf = ByteArray(256 * 1024)
            var sent = 0L
            input.use { inp ->
                while (true) {
                    val n = inp.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    sent += n
                    onProgress(sent)
                }
            }
            out.write(tail)
        }
        return finish(conn)
    }

    private fun request(
        baseUrl: String,
        authorization: String,
        path: String,
        method: String,
        body: String?,
    ): String {
        val conn = (URL("$baseUrl$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Authorization", authorization)
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        if (body != null) {
            conn.outputStream.use { it.write(body.toByteArray()) }
        }
        return finish(conn)
    }

    private fun finish(conn: HttpURLConnection): String {
        val code = conn.responseCode
        // Closing the stream is what returns the socket to the pool; leaving it open
        // leaks a descriptor per request in a polling client.
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.use { it.bufferedReader().readText() }.orEmpty()
        if (code !in 200..299) {
            throw RelayHttpException(code, hubDetail(text) ?: text.ifBlank { "HTTP $code" })
        }
        return text
    }

    /** FastAPI's {"detail": "..."} when there is one, so the hub's own wording reaches the screen. */
    fun hubDetail(body: String): String? =
        Regex("\"detail\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(body)?.groupValues?.get(1)
            ?.replace("\\\"", "\"")?.replace("\\n", " ")
}

class RelayHttpException(val code: Int, message: String) : Exception(message)
