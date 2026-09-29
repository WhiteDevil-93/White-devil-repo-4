package com.whitedevil.desktop

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * HubAuthClient against ktor-client-mock. Response bodies mirror hub/auth.py (FastAPI:
 * errors are {"detail": ...}). No network, no Windows Hello.
 */
class HubAuthClientTest {

    private val json = ContentType.Application.Json.toString()

    private class Rig(val engine: MockEngine, val client: HubAuthClient) {
        val requests: List<HttpRequestData> get() = engine.requestHistory
    }

    private fun rig(
        base: String = "https://hub.example",
        user: String = "anon3",
        pass: String = "pw",
        handler: suspend io.ktor.client.engine.mock.MockRequestHandleScope.(HttpRequestData) -> io.ktor.client.request.HttpResponseData,
    ): Rig {
        val engine = MockEngine(handler)
        return Rig(engine, HubAuthClient(base, user, pass, engine))
    }

    private fun jsonReply(scope: io.ktor.client.engine.mock.MockRequestHandleScope, status: HttpStatusCode, body: String, extra: Map<String, String> = emptyMap()) =
        scope.respond(
            content = body,
            status = status,
            headers = io.ktor.http.Headers.build {
                append(HttpHeaders.ContentType, json)
                extra.forEach { (k, v) -> append(k, v) }
            },
        )

    private fun bodyText(r: HttpRequestData): String = assertIs<TextContent>(r.body).text

    private fun bodyJson(r: HttpRequestData): JsonObject = Json.parseToJsonElement(bodyText(r)).jsonObject

    private fun basic(user: String, pass: String) =
        "Basic " + Base64.getEncoder().encodeToString("$user:$pass".toByteArray(Charsets.UTF_8))

    // -- success paths -----------------------------------------------------------------

    @Test
    fun `enrol posts the key and decodes the device`() = runTest {
        val rig = rig { jsonReply(this, HttpStatusCode.OK, """{"id":"abc123","name":"laptop","created":1712345678.5}""") }
        val r = rig.client.enrol("laptop", "-----BEGIN PUBLIC KEY-----\nAA\n-----END PUBLIC KEY-----\n", "code-1")
        val ok = assertIs<HubAuthResult.Success<EnrolResponse>>(r).value
        assertEquals("abc123", ok.id)
        assertEquals("laptop", ok.name)

        val req = rig.requests.single()
        assertEquals(HttpMethod.Post, req.method)
        assertEquals("hub.example", req.url.host)
        assertEquals("/api/auth/devices", req.url.encodedPath)
        val body = bodyJson(req)
        assertEquals(setOf("name", "public_key_pem", "enrol_code"), body.keys)
        assertEquals("code-1", body["enrol_code"]!!.jsonPrimitive.content)
        assertEquals("-----BEGIN PUBLIC KEY-----\nAA\n-----END PUBLIC KEY-----\n", body["public_key_pem"]!!.jsonPrimitive.content)
    }

    @Test
    fun `enrol omits the code when there is none`() = runTest {
        val rig = rig { jsonReply(this, HttpStatusCode.OK, """{"id":"i","name":"n"}""") }
        rig.client.enrol("n", "pem", null)
        rig.client.enrol("n", "pem", "   ")
        for (req in rig.requests) assertFalse("enrol_code" in bodyJson(req).keys)
    }

    @Test
    fun `challenge and token decode and send the right bodies`() = runTest {
        val rig = rig { req ->
            when (req.url.encodedPath) {
                "/api/auth/challenge" -> jsonReply(this, HttpStatusCode.OK, """{"nonce":"n0nce_-abc","expires_in":120}""")
                "/api/auth/token" -> jsonReply(this, HttpStatusCode.OK, """{"token":"tok","expires_in":43200,"device_id":"d1","future":true}""")
                else -> jsonReply(this, HttpStatusCode.NotFound, """{"detail":"Not Found"}""")
            }
        }
        val c = assertIs<HubAuthResult.Success<ChallengeResponse>>(rig.client.challenge("d1")).value
        assertEquals("n0nce_-abc", c.nonce)
        assertEquals(120, c.expiresIn)
        val t = assertIs<HubAuthResult.Success<TokenResponse>>(rig.client.token("d1", "c2ln")).value
        assertEquals("tok", t.token)
        assertEquals(43200, t.expiresIn)

        assertEquals(mapOf("device_id" to "d1"), bodyJson(rig.requests[0]).mapValues { it.value.jsonPrimitive.content })
        assertEquals(
            mapOf("device_id" to "d1", "signature_b64" to "c2ln"),
            bodyJson(rig.requests[1]).mapValues { it.value.jsonPrimitive.content },
        )
    }

