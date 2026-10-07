package com.whitedevil.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AiReviewTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private fun clip(n: String, t: Double, src: String? = null) = MediaClip(n, null, t, 2.0, src)

    // ---- which clips are offered for review

    @Test fun `recent finished clips of one source, newest first, joins before their parts`() {
        val groups = listOf(
            MediaGroup("c", "Best Friends Night V3", "chain", "vast", 30.0, listOf(
                clip("smoke_bestfriends_c01_wanbot.mp4", 10.0), clip("smoke_bestfriends_c02_wanbot.mp4", 20.0), clip("smoke_bestfriends_final.mp4", 30.0))),
            MediaGroup("t", "Friends Room", "chain", "thunder", 5.0, listOf(clip("smoke_friends_c01_14b.mp4", 5.0))),
            MediaGroup("tests", "Tests & experiments", "test", "ltx", 99.0, listOf(
                clip("ltx_chain_a_1fc9ca3b01a7.mp4", 99.0, "ltx"), clip("ltx_chain_a_1fc9ca3b01a7_c01.mp4", 98.0, "ltx"), clip("smoke_ti2v5b_x.mp4", 50.0))),
        )
        // Wan clips end in _wanbot.mp4, so (as on the web page) every one of them counts as finished
        assertEquals(listOf("smoke_bestfriends_final.mp4", "smoke_bestfriends_c02_wanbot.mp4", "smoke_bestfriends_c01_wanbot.mp4"), reviewClips(groups, "vast").map { it.name })
        assertEquals(listOf("smoke_friends_c01_14b.mp4"), reviewClips(groups, "thunder").map { it.name }, "parts are used when there is nothing else")
        assertEquals(listOf("ltx_chain_a_1fc9ca3b01a7.mp4"), reviewClips(groups, "ltx").map { it.name }, "an ltx chain's per-part file (ending _c01.mp4) is left out, and ltx renders are found although the hub filed them under tests")
        assertEquals(emptyList(), reviewClips(groups, "nope"))
        val many = listOf(MediaGroup("m", "Many", "chain", "vast", null, (1..20).map { clip("final_$it.mp4", it.toDouble()) }))
        assertEquals(8, reviewClips(many, "vast").size); assertEquals("final_20.mp4", reviewClips(many, "vast").first().name)
    }

    // ---- which model looks at the picture

    private fun m(id: String, vision: Boolean, offline: Boolean = false) = VeniceModel(id, id.uppercase(), 100_000, true, offline, false, vision)

    @Test fun `your own model is used when it can see images, otherwise a vision model`() {
        val models = listOf(m("zai-org-glm-5-2", false), m("gemini-3-6-flash", true), m("qwen3-vl-235b-a22b", true), m("venice-uncensored-1-2", true))
        assertEquals("gemini-3-6-flash", pickVisionModel("gemini-3-6-flash", models)!!.id, "a vision model you chose is kept")
        assertEquals("qwen3-vl-235b-a22b", pickVisionModel("zai-org-glm-5-2", models)!!.id, "else the preferred vision model")
        assertEquals("venice-uncensored-1-2", pickVisionModel("zai-org-glm-5-2", models.filterNot { it.id.startsWith("qwen3-vl") })!!.id)
        assertEquals("a-vision", pickVisionModel("x", listOf(m("zzz", true), m("a-vision", true), m("offline-vl", true, offline = true), m("text", false)))!!.id, "else the first by name, never an offline one")
        assertNull(pickVisionModel("x", listOf(m("text", false), m("gone", true, offline = true))))
    }

    // ---- the request

    @Test fun `the instruction says what to look at and not to invent`() {
        val p = reviewPrompt(ReviewClip("smoke_bestfriends-night-v3_c01_wanbot.mp4", "Best Friends Night V3", "vast", 1.0, 1.0), "two men on a sofa, slow dolly in")
        assertTrue("bestfriends night v3 · clip 1" in p && "two men on a sofa, slow dolly in" in p && "Best Friends Night V3" in p)
        assertTrue("Do not invent details you cannot see" in p && "left→right, top→bottom" in p && "motion or audio" in p)
        assertTrue("made from this prompt" !in reviewPrompt(ReviewClip("a.mp4", "t", "ltx", null, null), null), "no prompt line when the hub has none")
        assertTrue(reviewPrompt(ReviewClip("a.mp4", "t", "ltx", null, null), "x".repeat(5000)).length < 3000, "a long prompt is cut")
    }

    @Test fun `the request carries the picture the way Venice expects`() {
        val r = reviewRequest("gemini-3-6-flash", "look", "data:image/jpeg;base64,AAAA")
        assertEquals("gemini-3-6-flash", (r["model"] as JsonPrimitive).content)
        val content = (((r["messages"] as JsonArray)[0] as JsonObject)["content"] as JsonArray)
        assertEquals("text", ((content[0] as JsonObject)["type"] as JsonPrimitive).content)
        assertEquals("image_url", ((content[1] as JsonObject)["type"] as JsonPrimitive).content)
        assertEquals("data:image/jpeg;base64,AAAA", ((((content[1] as JsonObject)["image_url"]) as JsonObject)["url"] as JsonPrimitive).content)
    }

    @Test fun `the reply is read whether it is a string or a list of parts`() {
        assertEquals("Good.", reviewReply(Json.parseToJsonElement("""{"choices":[{"message":{"content":"  Good. "}}]}""")))
        assertEquals("a\nb", reviewReply(Json.parseToJsonElement("""{"choices":[{"message":{"content":[{"type":"text","text":"a"},{"type":"text","text":"b"}]}}]}""")))
        assertNull(reviewReply(Json.parseToJsonElement("""{"choices":[{"message":{"content":""}}]}""")))
        assertNull(reviewReply(Json.parseToJsonElement("""{"choices":[]}""")))
        assertNull(reviewReply(Json.parseToJsonElement("[]")))
    }

    @Test fun `the review call sends the key and reports failures in words`() = runBlocking {
        var auth: String? = null; var path = ""; var body = ""
        val ok = reviewWithVision("sk-test", "m", "t", "data:image/jpeg;base64,AA", MockEngine { req ->
            auth = req.headers[HttpHeaders.Authorization]; path = req.url.encodedPath; body = String(req.body.toByteArray())
            respond("""{"choices":[{"message":{"content":"Looks consistent."}}]}""", HttpStatusCode.OK, json)
        })
        assertEquals("Looks consistent.", (ok as MediaResult.Ok).value); assertEquals("Bearer sk-test", auth); assertEquals("/api/v1/chat/completions", path)
        assertTrue("image_url" in body)
        assertEquals("Venice rejected the API key. Check it in Settings.", ((reviewWithVision("bad", "m", "t", "d", MockEngine { respond("{}", HttpStatusCode.Unauthorized, json) })) as MediaResult.Failure).error.message)
        assertEquals("This model cannot read images", ((reviewWithVision("k", "m", "t", "d", MockEngine { respond("""{"error":{"message":"This model cannot read images"}}""", HttpStatusCode.BadRequest, json) })) as MediaResult.Failure).error.message)
        assertTrue(((reviewWithVision("k", "m", "t", "d", MockEngine { respond("""{"choices":[{"message":{"content":""}}]}""", HttpStatusCode.OK, json) })) as MediaResult.Failure).error.message.contains("no review text"))
        var calls = 0
        assertTrue(reviewWithVision("  ", "m", "t", "d", MockEngine { calls++; respond("{}", HttpStatusCode.OK, json) }) is MediaResult.Failure); assertEquals(0, calls, "no key, no request")
    }

    // ---- the card, on a real scene against a fake hub

    private fun jpeg(): ByteArray = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(160, 90, BufferedImage.TYPE_INT_RGB), "jpg", it) }.toByteArray()

    @OptIn(ExperimentalTestApi::class)
    @Test fun `the video review card shows the latest clip and runs an AI review from the button`() = runComposeUiTest {
        val library = """[{"id":"c","title":"Best Friends Night V3","kind":"chain","source":"vast","updated":30,"count":2,"clips":[
            {"name":"smoke_bestfriends_final.mp4","idx":null,"mtime":30,"mb":3.0,"source":"vast"},{"name":"smoke_bestfriends_c01_wanbot.mp4","idx":1,"mtime":20,"mb":1.0,"source":"vast"}]}]"""
        val engine = MockEngine { req ->
            when {
                req.url.encodedPath == "/api/media/library" -> respond(library, HttpStatusCode.OK, json)
                req.url.encodedPath.startsWith("/api/media/") -> respond(jpeg(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "image/jpeg"))
                else -> respond("{}", HttpStatusCode.NotFound, json)
            }
        }
        val media = MediaClient("https://hub.example", "u", "p", engine)
        setContent {
            MaterialTheme(colorScheme = WhiteDevilColors) {
                val a = rememberClipActions(media)
                // no Venice key in these settings, so the review button must answer with the reason, not hang or crash
                VideoReviewCard(Settings(veniceApiKey = ""), media, a, "vast")
            }
        }
        waitUntil(timeoutMillis = 8_000) { onAllNodesWithText("2 recent", substring = true).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(onAllNodesWithText("selected: bestfriends final", substring = true).fetchSemanticsNodes().isNotEmpty(), "the finished join is the one selected")
        onNodeWithText("✦ AI REVIEW").performClick()
        waitUntil(timeoutMillis = 8_000) { onAllNodesWithText("No Venice API key is set. Add it in Settings to use AI review.").fetchSemanticsNodes().isNotEmpty() }
    }
}
