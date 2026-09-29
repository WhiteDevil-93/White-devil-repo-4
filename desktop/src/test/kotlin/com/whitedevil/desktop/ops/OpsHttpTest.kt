package com.whitedevil.desktop.ops

import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.put
import java.net.ConnectException
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpsHttpTest {

    @Test
    fun `every non-2xx status maps to an Err that names the status - never an empty success`() = runTest {
        for (code in listOf(301, 302, 400, 401, 403, 404, 409, 418, 422, 429, 500, 502, 503, 504)) {
            val hub = FakeHub("", HttpStatusCode.fromValue(code))
            val err = hub.reader.getJson("/api/x").errValue()
            assertEquals(code, err.status, "status for $code")
            assertTrue(err.message.contains(code.toString()), "message for $code should name it: ${err.message}")
            assertTrue(err.message.isNotBlank())
        }
    }

    @Test
    fun `401 with an empty body blames the relay sign-in and points at Settings`() = runTest {
        val err = FakeHub("", HttpStatusCode.Unauthorized).reader.getJson("/api/colab/state").errValue()
        assertEquals(OpsErrorKind.Auth, err.kind)
        assertEquals(401, err.status)
        assertTrue(err.message.contains("Settings"), err.message)
    }

    @Test
    fun `401 with a hub detail shows the hub's own words`() = runTest {
        val err = FakeHub("""{"detail":"Thunder rejected the token; generate a new one in the console."}""", HttpStatusCode.Unauthorized)
            .reader.getJson("/api/thunder/state").errValue()
        assertTrue(err.message.contains("Thunder rejected the token"), err.message)
        assertEquals(401, err.status)
    }

    @Test
    fun `500 with FastAPI detail surfaces the detail and keeps the body`() = runTest {
        val err = FakeHub("""{"detail":"No Thunder token on the relay (~/.thunder_token)."}""", HttpStatusCode.InternalServerError)
            .reader.getJson("/api/thunder/state").errValue()
        assertEquals(500, err.status)
        assertTrue(err.message.contains("No Thunder token on the relay"), err.message)
        assertNotNull(err.body)
    }

    @Test
    fun `422 validation detail list is flattened`() {
        val err = describeHttpFailure(
            422,
            """{"detail":[{"loc":["body","offer_id"],"msg":"field required","type":"missing"}]}""",
        )
        assertTrue(err.message.contains("body.offer_id: field required"), err.message)
    }

    @Test
    fun `an HTML 502 from the proxy is shown as text, truncated`() = runTest {
        val html = "<html><body>" + "Bad gateway ".repeat(200) + "</body></html>"
        val err = FakeHub(html, HttpStatusCode.BadGateway).reader.getJson("/api/x").errValue()
        assertEquals(502, err.status)
        assertTrue(err.message.length < 700, "message should be bounded, was ${err.message.length}")
        assertTrue(err.mayHaveExecuted, "a proxy 502 might have come after the hub acted")
    }

    @Test
    fun `garbled JSON on a 200 is an error, not an empty Ok`() = runTest {
        for (body in listOf("{not json", "<html>login</html>", "", "   ")) {
            val err = FakeHub(body).reader.getJson("/api/x").errValue()
            assertEquals(OpsErrorKind.BadJson, err.kind, "body=<$body>")
            assertEquals(200, err.status)
        }
    }

    @Test
    fun `valid JSON of any shape reaches the caller`() = runTest {
        assertTrue(FakeHub("[]").reader.getJson("/api/x") is OpsResult.Ok)
        assertTrue(FakeHub("{}").reader.getJson("/api/x") is OpsResult.Ok)
    }

    @Test
    fun `a request timeout is a Timeout error that says it may have executed`() = runTest {
        val hub = FakeHub({ throw HttpRequestTimeoutException("https://hub.test/api/x", 30_000) })
        val err = hub.reader.getJson("/api/x").errValue()
        assertEquals(OpsErrorKind.Timeout, err.kind)
        assertTrue(err.message.contains("timed out"), err.message)
        assertTrue(err.mayHaveExecuted)
    }

    @Test
    fun `a genuinely slow hub hits the explicit timeout`() {
        val hub = FakeHub({
            delay(5_000)
            respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val err = runBlocking { hub.reader.getJson("/api/x", timeoutMs = 150) }.errValue()
        assertEquals(OpsErrorKind.Timeout, err.kind, err.toString())
    }

    @Test
    fun `network failures are reported with the host, and are not empty`() = runTest {
        val dns = FakeHub({ throw UnknownHostException("hub.test") }).reader.getJson("/api/x").errValue()
        assertEquals(OpsErrorKind.Unreachable, dns.kind)
        assertTrue(dns.message.contains("hub.test"), dns.message)

        val refused = FakeHub({ throw ConnectException("Connection refused") }).reader.getJson("/api/x").errValue()
        assertEquals(OpsErrorKind.Unreachable, refused.kind)
        assertTrue(refused.message.contains("refused"), refused.message)
        assertFalse(refused.mayHaveExecuted, "a connect failure means the request never left")

        val io = FakeHub({ throw java.io.IOException("connection reset") }).reader.getJson("/api/x").errValue()
        assertEquals(OpsErrorKind.Network, io.kind)
        assertTrue(io.mayHaveExecuted)
    }

    @Test
    fun `a blank or malformed hub URL is a config error and sends nothing`() = runTest {
        val hub = FakeHub("{}")
        val bad = OpsHttp("   ", "u", "p", hub.engine)
        val err = OpsReader(bad).getJson("/api/x").errValue()
        assertEquals(OpsErrorKind.Config, err.kind)
        assertTrue(hub.engine.requestHistory.isEmpty())
        assertEquals(OpsErrorKind.Config, OpsReader(OpsHttp("hub.test", "u", "p", hub.engine)).getJson("/api/x").errValue().kind)
    }

    @Test
    fun `redirects are not followed`() = runTest {
        val hub = FakeHub({
            respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://elsewhere.test/login"))
        })
        val err = hub.reader.getJson("/api/x").errValue()
        assertEquals(302, err.status)
        assertEquals(1, hub.engine.requestHistory.size, "must not chase the redirect with the relay password")
    }

    @Test
    fun `basic auth is sent when a password is set and omitted when blank`() = runTest {
        val hub = FakeHub("{}")
        hub.reader.getJson("/api/x").okValue()
        val header = hub.engine.requestHistory.single().headers[HttpHeaders.Authorization]
        val expected = "Basic " + java.util.Base64.getEncoder().encodeToString("anon3:s3cret-pw".toByteArray())
        assertEquals(expected, header)

        val blank = FakeHub("{}")
        OpsReader(OpsHttp("https://hub.test", "anon3", "", blank.engine)).getJson("/api/x").okValue()
        assertNull(blank.engine.requestHistory.single().headers[HttpHeaders.Authorization])
    }

    @Test
    fun `error messages never contain the password or the Authorization header`() = runTest {
        for (status in listOf(HttpStatusCode.Unauthorized, HttpStatusCode.InternalServerError)) {
            val err = FakeHub("", status).reader.getJson("/api/x").errValue()
            assertFalse(err.message.contains("s3cret-pw"))
            assertFalse(err.message.contains("Basic "))
        }
        val net = FakeHub({ throw ConnectException("boom") }).reader.getJson("/api/x").errValue()
        assertFalse(net.message.contains("s3cret-pw"))
    }

    @Test
    fun `the reader only ever sends GET`() = runTest {
        val hub = FakeHub("{}")
        hub.reader.getJson("/api/a").okValue()
        hub.reader.getJson("/api/b?x=1").okValue()
        assertTrue(hub.methods.all { it == HttpMethod.Get }, hub.methods.toString())
    }

    @Test
    fun `the actor sends POST with a JSON body`() = runTest {
        val hub = FakeHub("""{"ok":true}""")
        val body = kotlinx.serialization.json.buildJsonObject { put("k", "v") }
        hub.actor.post("/api/z", body).okValue()
        assertEquals(listOf(HttpMethod.Post), hub.methods)
        val sent = hub.engine.requestHistory.single().body
        assertEquals("""{"k":"v"}""", (sent as io.ktor.http.content.TextContent).text)
    }

    @Test
    fun `an action that times out is reported as possibly executed`() {
        val outcome = OpsError("x", kind = OpsErrorKind.Timeout).toFailedOutcome()
        assertTrue(outcome.mayHaveExecuted)
        assertNotNull(outcome.detail)
    }
}
