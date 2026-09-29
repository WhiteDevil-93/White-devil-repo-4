package com.whitedevil.desktop

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

internal fun HttpRequestData.bodyText(): String = (body as TextContent).text

class HubAuthClientTest {
    private fun client(engine: MockEngine, pass: String = "s3cret") =
        HubAuthClient("https://hub.test/", "anon3", pass, engine)

    private fun errorEngine(status: HttpStatusCode, body: String, headers: io.ktor.http.Headers = jsonHeaders) =
        MockEngine { respond(body, status, headers) }

    private fun failure(status: HttpStatusCode, body: String = "", headers: io.ktor.http.Headers = jsonHeaders): HubAuthException = runBlocking {
        val e = runCatching { client(errorEngine(status, body, headers)).challenge("dev") }.exceptionOrNull()
        assertTrue(e is HubAuthException, "expected HubAuthException, got $e")
        e
    }

    @Test
    fun `enrol posts json with basic auth and parses the reply`() = runBlocking<Unit> {
        val engine = MockEngine { respond("""{"id":"abc123","name":"laptop","created":1700000000.5}""", HttpStatusCode.OK, jsonHeaders) }
        val r = client(engine).enrol("laptop", "-----BEGIN PUBLIC KEY-----\nAAA\n-----END PUBLIC KEY-----\n")
        assertEquals("abc123", r.id)

        val req = engine.requestHistory.single()
        assertEquals("https://hub.test/api/auth/devices", req.url.toString())
        val expected = "Basic " + Base64.getEncoder().encodeToString("anon3:s3cret".toByteArray())
        assertEquals(expected, req.headers[HttpHeaders.Authorization])
        assertFalse(Json.parseToJsonElement(req.bodyText()).jsonObject.containsKey("enrol_code"), "null code must be omitted")
    }

