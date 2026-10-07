package com.whitedevil.desktop

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClipDownloadTest {
    private val video = headersOf(HttpHeaders.ContentType, "video/mp4")
    private fun client(engine: MockEngine) = MediaClient("https://hub.example", "wan", "pw", engine)
    private fun tmp(): Path = Files.createTempDirectory("clipdl")

    @Test fun `a clip is streamed from the clips route with relay auth and written whole`() = runBlocking {
        var path = ""; var auth: String? = null
        val engine = MockEngine { req -> path = req.url.encodedPath; auth = req.headers[HttpHeaders.Authorization]; respond(ByteArray(5000) { it.toByte() }, HttpStatusCode.OK, video) }
        val dest = tmp().resolve("a b#1.mp4")
        val r = client(engine).downloadClip("a b#1.mp4", dest)
        assertTrue(r is MediaResult.Ok, "$r")
        assertEquals("/clips/a%20b%231.mp4", path)
        assertTrue(auth!!.startsWith("Basic "))
        assertEquals(5000L, Files.size(dest))
        assertFalse(Files.exists(dest.resolveSibling("a b#1.mp4.part")), "no .part left behind")
    }

    @Test fun `an error status writes no file and leaves nothing behind`() = runBlocking {
        val dest = tmp().resolve("x.mp4")
        val r = client(MockEngine { respond("nope", HttpStatusCode.NotFound) }).downloadClip("x.mp4", dest)
        assertTrue(r is MediaResult.Failure)
        assertFalse(Files.exists(dest)); assertFalse(Files.exists(dest.resolveSibling("x.mp4.part")))
    }

    @Test fun `a login page or json answer is not saved as a video`() = runBlocking {
        val dest = tmp().resolve("x.mp4")
        val html = headersOf(HttpHeaders.ContentType, "text/html; charset=utf-8")
        val r = client(MockEngine { respond("<html>sign in</html>", HttpStatusCode.OK, html) }).downloadClip("x.mp4", dest)
        assertTrue(r is MediaResult.Failure)
        assertFalse(Files.exists(dest))
    }

    @Test fun `an empty body is a failure not a zero byte clip`() = runBlocking {
        val dest = tmp().resolve("x.mp4")
        val r = client(MockEngine { respond(ByteArray(0), HttpStatusCode.OK, video) }).downloadClip("x.mp4", dest)
        assertTrue(r is MediaResult.Failure)
        assertFalse(Files.exists(dest)); assertFalse(Files.exists(dest.resolveSibling("x.mp4.part")))
    }

    @Test fun `file names from the hub cannot escape the folder`() {
        assertEquals("clip.mp4", safeFileName("clip.mp4"))
        assertEquals(".._.._evil.mp4", safeFileName("../../evil.mp4"))
        assertEquals("C__Windows_x.mp4", safeFileName("C:" + 92.toChar() + "Windows" + 92.toChar() + "x.mp4"))
        assertEquals("a_b_c.mp4", safeFileName("a?b*c.mp4"))
        assertNull(safeFileName(".."))
        assertNull(safeFileName("..."))
        assertNull(safeFileName("   "))
        assertNull(safeFileName(""))
        val base = Path.of("C:/cache")
        assertTrue(base.resolve(safeFileName("../../evil.mp4")!!).normalize().startsWith(base))
    }
}