    @Test
    fun `whoami decodes both answers`() = runTest {
        val rig = rig { req ->
            if (req.headers[HttpHeaders.Authorization] == "Bearer good")
                jsonReply(this, HttpStatusCode.OK, """{"authenticated":true,"device_id":"d1","name":"laptop"}""")
            else jsonReply(this, HttpStatusCode.OK, """{"authenticated":false,"devices_enrolled":2}""")
        }
        val yes = assertIs<HubAuthResult.Success<WhoamiResponse>>(rig.client.whoami("good")).value
        assertTrue(yes.authenticated)
        assertEquals("d1", yes.deviceId)
        val no = assertIs<HubAuthResult.Success<WhoamiResponse>>(rig.client.whoami("bad")).value
        assertFalse(no.authenticated)
        assertNull(no.deviceId)
        assertEquals(2, no.devicesEnrolled)
        assertEquals(HttpMethod.Get, rig.requests.first().method)
        assertEquals("/api/auth/whoami", rig.requests.first().url.encodedPath)
    }

    @Test
    fun `config decodes and tolerates a missing flag`() = runTest {
        val full = rig { jsonReply(this, HttpStatusCode.OK, """{"require_enrol_code":true,"forward_auth_mode":"permissive","devices_enrolled":2,"basic_credential_configured":false,"modes":["permissive"]}""") }
        assertTrue(assertIs<HubAuthResult.Success<HubAuthConfig>>(full.client.config()).value.requireEnrolCode)
        val sparse = rig { jsonReply(this, HttpStatusCode.OK, "{}") }
        assertFalse(assertIs<HubAuthResult.Success<HubAuthConfig>>(sparse.client.config()).value.requireEnrolCode)
    }

    // -- the request body is always valid JSON -----------------------------------------

    @Test
    fun `the enrol body is valid JSON for names with quotes newlines and unicode`() = runTest {
        val nasty = listOf(
            "He said \"hi\"",
            "line1\nline2\r\nline3",
            "back\\slash and \\\" mix",
            "tab\there",
            "ctrl\u0001\u001f char",
            "café 日本語 😀 emoji",
            "</script><!-- {\"a\":[1,2]} -->",
            "'; DROP TABLE devices; --",
            "   separators",
            " ",
        )
        for (name in nasty) {
            val rig = rig { jsonReply(this, HttpStatusCode.OK, """{"id":"i","name":"n"}""") }
            rig.client.enrol(name, "-----BEGIN PUBLIC KEY-----\nA+B/C=\n-----END PUBLIC KEY-----\n", "c")
            val req = rig.requests.single()
            val text = bodyText(req)
            // Parses, and the name survives byte for byte.
            assertEquals(name, bodyJson(req)["name"]!!.jsonPrimitive.content, "name $name")
            // The encoder escaped control characters: no raw newline or control char inside the document.
            assertFalse(text.any { it.code < 0x20 }, "raw control character leaked into $text")
            // What goes on the wire is UTF-8 of exactly that text.
            assertEquals(text, assertIs<TextContent>(req.body).bytes().toString(Charsets.UTF_8))
            assertTrue(assertIs<TextContent>(req.body).contentType!!.match(ContentType.Application.Json))
        }
    }

    @Test
    fun `the challenge and token bodies escape too`() = runTest {
        val rig = rig { jsonReply(this, HttpStatusCode.NotFound, """{"detail":"Unknown device. Enrol it first."}""") }
        rig.client.challenge("we\"ird\nid")
        rig.client.token("we\"ird\nid", "sig\"nature")
        assertEquals("we\"ird\nid", bodyJson(rig.requests[0])["device_id"]!!.jsonPrimitive.content)
        assertEquals("sig\"nature", bodyJson(rig.requests[1])["signature_b64"]!!.jsonPrimitive.content)
    }

    // -- basic auth is always sent -----------------------------------------------------

    @Test
    fun `every request except whoami carries the relay basic auth`() = runTest {
        val user = "anon3"
        val pass = "p:ss é🔒"
        val rig = rig(user = user, pass = pass) { jsonReply(this, HttpStatusCode.OK, "{}") }
        rig.client.config()
        rig.client.probeDevices()
        rig.client.enrol("n", "pem", null)
        rig.client.challenge("d")
        rig.client.token("d", "s")
        for (req in rig.requests) {
            assertEquals(basic(user, pass), req.headers[HttpHeaders.Authorization], req.url.encodedPath)
        }
        assertEquals(5, rig.requests.size)
    }

