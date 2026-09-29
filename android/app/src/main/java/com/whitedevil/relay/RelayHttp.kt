package com.whitedevil.relay

import java.net.HttpURLConnection
import java.net.URL

object RelayHttp {
    fun get(baseUrl: String, authorization: String, path: String): String =
        request(baseUrl, authorization, path, "GET", null)

    fun post(baseUrl: String, authorization: String, path: String, jsonBody: String): String =
        request(baseUrl, authorization, path, "POST", jsonBody)

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
        val code = conn.responseCode
        // Closing the stream is what returns the socket to the pool; leaving it open
        // leaks a descriptor per request in a polling client.
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.use { it.bufferedReader().readText() }.orEmpty()
        if (code !in 200..299) {
            throw RelayHttpException(code, text.ifBlank { "HTTP $code" })
        }
        return text
    }
}

class RelayHttpException(val code: Int, message: String) : Exception(message)
