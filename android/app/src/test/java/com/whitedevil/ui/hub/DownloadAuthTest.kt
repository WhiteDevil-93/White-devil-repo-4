package com.whitedevil.ui.hub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * The relay password is attached to downloads the WebView hands to the DownloadManager. The
 * property that matters is that it only ever goes to the relay's own https host: a page can link
 * anywhere, and a wrong answer here leaks the password to whatever host a link names.
 */
class DownloadAuthTest {

    private val relay = "84-12-112-249.sslip.io"
    private fun headers(url: String, user: String = "wan", pass: String = "s3cret") =
        relayDownloadHeaders(url, relay, user, pass)

    @Test
    fun `a video on the relay gets the basic auth header`() {
        val h = headers("https://$relay/clips/ltx_chain_x.mp4")
        val expected = "Basic " + Base64.getEncoder().encodeToString("wan:s3cret".toByteArray())
        assertEquals(mapOf("Authorization" to expected), h)
    }

    @Test
    fun `the host match ignores case`() {
        assertTrue(headers("https://${relay.uppercase()}/clips/a.mp4").isNotEmpty())
    }

    @Test
    fun `a password with non ascii characters is encoded as utf 8`() {
        val h = headers("https://$relay/clips/a.mp4", pass = "pässwörd")
        val decoded = String(Base64.getDecoder().decode(h.getValue("Authorization").removePrefix("Basic ")), Charsets.UTF_8)
        assertEquals("wan:pässwörd", decoded)
    }

    @Test
    fun `another host never gets the password`() {
        assertTrue(headers("https://example.com/clips/a.mp4").isEmpty())
    }

    @Test
    fun `a lookalike host never gets the password`() {
        assertTrue(headers("https://$relay.evil.example/clips/a.mp4").isEmpty())
        assertTrue(headers("https://evil-$relay/clips/a.mp4").isEmpty())
        assertTrue(headers("https://comfy.$relay/clips/a.mp4").isEmpty())
    }

    @Test
    fun `a link that puts the relay in the userinfo part never gets the password`() {
        assertTrue(headers("https://$relay@evil.example/clips/a.mp4").isEmpty())
        assertTrue(headers("https://$relay:80@evil.example/clips/a.mp4").isEmpty())
    }

    @Test
    fun `plain http never gets the password even for the relay host`() {
        assertTrue(headers("http://$relay/clips/a.mp4").isEmpty())
    }

    @Test
    fun `non web schemes never get the password`() {
        assertTrue(headers("file:///sdcard/a.mp4").isEmpty())
        assertTrue(headers("ftp://$relay/a.mp4").isEmpty())
        assertTrue(headers("data:video/mp4;base64,AAAA").isEmpty())
    }

    @Test
    fun `garbage and empty urls never get the password`() {
        assertTrue(headers("").isEmpty())
        assertTrue(headers("not a url at all").isEmpty())
        assertTrue(headers("https://").isEmpty())
    }

    @Test
    fun `missing credentials or relay host send nothing`() {
        assertTrue(headers("https://$relay/a.mp4", user = "").isEmpty())
        assertTrue(headers("https://$relay/a.mp4", pass = "").isEmpty())
        assertTrue(relayDownloadHeaders("https://$relay/a.mp4", "", "wan", "s3cret").isEmpty())
    }
}
