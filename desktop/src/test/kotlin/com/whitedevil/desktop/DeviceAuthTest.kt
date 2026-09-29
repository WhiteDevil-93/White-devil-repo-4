package com.whitedevil.desktop

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The enrol and sign-in flows, with a fake Windows Hello and a mock hub. What matters most
 * here is what does NOT happen: no Hello prompt when the hub cannot take the enrolment, no
 * retry after a 429, no key replacement for a refusal we could have predicted.
 */
class DeviceAuthTest {

    // -- fakes -------------------------------------------------------------------------

    private class FakeHelper(
        var unavailable: String? = null,
        var status: HelperOutcome<HelloStatus> = HelperOutcome.Success(HelloStatus(true, false, "k", "Ready.")),
        var create: HelperOutcome<CreatedKey> = HelperOutcome.Success(CreatedKey(PEM)),
        var sign: HelperOutcome<SignedNonce> = HelperOutcome.Success(SignedNonce("c2ln")),
        var explode: Throwable? = null,
    ) : HelloHelper {
        val calls = mutableListOf<String>()
        val signedNonces = mutableListOf<String>()

        override fun unavailableReason(): String? = unavailable

        override suspend fun status(): HelperOutcome<HelloStatus> {
            calls += "status"
            explode?.let { throw it }
            return status
        }

        override suspend fun createKey(): HelperOutcome<CreatedKey> {
            calls += "create"
            explode?.let { throw it }
            return create
        }

        override suspend fun sign(nonce: String): HelperOutcome<SignedNonce> {
            calls += "sign"
            signedNonces += nonce
            explode?.let { throw it }
            return sign
        }
    }

    private class Reply(val status: HttpStatusCode, val body: String = "{}", val headers: Map<String, String> = emptyMap())

    private class Hub(routes: (HttpRequestData) -> Reply) {
        val engine = MockEngine { req ->
            val r = routes(req)
            respond(
                r.body, r.status,
                Headers.build {
                    append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    r.headers.forEach { (k, v) -> append(k, v) }
                },
            )
        }
        val requests: List<HttpRequestData> get() = engine.requestHistory
        val summary: List<String> get() = requests.map { "${it.method.value} ${it.url.encodedPath}" }
        fun client() = HubAuthClient("https://hub.example", "anon3", "pw", engine)
        fun body(i: Int) = Json.parseToJsonElement((requests[i].body as TextContent).text).jsonObject
    }

    private fun ok(body: String) = Reply(HttpStatusCode.OK, body)

    private fun route(vararg pairs: Pair<String, Reply>): (HttpRequestData) -> Reply {
        val map = pairs.toMap()
        return { req -> map["${req.method.value} ${req.url.encodedPath}"] ?: Reply(HttpStatusCode.NotFound, """{"detail":"Not Found"}""") }
    }

    private var now = 10_000_000L
    private val tokens = TokenCache(clock = { now }, skewMs = 120_000L)
    private val key = TokenCache.Key("https://hub.example", "dev9")

    private fun auth(helper: HelloHelper, hub: Hub) = DeviceAuth(helper, hub.client(), tokens)

    private val configNoCode = "GET /api/auth/config" to ok("""{"require_enrol_code":false,"forward_auth_mode":"permissive","devices_enrolled":1}""")
    private val configNeedsCode = "GET /api/auth/config" to ok("""{"require_enrol_code":true,"forward_auth_mode":"permissive","devices_enrolled":1}""")
    private val enrolOk = "POST /api/auth/devices" to ok("""{"id":"dev9","name":"My PC","created":1.5}""")
    private val challengeOk = "POST /api/auth/challenge" to ok("""{"nonce":"Nonce_abc-123","expires_in":120}""")
    private val tokenOk = "POST /api/auth/token" to ok("""{"token":"SECRET-TOKEN","expires_in":43200,"device_id":"dev9"}""")

    // -- enrol: happy path -------------------------------------------------------------