    @Test
    fun `whoami sends the bearer token in place of basic auth because there is only one Authorization header`() = runTest {
        val rig = rig { jsonReply(this, HttpStatusCode.OK, """{"authenticated":false}""") }
        rig.client.whoami("the-token")
        assertEquals("Bearer the-token", rig.requests.single().headers[HttpHeaders.Authorization])
    }

    @Test
    fun `no relay user means no Authorization header rather than an empty credential`() = runTest {
        val rig = rig(user = "  ", pass = "") { jsonReply(this, HttpStatusCode.OK, "{}") }
        rig.client.challenge("d")
        assertNull(rig.requests.single().headers[HttpHeaders.Authorization])
    }

    // -- 404: the hub has no device auth -----------------------------------------------

    @Test
    fun `404 on enrol means the hub has no device auth`() = runTest {
        val rig = rig { jsonReply(this, HttpStatusCode.NotFound, """{"detail":"Not Found"}""") }
        assertEquals(HubAuthError.NoDeviceAuth, rig.client.enrol("n", "pem", null))
        assertEquals(HubAuthError.NoDeviceAuth, rig.client.config())
        assertEquals(HubAuthError.NoDeviceAuth, rig.client.probeDevices())
        assertEquals(HubAuthError.NoDeviceAuth, rig.client.whoami("t"))
    }

    @Test
    fun `405 and 501 also mean the route is not there`() = runTest {
        for (status in listOf(HttpStatusCode.MethodNotAllowed, HttpStatusCode.NotImplemented)) {
            val rig = rig { jsonReply(this, status, "") }
            assertEquals(HubAuthError.NoDeviceAuth, rig.client.enrol("n", "pem", null), status.toString())
        }
    }

    @Test
    fun `404 unknown device on challenge or token is distinct from a hub with no device auth`() = runTest {
        val unknown = rig { jsonReply(this, HttpStatusCode.NotFound, """{"detail":"Unknown device. Enrol it first."}""") }
        assertIs<HubAuthError.UnknownDevice>(unknown.client.challenge("gone"))
        assertIs<HubAuthError.UnknownDevice>(unknown.client.token("gone", "sig"))

        val absent = rig { jsonReply(this, HttpStatusCode.NotFound, """{"detail":"Not Found"}""") }
        assertEquals(HubAuthError.NoDeviceAuth, absent.client.challenge("d"))
        assertEquals(HubAuthError.NoDeviceAuth, absent.client.token("d", "sig"))

        // "Unknown device" wording on the enrol route does not change its meaning.
        assertEquals(HubAuthError.NoDeviceAuth, unknown.client.enrol("n", "pem", null))
    }

    // -- 401 / 403 / 409 / 400 / 422 ---------------------------------------------------

    @Test
    fun `401 403 409 and 400 carry the hubs detail`() = runTest {
        fun errFor(status: HttpStatusCode, detail: String) =
            rig { jsonReply(this, status, """{"detail":"$detail"}""") }.client

        assertEquals(
            HubAuthError.Unauthorized("Signature does not match this device's enrolled key."),
            errFor(HttpStatusCode.Unauthorized, "Signature does not match this device's enrolled key.").token("d", "s"),
        )
        assertEquals(
            HubAuthError.Forbidden("That enrolment code is unknown or has already been used."),
            errFor(HttpStatusCode.Forbidden, "That enrolment code is unknown or has already been used.").enrol("n", "p", "c"),
        )
        assertEquals(
            HubAuthError.Conflict("That public key is already enrolled."),
            errFor(HttpStatusCode.Conflict, "That public key is already enrolled.").enrol("n", "p", "c"),
        )
        assertEquals(
            HubAuthError.BadRequest("No outstanding challenge for this device; request one first."),
            errFor(HttpStatusCode.BadRequest, "No outstanding challenge for this device; request one first.").token("d", "s"),
        )
    }

    @Test
    fun `a 401 from the proxy with no body is still Unauthorized`() = runTest {
        val rig = rig { respond("", HttpStatusCode.Unauthorized, headersOf(HttpHeaders.WWWAuthenticate, "Basic realm=\"restricted\"")) }
        assertEquals(HubAuthError.Unauthorized(null), rig.client.challenge("d"))
    }

    @Test
    fun `a 422 validation list is summarised`() = runTest {
        val rig = rig {
            jsonReply(this, HttpStatusCode.UnprocessableEntity, """{"detail":[{"loc":["body","name"],"msg":"String should have at most 80 characters","type":"string_too_long"}]}""")
        }
        assertEquals(HubAuthError.BadRequest("String should have at most 80 characters"), rig.client.enrol("n", "p", null))
    }