    @Test
    fun `names with quotes newlines backslashes and unicode survive the wire`() = runBlocking<Unit> {
        val engine = MockEngine { respond("""{"id":"i","name":"n"}""", HttpStatusCode.OK, jsonHeaders) }
        val name = "Ana's \"desk\"\n\\ é中😀"
        val pem = "-----BEGIN PUBLIC KEY-----\nAAA\n-----END PUBLIC KEY-----\n"
        client(engine).enrol(name, pem)
        val sent = Json.parseToJsonElement(engine.requestHistory.single().bodyText()).jsonObject
        assertEquals(name, sent["name"]!!.jsonPrimitive.content)
        assertEquals(pem, sent["public_key_pem"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an enrolment code is sent trimmed when given`() = runBlocking<Unit> {
        val engine = MockEngine { respond("""{"id":"i","name":"n"}""", HttpStatusCode.OK, jsonHeaders) }
        client(engine).enrol("n", "pem", "  code-123 ")
        val sent = Json.parseToJsonElement(engine.requestHistory.single().bodyText()).jsonObject
        assertEquals("code-123", sent["enrol_code"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a blank enrolment code is omitted`() = runBlocking<Unit> {
        val engine = MockEngine { respond("""{"id":"i","name":"n"}""", HttpStatusCode.OK, jsonHeaders) }
        client(engine).enrol("n", "pem", "   ")
        assertFalse(Json.parseToJsonElement(engine.requestHistory.single().bodyText()).jsonObject.containsKey("enrol_code"))
    }

    @Test
    fun `challenge and token bodies use the hub's field names`() = runBlocking<Unit> {
        val engine = MockEngine { req ->
            if (req.url.encodedPath.endsWith("/challenge")) respond("""{"nonce":"N","expires_in":120}""", HttpStatusCode.OK, jsonHeaders)
            else respond("""{"token":"T","expires_in":43200,"device_id":"dev"}""", HttpStatusCode.OK, jsonHeaders)
        }
        val c = client(engine)
        assertEquals("N", c.challenge("dev").nonce)
        val t = c.token("dev", "c2ln")
        assertEquals(43200, t.expiresIn)
        val challengeBody = Json.parseToJsonElement(engine.requestHistory[0].bodyText()).jsonObject
        val tokenBody = Json.parseToJsonElement(engine.requestHistory[1].bodyText()).jsonObject
        assertEquals("dev", challengeBody["device_id"]!!.jsonPrimitive.content)
        assertEquals("c2ln", tokenBody["signature_b64"]!!.jsonPrimitive.content)
    }

    @Test
    fun `no basic auth header when there is no relay password`() = runBlocking<Unit> {
        val engine = MockEngine { respond("""{"nonce":"N","expires_in":1}""", HttpStatusCode.OK, jsonHeaders) }
        client(engine, pass = "").challenge("dev")
        assertNull(engine.requestHistory.single().headers[HttpHeaders.Authorization])
    }

    @Test
    fun `whoami sends the bearer token and not basic auth`() = runBlocking<Unit> {
        val engine = MockEngine { respond("""{"authenticated":true,"device_id":"dev","name":"laptop"}""", HttpStatusCode.OK, jsonHeaders) }
        val who = client(engine).whoami("TOKEN")
        assertTrue(who.authenticated)
        assertEquals("Bearer TOKEN", engine.requestHistory.single().headers[HttpHeaders.Authorization])
    }

    @Test
    fun `whoami parses the unauthenticated reply`() = runBlocking<Unit> {
        val engine = MockEngine { respond("""{"authenticated":false,"devices_enrolled":2}""", HttpStatusCode.OK, jsonHeaders) }
        val who = client(engine).whoami("x")
        assertFalse(who.authenticated)
        assertEquals(2, who.devicesEnrolled)
    }

    // ---- error classification ------------------------------------------------------

    @Test
    fun `a missing route means the hub predates device auth`() {
        assertEquals(HubAuthException.Kind.Unsupported, failure(HttpStatusCode.NotFound, """{"detail":"Not Found"}""").kind)
        assertEquals(HubAuthException.Kind.Unsupported, failure(HttpStatusCode.MethodNotAllowed).kind)
        assertEquals(HubAuthException.Kind.Unsupported, failure(HttpStatusCode.NotImplemented).kind)
    }

    @Test
    fun `an unknown device is not confused with a missing route`() {
        val e = failure(HttpStatusCode.NotFound, """{"detail":"Unknown device. Enrol it first."}""")
        assertEquals(HubAuthException.Kind.UnknownDevice, e.kind)
    }

    @Test
    fun `401 with no json body is still explained`() {
        val e = failure(HttpStatusCode.Unauthorized, "", headersOf(HttpHeaders.ContentType, "text/html"))
        assertEquals(HubAuthException.Kind.Unauthorized, e.kind)
        assertTrue(e.message!!.contains("relay"))
    }

    @Test
    fun `401 from the hub keeps the hub's own reason`() {
        val e = failure(HttpStatusCode.Unauthorized, """{"detail":"Signature does not match this device's enrolled key."}""")
        assertTrue(e.message!!.contains("Signature does not match"))
    }

    @Test
    fun `429 carries the retry delay`() {
        val e = failure(HttpStatusCode.TooManyRequests, """{"detail":"Too many requests"}""", headersOf(HttpHeaders.ContentType to listOf("application/json"), HttpHeaders.RetryAfter to listOf("17")))
        assertEquals(HubAuthException.Kind.RateLimited, e.kind)
        assertEquals(17, e.retryAfterSeconds)
    }

    @Test
    fun `403 about a code is an enrolment code problem`() {
        val e = failure(HttpStatusCode.Forbidden, """{"detail":"An enrolment code is required."}""")
        assertEquals(HubAuthException.Kind.EnrolCode, e.kind)
    }

    @Test
    fun `409 means already enrolled`() {
        assertEquals(HubAuthException.Kind.AlreadyEnrolled, failure(HttpStatusCode.Conflict, """{"detail":"That public key is already enrolled."}""").kind)
    }

    @Test
    fun `other 4xx are rejected with the hub's reason and 5xx are server errors`() {
        val bad = failure(HttpStatusCode.BadRequest, """{"detail":"Challenge expired; request a new one."}""")
        assertEquals(HubAuthException.Kind.Rejected, bad.kind)
        assertEquals("Challenge expired; request a new one.", bad.message)
        assertEquals(HubAuthException.Kind.Server, failure(HttpStatusCode.BadGateway, "<html>bad gateway</html>").kind)
    }

    @Test
    fun `a 422 validation list does not crash the parser`() {
        val e = failure(HttpStatusCode.UnprocessableEntity, """{"detail":[{"loc":["body","name"],"msg":"too long"}]}""")
        assertEquals(HubAuthException.Kind.Rejected, e.kind)
        assertNotNull(e.message)
    }

    @Test
    fun `a network failure is Unreachable and never mentions the password`() = runBlocking<Unit> {
        val engine = MockEngine { throw IOException("connection refused") }
        val e = runCatching { client(engine, pass = "s3cret").challenge("dev") }.exceptionOrNull()
        assertTrue(e is HubAuthException)
        assertEquals(HubAuthException.Kind.Unreachable, e.kind)
        assertFalse(e.message!!.contains("s3cret"))
    }

    @Test
    fun `a 200 with an unreadable body is a server error not a crash`() = runBlocking<Unit> {
        val engine = MockEngine { respond("<html>welcome</html>", HttpStatusCode.OK, jsonHeaders) }
        val e = runCatching { client(engine).challenge("dev") }.exceptionOrNull()
        assertTrue(e is HubAuthException)
        assertEquals(HubAuthException.Kind.Server, e.kind)
    }

    @Test
    fun `response types redact secrets in toString`() {
        assertFalse(TokenResponse("SECRETTOKEN", 60).toString().contains("SECRETTOKEN"))
        assertFalse(ChallengeResponse("SECRETNONCE", 60).toString().contains("SECRETNONCE"))
    }
}
