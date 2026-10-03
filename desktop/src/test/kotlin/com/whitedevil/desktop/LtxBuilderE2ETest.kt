package com.whitedevil.desktop

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End to end against a real hub process: the app's real client, real multipart, the hub's real FastAPI
 * parsing and validation. Only runs when LTX_E2E_URL points at a stub hub (ComfyUI and the render watcher
 * replaced, state in a temp folder), so a normal build skips it and nothing is ever spent.
 */
class LtxBuilderE2ETest {
    private val url: String? = System.getenv("LTX_E2E_URL")
    private fun client() = LtxBuilderClient(url!!, "anon3", "irrelevant")
    private fun png(): ByteArray = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray()
    private fun submitted(): String = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI("$url/__stub/submitted")).build(), HttpResponse.BodyHandlers.ofString()).body()
    private fun <T> ok(r: MediaResult<T>): T = (r as? MediaResult.Ok)?.value ?: error("hub refused: ${(r as MediaResult.Failure).error.message} (HTTP ${r.error.status})")
    private fun failureOf(r: MediaResult<*>): String = (r as? MediaResult.Failure)?.error?.message ?: error("expected a failure, got $r")

    @Test fun `the native builder against the real hub code`() = runBlocking {
        assumeTrue(url != null, "set LTX_E2E_URL to a stub hub to run this")
        val c = client()

        // status: ComfyUI is not there in the stub, so the hub says offline, and the client must read that
        val st = ok(c.status())
        assertTrue(!st.online, "stub has no ComfyUI")

        // one fresh clip with a picture -> /render, accepted, and the hub saw the picture
        val one = (c.submit(BuildRequest("he looks at the camera and smiles slowly", picture = BuilderPicture("start.png", png()), frames = 97, size = "portrait", seed = 5)) as MediaResult.Ok).value
        assertTrue(submitted().contains("\"has_image\":true") && submitted().contains("\"frames\":97") && submitted().contains("portrait"), submitted())

        // a chain, text-to-video, plain words
        val chain = ok(c.submit(BuildRequest("two men kiss on a sofa", parts = 2)))

        // THE BUG that was reported: text-to-video with a director plan and no picture
        val plan = "GLOBAL CONTINUITY\nTwo men on a sofa, stable camera.\n\nCLIP 1\nSTART STATE: they sit.\nACTION: one leans in.\nEND STATE: close.\n\nCLIP 2\nSTART STATE: close.\nACTION: they kiss.\nEND STATE: embracing."
        val planned = ok(c.submit(BuildRequest(plan, parts = 2)))

        // continue from a finished clip, with no text
        val cont = ok(c.submit(BuildRequest("", parts = 2, continueFrom = "a1b2c3d4e5f6")))

        // all of them show up in the job list, read correctly
        val jobs = ok(c.jobs()).associateBy { it.id }
        assertEquals("queued", jobs.getValue(one).status); assertEquals(97, jobs.getValue(one).frames); assertEquals("portrait", jobs.getValue(one).size); assertEquals(5L, jobs.getValue(one).seed)
        assertEquals(2, jobs.getValue(chain).partsTotal); assertTrue(jobs.getValue(chain).isChain); assertTrue(jobs.getValue(chain).textToVideo)
        assertEquals(2, jobs.getValue(planned).partsTotal); assertTrue(jobs.getValue(planned).textToVideo)
        assertEquals("a1b2c3d4e5f6", jobs.getValue(cont).fromJob)
        assertTrue(jobs.getValue("a1b2c3d4e5f6").done)

        // the hub's own refusals reach the user in the hub's words
        assertTrue(failureOf(c.submit(BuildRequest("line one\nline two", parts = 3))).contains("You wrote 2 lines for 3 parts"))
        assertTrue(failureOf(c.submit(BuildRequest(plan, parts = 5))).contains("The plan has 2 clips and this run is set to 5"))
        assertTrue(failureOf(c.submit(BuildRequest("", parts = 2, continueFrom = "nosuchjob1234"))).isNotBlank())

        // cancelling something that is not running is a readable failure, not a crash
        assertTrue(failureOf(c.cancel("a1b2c3d4e5f6")).isNotBlank())

        // the prompt writer request is shaped right: the stub has no OpenRouter key, and says exactly that
        assertTrue(failureOf(c.assist(AssistRequest("rough words", 49, 1, null, null, "x-ai/grok-4.5"))).contains("OpenRouter"))
        c.close()
    }
}