    @Test
    fun `an html error page is never echoed into the detail`() = runTest {
        val rig = rig { jsonReply(this, HttpStatusCode.BadGateway, "<html><body><h1>502 Bad Gateway</h1>secret-ish</body></html>") }
        val e = assertIs<HubAuthError.HttpError>(rig.client.challenge("d"))
        assertEquals(502, e.status)
        assertNull(e.detail)
    }

    @Test
    fun `5xx and redirects are HttpError and redirects are not followed`() = runTest {
        val s500 = rig { jsonReply(this, HttpStatusCode.InternalServerError, """{"detail":"boom"}""") }
        assertEquals(HubAuthError.HttpError(500, "boom"), s500.client.enrol("n", "p", null))

        // Ktor never follows a POST redirect anyway; the GETs are where following would carry the relay
        // password to another host, so those are what this must pin down.
        for (call in listOf<suspend (HubAuthClient) -> Any>(
            { it.config() }, { it.probeDevices() }, { it.whoami("t") }, { it.challenge("d") }, { it.enrol("n", "p", null) },
        )) {
            val redirect = rig { respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://evil.example/")) }
            assertEquals(HubAuthError.HttpError(302, null), call(redirect.client))
            assertEquals(1, redirect.requests.size, "must not follow a redirect with the relay password attached")
            assertEquals("hub.example", redirect.requests.single().url.host)
        }
    }

    // -- 429 ---------------------------------------------------------------------------

    @Test
    fun `429 surfaces Retry-After and is not retried`() = runTest {
        val rig = rig {
            jsonReply(
                this, HttpStatusCode.TooManyRequests,
                """{"detail":"Too many requests: 10 per 60s for this device. Try again in 17s."}""",
                mapOf(HttpHeaders.RetryAfter to "17"),
            )
        }
        val e = assertIs<HubAuthError.RateLimited>(rig.client.challenge("d"))
        assertEquals(17, e.retryAfterSeconds)
        assertTrue(e.detail!!.contains("Too many requests"))
        assertEquals(1, rig.requests.size, "a 429 must never be retried automatically")
    }

    @Test
    fun `429 on token is surfaced the same way`() = runTest {
        val rig = rig { jsonReply(this, HttpStatusCode.TooManyRequests, """{"detail":"slow down"}""", mapOf(HttpHeaders.RetryAfter to "60")) }
        assertEquals(HubAuthError.RateLimited(60, "slow down"), rig.client.token("d", "s"))
        assertEquals(1, rig.requests.size)
    }

    @Test
    fun `Retry-After that is missing or not a number of seconds is null not an error`() = runTest {
        val missing = rig { jsonReply(this, HttpStatusCode.TooManyRequests, "{}") }
        assertEquals(HubAuthError.RateLimited(null, null), missing.client.challenge("d"))

        val date = rig { jsonReply(this, HttpStatusCode.TooManyRequests, "{}", mapOf(HttpHeaders.RetryAfter to "Wed, 21 Oct 2026 07:28:00 GMT")) }
        assertNull(assertIs<HubAuthError.RateLimited>(date.client.challenge("d")).retryAfterSeconds)

        val negative = rig { jsonReply(this, HttpStatusCode.TooManyRequests, "{}", mapOf(HttpHeaders.RetryAfter to "-5")) }
        assertEquals(0, assertIs<HubAuthError.RateLimited>(negative.client.challenge("d")).retryAfterSeconds)

        val huge = rig { jsonReply(this, HttpStatusCode.TooManyRequests, "{}", mapOf(HttpHeaders.RetryAfter to "99999999")) }
        assertEquals(86_400, assertIs<HubAuthError.RateLimited>(huge.client.challenge("d")).retryAfterSeconds)
    }

    // -- network -----------------------------------------------------------------------

    @Test
    fun `a connection failure is a Network error not an exception`() = runTest {
        val rig = rig { throw IOException("Connection refused") }
        val e = assertIs<HubAuthError.Network>(rig.client.challenge("d"))
        assertTrue(e.message.contains("Connection refused"))
    }

    @Test
    fun `a timeout is a Network error`() = runTest {
        val rig = rig { throw HttpRequestTimeoutException("https://hub.example/api/auth/token", 30_000) }
        val e = assertIs<HubAuthError.Network>(rig.client.token("d", "s"))
        assertTrue(e.message.contains("in time"))
    }

    @Test
    fun `the password never appears in an error`() = runTest {
        val pass = "hunter2-very-secret"
        val rig = rig(pass = pass) { throw IOException("boom") }
        val e = rig.client.enrol("n", "p", null)
        assertFalse(e.toString().contains(pass))
        val bad = HubAuthClient("https://user:$pass@hub.example", "u", pass, MockEngine { respond("") })
        val cfg = bad.config()
        assertIs<HubAuthError.BadConfig>(cfg)
        assertFalse(cfg.toString().contains(pass))
    }

    // -- malformed success bodies ------------------------------------------------------

    @Test
    fun `a 200 with the wrong shape is BadResponse`() = runTest {
        for (body in listOf("", "not json", "[]", "{}", """{"id":5}""", """{"id":"","name":"x"}""")) {
            val rig = rig { jsonReply(this, HttpStatusCode.OK, body) }
            assertIs<HubAuthError.BadResponse>(rig.client.enrol("n", "p", null), "enrol body: $body")
        }
        val noNonce = rig { jsonReply(this, HttpStatusCode.OK, """{"nonce":"","expires_in":120}""") }
        assertIs<HubAuthError.BadResponse>(noNonce.client.challenge("d"))
        val noExpiry = rig { jsonReply(this, HttpStatusCode.OK, """{"token":"t"}""") }
        assertIs<HubAuthError.BadResponse>(noExpiry.client.token("d", "s"))
        val emptyToken = rig { jsonReply(this, HttpStatusCode.OK, """{"token":"","expires_in":10}""") }
        assertIs<HubAuthError.BadResponse>(emptyToken.client.token("d", "s"))
    }

    @Test
    fun `an oversized reply is refused`() = runTest {
        val rig = rig {
            respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, (HubAuthClient.MAX_RESPONSE_BYTES + 1).toString()))
        }
        assertIs<HubAuthError.BadResponse>(rig.client.config())
    }

    // -- URL handling ------------------------------------------------------------------

    @Test
    fun `a trailing slash and whitespace on the hub url are tolerated`() = runTest {
        val rig = rig(base = "  https://hub.example/  ") { jsonReply(this, HttpStatusCode.OK, """{"nonce":"n","expires_in":1}""") }
        rig.client.challenge("d")
        assertEquals("/api/auth/challenge", rig.requests.single().url.encodedPath)
        assertEquals("https://hub.example", rig.client.normalizedBaseUrl)
    }

    @Test
    fun `a hub url with a path prefix keeps it`() = runTest {
        val rig = rig(base = "https://hub.example/prefix/") { jsonReply(this, HttpStatusCode.OK, """{"nonce":"n","expires_in":1}""") }
        rig.client.challenge("d")
        assertEquals("/prefix/api/auth/challenge", rig.requests.single().url.encodedPath)
    }

    @Test
    fun `an unusable hub url is BadConfig and nothing is sent`() = runTest {
        for (bad in listOf("", "   ", "hub.example", "ftp://hub.example", "https://", "https://user:pw@hub.example", "not a url", "javascript:alert(1)")) {
            val rig = rig(base = bad) { jsonReply(this, HttpStatusCode.OK, "{}") }
            assertIs<HubAuthError.BadConfig>(rig.client.challenge("d"), "url: '$bad'")
            assertTrue(rig.requests.isEmpty(), "sent a request for '$bad'")
            assertNull(rig.client.normalizedBaseUrl)
        }
    }

    @Test
    fun `http and https are both accepted and normalised`() {
        assertEquals("http://127.0.0.1:9000", HubAuthClient.normalizeBaseUrl("http://127.0.0.1:9000/"))
        assertEquals("https://84-12-112-249.sslip.io", HubAuthClient.normalizeBaseUrl("https://84-12-112-249.sslip.io"))
        assertEquals("HTTPS://Hub.Example", HubAuthClient.normalizeBaseUrl("HTTPS://Hub.Example//"))
    }

    // -- detail extraction -------------------------------------------------------------

    @Test
    fun `detail extraction is defensive`() {
        assertEquals("x", HubAuthClient.extractDetail("""{"detail":"x"}"""))
        assertNull(HubAuthClient.extractDetail(""))
        assertNull(HubAuthClient.extractDetail("<html>"))
        assertNull(HubAuthClient.extractDetail("[]"))
        assertNull(HubAuthClient.extractDetail("""{"detail":{"nested":true}}"""))
        assertNull(HubAuthClient.extractDetail("""{"detail":null}"""))
        assertNull(HubAuthClient.extractDetail("""{"other":"x"}"""))
        assertEquals(200, HubAuthClient.extractDetail("""{"detail":"${"y".repeat(5000)}"}""")!!.length)
        assertFalse(HubAuthClient.extractDetail("""{"detail":"a\nb\u0007c"}""")!!.any { it.isISOControl() })
        assertNotNull(HubAuthClient.extractDetail("""{"detail":"café 🔒"}"""))
    }
}