    @Test
    fun `enrol checks hello then the hub and only then creates the key`() = runTest {
        val helper = FakeHelper()
        val hub = Hub(route(configNeedsCode, enrolOk))
        val r = auth(helper, hub).enrol("My PC", " CODE-1 ")

        assertEquals(EnrolResult.Enrolled("dev9", "My PC"), r)
        assertEquals(listOf("status", "create"), helper.calls)
        assertEquals(listOf("GET /api/auth/config", "POST /api/auth/devices"), hub.summary)
        val body = hub.body(1)
        assertEquals("My PC", body["name"]!!.jsonPrimitive.content)
        assertEquals("CODE-1", body["enrol_code"]!!.jsonPrimitive.content)
        assertEquals(PEM, body["public_key_pem"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the code is left out when the hub does not want one`() = runTest {
        val hub = Hub(route(configNoCode, enrolOk))
        assertIs<EnrolResult.Enrolled>(auth(FakeHelper(), hub).enrol("My PC", ""))
        assertFalse("enrol_code" in hub.body(1).keys)
    }

    @Test
    fun `an older hub without config but with enrolment is still enrolled`() = runTest {
        val hub = Hub(route("GET /api/auth/devices" to ok("""{"devices":[]}"""), enrolOk)) // /config -> 404
        val helper = FakeHelper()
        assertIs<EnrolResult.Enrolled>(auth(helper, hub).enrol("My PC", ""))
        assertEquals(listOf("GET /api/auth/config", "GET /api/auth/devices", "POST /api/auth/devices"), hub.summary)
        assertEquals(listOf("status", "create"), helper.calls)
    }

    @Test
    fun `an over long or blank name is clamped to what the hub accepts`() = runTest {
        val hub = Hub(route(configNoCode, enrolOk))
        // 79 ASCII + an astral character: a naive take(80) would cut the surrogate pair in half.
        val long = "a".repeat(79) + "😀" + "tail"
        auth(FakeHelper(), hub).enrol(long, "")
        val sent = hub.body(1)["name"]!!.jsonPrimitive.content
        assertTrue(sent.length <= 80)
        assertTrue(sent.codePointCount(0, sent.length) <= 80)
        assertFalse(sent.last().isHighSurrogate(), "split a surrogate pair")

        val hub2 = Hub(route(configNoCode, enrolOk))
        auth(FakeHelper(), hub2).enrol("   ", "")
        assertTrue(hub2.body(1)["name"]!!.jsonPrimitive.content.startsWith("WhiteDevil desktop"))
    }

    // -- enrol: refusals that must NOT touch the Hello key -----------------------------

    @Test
    fun `a hub with no device auth means no prompt and no key change`() = runTest {
        val helper = FakeHelper()
        val hub = Hub(route()) // everything 404
        val r = assertIs<EnrolResult.Failed>(auth(helper, hub).enrol("My PC", "code"))

        assertFalse(r.keyReplaced)
        assertTrue(r.message.contains("does not offer device authentication"), r.message)
        assertTrue(r.message.contains("relay password"), r.message)
        assertEquals(listOf("status"), helper.calls, "createKey would prompt and replace the key")
        assertTrue(hub.requests.none { it.method == HttpMethod.Post }, "nothing may be POSTed")
    }

    @Test
    fun `a hub that wants a code we were not given stops before the key is created`() = runTest {
        val helper = FakeHelper()
        val hub = Hub(route(configNeedsCode, enrolOk))
        val r = assertIs<EnrolResult.Failed>(auth(helper, hub).enrol("My PC", "   "))
        assertFalse(r.keyReplaced)
        assertTrue(r.message.contains("enrolment code"), r.message)
        assertEquals(listOf("status"), helper.calls)
        assertEquals(listOf("GET /api/auth/config"), hub.summary)
    }

    @Test
    fun `windows hello not being set up stops before any hub request`() = runTest {
        val helper = FakeHelper(status = HelperOutcome.Success(HelloStatus(false, false, null, "Add a PIN in Windows Settings.")))
        val hub = Hub(route(configNoCode, enrolOk))
        val r = assertIs<EnrolResult.Failed>(auth(helper, hub).enrol("n", ""))
        assertEquals("Add a PIN in Windows Settings.", r.message)
        assertTrue(hub.requests.isEmpty())
        assertEquals(listOf("status"), helper.calls)
    }

    @Test
    fun `an unavailable helper runs nothing and asks nothing`() = runTest {
        val helper = FakeHelper(unavailable = "The Windows Hello helper (wd-hello.exe) was not found next to the app.")
        val hub = Hub(route(configNoCode, enrolOk))
        val r = assertIs<EnrolResult.Failed>(auth(helper, hub).enrol("n", ""))
        assertTrue(r.message.contains("wd-hello.exe"))
        assertTrue(helper.calls.isEmpty())
        assertTrue(hub.requests.isEmpty())
    }

    @Test
    fun `a status failure stops before the hub`() = runTest {
        val helper = FakeHelper(status = HelperOutcome.Failure(HelperFailureKind.TIMED_OUT, "Windows Hello did not answer within 20 seconds, so the helper was stopped."))
        val hub = Hub(route(configNoCode, enrolOk))
        val r = assertIs<EnrolResult.Failed>(auth(helper, hub).enrol("n", ""))
        assertTrue(r.message.contains("did not answer"))
        assertTrue(hub.requests.isEmpty())
    }

    @Test
    fun `a relay 401 on the probe stops before the key is created`() = runTest {
        val helper = FakeHelper()
        val hub = Hub(route("GET /api/auth/config" to Reply(HttpStatusCode.Unauthorized, "")))
        val r = assertIs<EnrolResult.Failed>(auth(helper, hub).enrol("n", ""))
        assertTrue(r.message.contains("relay rejected the user name or password"), r.message)
        assertFalse(r.keyReplaced)
        assertEquals(listOf("status"), helper.calls)
    }

    @Test
    fun `cancelling the create prompt changes nothing and posts nothing`() = runTest {
        val helper = FakeHelper(create = HelperOutcome.Failure(HelperFailureKind.REPORTED_ERROR, "Windows would not create the key: you cancelled the Windows Hello prompt"))
        val hub = Hub(route(configNoCode, enrolOk))
        val r = assertIs<EnrolResult.Failed>(auth(helper, hub).enrol("n", ""))
        assertFalse(r.keyReplaced)
        assertTrue(r.message.contains("cancelled"))
        assertTrue(hub.requests.none { it.method == HttpMethod.Post })
    }

    // -- enrol: failures after the key was replaced ------------------------------------

    @Test
    fun `a hub refusal after the key was created says the key was replaced`() = runTest {
        val hub = Hub(route(configNoCode, "POST /api/auth/devices" to Reply(HttpStatusCode.Forbidden, """{"detail":"That enrolment code is unknown or has already been used."}""")))
        val r = assertIs<EnrolResult.Failed>(auth(FakeHelper(), hub).enrol("n", "old"))
        assertTrue(r.keyReplaced)
        assertTrue(r.message.contains("unknown or has already been used"), r.message)
        assertTrue(r.message.contains("replaced"), r.message)
    }

    @Test
    fun `a conflict and a server error are also failures with the key replaced`() = runTest {
        for (reply in listOf(
            Reply(HttpStatusCode.Conflict, """{"detail":"That public key is already enrolled."}"""),
            Reply(HttpStatusCode.InternalServerError, """{"detail":"boom"}"""),
        )) {
            val hub = Hub(route(configNoCode, "POST /api/auth/devices" to reply))
            val r = assertIs<EnrolResult.Failed>(auth(FakeHelper(), hub).enrol("n", ""))
            assertTrue(r.keyReplaced)
        }
    }

    @Test
    fun `an enrol 429 is surfaced with its Retry-After and not retried`() = runTest {
        val hub = Hub(route(configNoCode, "POST /api/auth/devices" to Reply(HttpStatusCode.TooManyRequests, """{"detail":"slow"}""", mapOf(HttpHeaders.RetryAfter to "30"))))
        val r = assertIs<EnrolResult.Failed>(auth(FakeHelper(), hub).enrol("n", ""))
        assertTrue(r.rateLimited)
        assertEquals(30, r.retryAfterSeconds)
        assertTrue(r.message.contains("30 seconds"), r.message)
        assertEquals(1, hub.requests.count { it.method == HttpMethod.Post }, "no automatic retry")
    }

    @Test
    fun `a successful enrol drops any token cached for the old enrolment`() = runTest {
        tokens.store(key, "old", 43200)
        val hub = Hub(route(configNoCode, enrolOk))
        assertIs<EnrolResult.Enrolled>(auth(FakeHelper(), hub).enrol("n", ""))
        assertNull(tokens.get(key))
    }

    // -- sign in -----------------------------------------------------------------------

    @Test
    fun `sign in runs challenge then sign then token and caches the token in memory`() = runTest {
        val helper = FakeHelper()
        val hub = Hub(route(challengeOk, tokenOk))
        val r = assertIs<SignInResult.SignedIn>(auth(helper, hub).signIn("dev9"))

        assertFalse(r.fromCache)
        assertEquals(now + 43_200_000L - 120_000L, r.validUntilMs)
        assertEquals(listOf("POST /api/auth/challenge", "POST /api/auth/token"), hub.summary)
        assertEquals(listOf("Nonce_abc-123"), helper.signedNonces, "the hub's nonce is what gets signed")
        assertEquals("c2ln", hub.body(1)["signature_b64"]!!.jsonPrimitive.content)
        assertEquals("dev9", hub.body(1)["device_id"]!!.jsonPrimitive.content)
        assertEquals("SECRET-TOKEN", tokens.get(key))
        assertFalse(r.toString().contains("SECRET-TOKEN"))
    }

    @Test
    fun `a fresh cached token means no prompt and no request`() = runTest {
        val helper = FakeHelper()
        val hub = Hub(route(challengeOk, tokenOk))
        val a = auth(helper, hub)
        a.signIn("dev9")
        val second = assertIs<SignInResult.SignedIn>(a.signIn("dev9"))
        assertTrue(second.fromCache)
        assertEquals(1, helper.signedNonces.size, "the second sign-in must not prompt")
        assertEquals(2, hub.requests.size, "and must not touch the hub")
    }

    @Test
    fun `a token inside the skew margin is refreshed`() = runTest {
        val helper = FakeHelper()
        val hub = Hub(route(challengeOk, tokenOk))
        val a = auth(helper, hub)
        a.signIn("dev9")
        now += 43_200_000L - 120_000L // exactly at the trusted limit
        val again = assertIs<SignInResult.SignedIn>(a.signIn("dev9"))
        assertFalse(again.fromCache)
        assertEquals(2, helper.signedNonces.size)
    }

    @Test
    fun `force signs in again even with a fresh token`() = runTest {
        val helper = FakeHelper()
        val hub = Hub(route(challengeOk, tokenOk))
        val a = auth(helper, hub)
        a.signIn("dev9")
        assertFalse(assertIs<SignInResult.SignedIn>(a.signIn("dev9", force = true)).fromCache)
        assertEquals(2, helper.signedNonces.size)
    }

    @Test
    fun `sign out forgets the token`() = runTest {
        val hub = Hub(route(challengeOk, tokenOk))
        val a = auth(FakeHelper(), hub)
        a.signIn("dev9")
        a.signOut()
        assertNull(tokens.get(key))
    }

    @Test
    fun `a 429 on the challenge stops there with no signing and no retry`() = runTest {
        val helper = FakeHelper()
        val hub = Hub(route("POST /api/auth/challenge" to Reply(HttpStatusCode.TooManyRequests, """{"detail":"Too many requests"}""", mapOf(HttpHeaders.RetryAfter to "42")), tokenOk))
        val r = assertIs<SignInResult.Failed>(auth(helper, hub).signIn("dev9"))
        assertTrue(r.rateLimited)
        assertEquals(42, r.retryAfterSeconds)
        assertTrue(r.message.contains("42 seconds"), r.message)
        assertTrue(helper.signedNonces.isEmpty(), "no Hello prompt when the hub is rate limiting")
        assertEquals(1, hub.requests.size, "no automatic retry")
    }

    @Test
    fun `a 429 on the token is surfaced and the challenge is not re-requested`() = runTest {
        val hub = Hub(route(challengeOk, "POST /api/auth/token" to Reply(HttpStatusCode.TooManyRequests, "{}", mapOf(HttpHeaders.RetryAfter to "9"))))
        val r = assertIs<SignInResult.Failed>(auth(FakeHelper(), hub).signIn("dev9"))
        assertTrue(r.rateLimited)
        assertEquals(9, r.retryAfterSeconds)
        assertEquals(listOf("POST /api/auth/challenge", "POST /api/auth/token"), hub.summary)
    }

    @Test
    fun `an unknown device asks the user to enrol again`() = runTest {
        val hub = Hub(route("POST /api/auth/challenge" to Reply(HttpStatusCode.NotFound, """{"detail":"Unknown device. Enrol it first."}""")))
        val r = assertIs<SignInResult.Failed>(auth(FakeHelper(), hub).signIn("dev9"))
        assertTrue(r.reenrolNeeded)
        assertTrue(r.message.contains("Enrol this PC again"), r.message)
    }

    @Test
    fun `a hub without device auth is a plain failure for sign in`() = runTest {
        val r = assertIs<SignInResult.Failed>(auth(FakeHelper(), Hub(route())).signIn("dev9"))
        assertFalse(r.reenrolNeeded)
        assertTrue(r.message.contains("does not offer device authentication"), r.message)
    }

    @Test
    fun `cancelling the sign prompt never reaches the token endpoint`() = runTest {
        val helper = FakeHelper(sign = HelperOutcome.Failure(HelperFailureKind.REPORTED_ERROR, "Signing was not completed: you cancelled the Windows Hello prompt"))
        val hub = Hub(route(challengeOk, tokenOk))
        val r = assertIs<SignInResult.Failed>(auth(helper, hub).signIn("dev9"))
        assertTrue(r.message.contains("cancelled"))
        assertEquals(listOf("POST /api/auth/challenge"), hub.summary)
        assertNull(tokens.get(key))
    }

    @Test
    fun `a rejected signature says to enrol again and caches nothing`() = runTest {
        val hub = Hub(route(challengeOk, "POST /api/auth/token" to Reply(HttpStatusCode.Unauthorized, """{"detail":"Signature does not match this device's enrolled key."}""")))
        val r = assertIs<SignInResult.Failed>(auth(FakeHelper(), hub).signIn("dev9"))
        assertTrue(r.reenrolNeeded)
        assertTrue(r.message.contains("did not accept the signature"), r.message)
        assertNull(tokens.get(key))
    }

    @Test
    fun `a relay 401 on the challenge is worded as a relay problem not a signature problem`() = runTest {
        val hub = Hub(route("POST /api/auth/challenge" to Reply(HttpStatusCode.Unauthorized, "")))
        val r = assertIs<SignInResult.Failed>(auth(FakeHelper(), hub).signIn("dev9"))
        assertTrue(r.message.contains("relay rejected"), r.message)
        assertFalse(r.reenrolNeeded)
    }

    @Test
    fun `a challenge in an unexpected shape is never handed to a command line`() = runTest {
        for (nonce in listOf("has space", "quote\\\"", "new\\nline", "semi;colon", "\$(whoami)", "a&b", "")) {
            val helper = FakeHelper()
            val hub = Hub(route("POST /api/auth/challenge" to ok("""{"nonce":"$nonce","expires_in":120}""")))
            val r = auth(helper, hub).signIn("dev9")
            assertIs<SignInResult.Failed>(r, "nonce '$nonce'")
            assertTrue(helper.signedNonces.isEmpty(), "signed '$nonce'")
        }
    }

    @Test
    fun `a token that is already stale is discarded`() = runTest {
        val hub = Hub(route(challengeOk, "POST /api/auth/token" to ok("""{"token":"t","expires_in":30}""")))
        val r = assertIs<SignInResult.Failed>(auth(FakeHelper(), hub).signIn("dev9"))
        assertTrue(r.message.contains("already expired"), r.message)
        assertNull(tokens.get(key))
    }

    @Test
    fun `sign in without an enrolment or without a helper spends no challenge`() = runTest {
        val hub = Hub(route(challengeOk, tokenOk))
        assertIs<SignInResult.Failed>(auth(FakeHelper(), hub).signIn("  "))
        assertIs<SignInResult.Failed>(auth(FakeHelper(unavailable = "no helper"), hub).signIn("dev9"))
        assertTrue(hub.requests.isEmpty(), "a challenge is single use and rate limited: do not burn one for nothing")
    }

    @Test
    fun `a network failure is a failure not a retry`() = runTest {
        // MockEngine records a request only after its handler returns, so count attempts ourselves.
        var attempts = 0
        val engine = MockEngine { attempts++; throw java.io.IOException("Connection refused") }
        val helper = FakeHelper()
        val a = DeviceAuth(helper, HubAuthClient("https://hub.example", "u", "p", engine), tokens)
        val r = assertIs<SignInResult.Failed>(a.signIn("dev9"))
        assertTrue(r.message.contains("Connection refused"), r.message)
        assertEquals(1, attempts, "no automatic retry")
        assertTrue(helper.signedNonces.isEmpty())
    }

    // -- robustness --------------------------------------------------------------------

    @Test
    fun `a helper that blows up becomes a failure not a crash`() = runTest {
        val hub = Hub(route(configNoCode, enrolOk, challengeOk, tokenOk))
        val boom = FakeHelper(explode = IllegalStateException("kaboom"))
        assertIs<EnrolResult.Failed>(auth(boom, hub).enrol("n", ""))
        assertIs<SignInResult.Failed>(auth(boom, hub).signIn("dev9"))
    }

    @Test
    fun `cancellation is not swallowed`() = runTest {
        val hub = Hub(route(configNoCode, enrolOk, challengeOk, tokenOk))
        val cancelling = FakeHelper(explode = CancellationException("screen closed"))
        assertFailsWith<CancellationException> { auth(cancelling, hub).enrol("n", "") }
        assertFailsWith<CancellationException> { auth(cancelling, hub).signIn("dev9") }
    }

    // -- settings ----------------------------------------------------------------------

    private val base = Settings(hubUrl = "https://hub.example", relayUser = "anon3", relayPass = "pw", veniceApiKey = "vk")

    @Test
    fun `enrolment is persisted as id and name and nothing else changes`() {
        val next = settingsAfterEnrol(base, EnrolResult.Enrolled("dev9", "My PC"))!!
        assertEquals("dev9", next.deviceId)
        assertEquals("My PC", next.deviceName)
        assertEquals(base.copy(deviceId = "dev9", deviceName = "My PC"), next)
    }

    @Test
    fun `a plain failure persists nothing`() {
        assertNull(settingsAfterEnrol(base, EnrolResult.Failed("x")))
        assertNull(settingsAfterEnrol(base.copy(deviceId = "old"), EnrolResult.Failed("x")))
    }

    @Test
    fun `losing the key on a PC that was enrolled forgets the dead enrolment`() {
        val enrolled = base.copy(deviceId = "old", deviceName = "Old PC")
        val next = settingsAfterEnrol(enrolled, EnrolResult.Failed("x", keyReplaced = true))!!
        assertEquals("", next.deviceId)
        assertEquals("", next.deviceName)
        assertEquals("vk", next.veniceApiKey)
        // ...but a PC that never had an enrolment has nothing to forget.
        assertNull(settingsAfterEnrol(base, EnrolResult.Failed("x", keyReplaced = true)))
    }

    // -- cooldown ----------------------------------------------------------------------

    @Test
    fun `the cooldown gate blocks until Retry-After has passed`() {
        var t = 1_000L
        val gate = CooldownGate { t }
        assertEquals(0, gate.remainingSeconds())
        gate.start(30)
        assertEquals(30, gate.remainingSeconds())
        t += 10_500
        assertEquals(20, gate.remainingSeconds()) // rounded up
        t += 19_500
        assertEquals(0, gate.remainingSeconds())
    }

    @Test
    fun `the cooldown gate has a default and a ceiling and can be cleared`() {
        var t = 0L
        val gate = CooldownGate { t }
        gate.start(null)
        assertEquals(CooldownGate.DEFAULT_SECONDS, gate.remainingSeconds())
        gate.start(1_000_000)
        assertEquals(CooldownGate.MAX_SECONDS, gate.remainingSeconds())
        gate.start(0)
        assertEquals(1, gate.remainingSeconds(), "even Retry-After: 0 waits a beat")
        gate.clear()
        assertEquals(0, gate.remainingSeconds())
    }

    // -- names -------------------------------------------------------------------------

    @Test
    fun `device names are trimmed clamped and defaulted`() {
        assertEquals("My PC", DeviceAuth.clampDeviceName("  My PC  "))
        assertEquals("WhiteDevil desktop (BOX)", DeviceAuth.clampDeviceName("   ", fallback = DeviceAuth.defaultDeviceName("BOX")))
        assertEquals(80, DeviceAuth.clampDeviceName("x".repeat(500)).length)
        assertEquals("WhiteDevil desktop", DeviceAuth.defaultDeviceName(null))
        assertEquals("WhiteDevil desktop", DeviceAuth.defaultDeviceName("  "))
        assertTrue(DeviceAuth.defaultDeviceName("H".repeat(500)).length <= 80)
    }

    // -- wording -----------------------------------------------------------------------

    @Test
    fun `every error has a message a human can read and none leaks a secret`() {
        val all: List<HubAuthError> = listOf(
            HubAuthError.NoDeviceAuth, HubAuthError.UnknownDevice(null), HubAuthError.Unauthorized(null),
            HubAuthError.Unauthorized("d"), HubAuthError.Forbidden("d"), HubAuthError.Conflict(null),
            HubAuthError.BadRequest("d"), HubAuthError.RateLimited(5, null), HubAuthError.RateLimited(null, null),
            HubAuthError.HttpError(500, null), HubAuthError.Network("n"), HubAuthError.BadConfig("c"), HubAuthError.BadResponse("r"),
        )
        for (e in all) for (stage in DeviceAuth.Stage.entries) {
            val msg = with(DeviceAuth) { e.userMessage(stage) }
            assertTrue(msg.isNotBlank(), "$e/$stage")
            assertFalse(msg.contains("null"), "'null' leaked into: $msg")
        }
    }

    companion object {
        const val PEM = "-----BEGIN PUBLIC KEY-----\nMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA\n-----END PUBLIC KEY-----\n"
    }
}
