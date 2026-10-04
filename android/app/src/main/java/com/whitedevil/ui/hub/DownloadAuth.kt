package com.whitedevil.ui.hub

import java.net.URI
import java.util.Base64

/**
 * Headers for a download that a page's WebView handed off to the system DownloadManager.
 *
 * The WebView answers the relay's basic-auth challenge on its own, but the DownloadManager
 * fetches the file in a different process that knows nothing about that, so the credentials
 * have to be spelled out on the request. Without them every download from a relay page is
 * refused with a 401 (or, with no download listener at all, silently does nothing).
 *
 * Credentials go to the relay's own host over https and nowhere else. A page can link to any
 * address, and sending the relay password to whatever host a link names would hand it over, so
 * lookalike hosts, plain http, and `https://relay@evil.example/` style links all get no header.
 */
internal fun relayDownloadHeaders(url: String, relayHost: String, user: String, pass: String): Map<String, String> {
    if (relayHost.isBlank() || user.isBlank() || pass.isEmpty()) return emptyMap()
    val uri = runCatching { URI(url) }.getOrNull() ?: return emptyMap()
    if (!"https".equals(uri.scheme, ignoreCase = true)) return emptyMap()
    // URI.host is null for anything it cannot parse as a host, and it is the real host (not the
    // userinfo) for `https://relay@evil.example/`, which is exactly the case to refuse.
    val host = uri.host ?: return emptyMap()
    if (!relayHost.equals(host, ignoreCase = true)) return emptyMap()
    val token = Base64.getEncoder().encodeToString("$user:$pass".toByteArray(Charsets.UTF_8))
    return mapOf("Authorization" to "Basic $token")
}
