package com.whitedevil.desktop

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TokenCacheTest {
    private var now = 1_000_000L
    private val cache = TokenCache(nowMs = { now }, skewMs = 60_000)

    @Test
    fun `a fresh token is valid for its device`() {
        cache.put("dev", "T", 3600)
        assertEquals("T", cache.valid("dev")?.token)
    }

    @Test
    fun `it expires a skew before the hub does`() {
        cache.put("dev", "T", 3600)              // hub deadline: now + 3_600_000
        now += 3_600_000 - 60_001
        assertNotNull(cache.valid("dev"))
        now += 2
        assertNull(cache.valid("dev"))
    }

    @Test
    fun `a token for another device is not returned`() {
        cache.put("dev", "T", 3600)
        assertNull(cache.valid("other"))
    }

    @Test
    fun `clear forgets it`() {
        cache.put("dev", "T", 3600)
        cache.clear()
        assertNull(cache.valid("dev"))
    }

    @Test
    fun `toString does not reveal the token`() {
        cache.put("dev", "SECRETTOKEN", 3600)
        assertFalse(cache.valid("dev").toString().contains("SECRETTOKEN"))
    }
}

class DeviceAuthServiceTest {
    private class FakeHello(
        var status: Result<HelloStatus> = Result.success(HelloStatus(available = true, keyExists = false, detail = "")),
        var create: Result<String> = Result.success("-----BEGIN PUBLIC KEY-----\nAAA\n-----END PUBLIC KEY-----\n"),
        var sign: (String) -> Result<String> = { Result.success("SIGNATURE-OF-$it") },
    ) : HelloBackend {
        var statusCalls = 0
        var createCalls = 0
        val signedNonces = mutableListOf<String>()
        override fun status() = status.also { statusCalls++ }
        override fun createKey() = create.also { createCalls++ }
        override fun sign(nonce: String) = sign.invoke(nonce).also { signedNonces += nonce }
    }

    private val ok = """{"id":"dev1","name":"laptop","created":1.0}"""

    private fun hubClient(engine: MockEngine) = HubAuthClient("https://hub.test", "anon3", "pw", engine)

    private fun engine(vararg routes: Pair<String, () -> Pair<HttpStatusCode, String>>) = MockEngine { req ->
        val (status, body) = routes.first { req.url.encodedPath.endsWith(it.first) }.second()
        respond(body, status, jsonHeaders)
    }

    private val enrolOk = "/devices" to { HttpStatusCode.OK to ok }
    private val challengeOk = "/challenge" to { HttpStatusCode.OK to """{"nonce":"NONCE123","expires_in":120}""" }
    private val tokenOk = "/token" to { HttpStatusCode.OK to """{"token":"BEARERTOKEN","expires_in":43200,"device_id":"dev1"}""" }

    // ---- enrol -----------------------------------------------------------------

    @Test
    fun `enrol probes, creates the key, registers it and reports the id`() = runBlocking {
        val hello = FakeHello()
        val e = engine(enrolOk)
        val r = DeviceAuthService(hello).enrol(hubClient(e), "laptop", null, replaceExisting = false)
        assertTrue(r.ok, r.message)
        assertEquals("dev1", r.deviceId)
        assertEquals(1, hello.statusCalls)
        assertEquals(1, hello.createCalls)
        assertEquals(1, e.requestHistory.size)
    }

    @Test
    fun `an existing key is not replaced without confirmation`() = runBlocking {
        val hello = FakeHello(status = Result.success(HelloStatus(true, keyExists = true, detail = "")))
        val e = engine(enrolOk)
        val svc = DeviceAuthService(hello)
        val r = svc.enrol(hubClient(e), "laptop", null, replaceExisting = false)
        assertFalse(r.ok)
        assertTrue(r.needsReplaceConfirmation)
        assertEquals(0, hello.createCalls, "must not create (and destroy the old key) unasked")
        assertTrue(e.requestHistory.isEmpty())

        val again = svc.enrol(hubClient(e), "laptop", null, replaceExisting = true)
        assertTrue(again.ok, again.message)
        assertEquals(1, hello.createCalls)
    }

    @Test
    fun `hello unavailable stops before any prompt or hub call`() = runBlocking {
        val hello = FakeHello(status = Result.success(HelloStatus(false, false, "Add a PIN in Windows Settings.")))
        val e = engine(enrolOk)
        val r = DeviceAuthService(hello).enrol(hubClient(e), "laptop", null, false)
        assertFalse(r.ok)
        assertTrue(r.message.contains("Add a PIN"))
        assertTrue(r.message.contains(DeviceAuthService.CARRY_ON))
        assertEquals(0, hello.createCalls)
        assertTrue(e.requestHistory.isEmpty())
    }

    @Test
    fun `a missing helper stops before any prompt or hub call`() = runBlocking {
        val hello = FakeHello(status = Result.failure(HelloException("wd-hello.exe was not found.")))
        val e = engine(enrolOk)
        val r = DeviceAuthService(hello).enrol(hubClient(e), "laptop", null, false)
        assertFalse(r.ok)
        assertTrue(r.message.contains("wd-hello.exe was not found"))
        assertEquals(0, hello.createCalls)
        assertTrue(e.requestHistory.isEmpty())
    }

    @Test
    fun `a cancelled prompt enrols nothing`() = runBlocking {
        val hello = FakeHello(create = Result.failure(HelloException("you cancelled the Windows Hello prompt")))
        val e = engine(enrolOk)
        val r = DeviceAuthService(hello).enrol(hubClient(e), "laptop", null, false)
        assertFalse(r.ok)
        assertTrue(r.message.contains("cancelled"))
        assertTrue(e.requestHistory.isEmpty())
    }

    @Test
    fun `a hub that predates device auth is reported and the app carries on`() = runBlocking {
        val hello = FakeHello()
        val e = engine("/devices" to { HttpStatusCode.NotFound to """{"detail":"Not Found"}""" })
        val r = DeviceAuthService(hello).enrol(hubClient(e), "laptop", null, false)
        assertFalse(r.ok)
        assertTrue(r.message.contains("predates"))
        assertTrue(r.message.contains(DeviceAuthService.CARRY_ON))
        assertNull(r.deviceId)
    }

    @Test
    fun `retrying after a hub failure reuses the key instead of prompting again`() = runBlocking {
        val hello = FakeHello()
        var hubUp = false
        val e = engine("/devices" to { if (hubUp) HttpStatusCode.OK to ok else HttpStatusCode.BadGateway to "" })
        val svc = DeviceAuthService(hello)

        val first = svc.enrol(hubClient(e), "laptop", null, false)
        assertFalse(first.ok)
        assertEquals(1, hello.createCalls)

        hubUp = true
        val second = svc.enrol(hubClient(e), "laptop", null, false)
        assertTrue(second.ok, second.message)
        assertEquals(1, hello.createCalls, "the retry must not create a second key")
    }

    @Test
    fun `a required enrolment code is explained and the key is kept for the retry`() = runBlocking {
        val hello = FakeHello()
        var code: String? = null
        val e = MockEngine { req ->
            val body = req.bodyText()
            if ("code-abc" in body) respond(ok, HttpStatusCode.OK, jsonHeaders)
            else respond("""{"detail":"An enrolment code is required."}""", HttpStatusCode.Forbidden, jsonHeaders)
        }
        val svc = DeviceAuthService(hello)
        val first = svc.enrol(hubClient(e), "laptop", code, false)
        assertFalse(first.ok)
        assertTrue(first.message.contains("enrol-code"))

        code = "code-abc"
        val second = svc.enrol(hubClient(e), "laptop", code, false)
        assertTrue(second.ok, second.message)
        assertEquals(1, hello.createCalls)
    }

    @Test
    fun `a blank or oversized name is refused before anything runs`() = runBlocking {
        val hello = FakeHello()
        val svc = DeviceAuthService(hello)
        assertFalse(svc.enrol(hubClient(engine(enrolOk)), "  ", null, false).ok)
        assertFalse(svc.enrol(hubClient(engine(enrolOk)), "x".repeat(81), null, false).ok)
        assertEquals(0, hello.statusCalls)
    }

    // ---- sign in ---------------------------------------------------------------

    @Test
    fun `sign in signs the hub's nonce, caches the token and never echoes secrets`() = runBlocking {
        val hello = FakeHello(status = Result.success(HelloStatus(true, true, "")))
        val svc = DeviceAuthService(hello)
        val r = svc.signIn(hubClient(engine(challengeOk, tokenOk)), "dev1")
        assertTrue(r.ok, r.message)
        assertEquals(listOf("NONCE123"), hello.signedNonces)
        assertEquals("BEARERTOKEN", svc.cachedToken("dev1")?.token)
        assertNotNull(r.expiresAtMs)
        for (secret in listOf("BEARERTOKEN", "NONCE123", "SIGNATURE-OF-NONCE123")) {
            assertFalse(r.message.contains(secret), "message leaked $secret")
        }
    }

    @Test
    fun `a cancelled sign never reaches the token endpoint and asks for a fresh challenge next time`() = runBlocking {
        val hello = FakeHello(
            status = Result.success(HelloStatus(true, true, "")),
            sign = { Result.failure(HelloException("you cancelled the Windows Hello prompt")) },
        )
        var tokenCalls = 0
        val e = engine(challengeOk, "/token" to { tokenCalls++; HttpStatusCode.OK to "{}" })
        val svc = DeviceAuthService(hello)
        val r = svc.signIn(hubClient(e), "dev1")
        assertFalse(r.ok)
        assertEquals(0, tokenCalls)
        assertTrue(r.message.contains("fresh one"))
        assertNull(svc.cachedToken("dev1"))
    }

    @Test
    fun `each attempt requests its own challenge - a nonce is never reused`() = runBlocking {
        val hello = FakeHello(status = Result.success(HelloStatus(true, true, "")))
        var n = 0
        val e = engine("/challenge" to { HttpStatusCode.OK to """{"nonce":"N${++n}","expires_in":120}""" }, tokenOk)
        val svc = DeviceAuthService(hello)
        svc.signIn(hubClient(e), "dev1")
        svc.signIn(hubClient(e), "dev1")
        assertEquals(listOf("N1", "N2"), hello.signedNonces)
    }

    @Test
    fun `a rejected signature is explained and nothing is cached`() = runBlocking {
        val hello = FakeHello(status = Result.success(HelloStatus(true, true, "")))
        val e = engine(challengeOk, "/token" to { HttpStatusCode.Unauthorized to """{"detail":"Signature does not match this device's enrolled key."}""" })
        val svc = DeviceAuthService(hello)
        val r = svc.signIn(hubClient(e), "dev1")
        assertFalse(r.ok)
        assertTrue(r.message.contains("does not match"))
        assertNull(svc.cachedToken("dev1"))
    }

    @Test
    fun `a revoked device is reported as such`() = runBlocking {
        val hello = FakeHello(status = Result.success(HelloStatus(true, true, "")))
        val e = engine("/challenge" to { HttpStatusCode.NotFound to """{"detail":"Unknown device. Enrol it first."}""" })
        val r = DeviceAuthService(hello).signIn(hubClient(e), "dev1")
        assertFalse(r.ok)
        assertTrue(r.message.contains("revoked"))
    }

    @Test
    fun `no local key stops before touching the hub`() = runBlocking {
        val hello = FakeHello(status = Result.success(HelloStatus(true, keyExists = false, detail = "")))
        val e = engine(challengeOk, tokenOk)
        val r = DeviceAuthService(hello).signIn(hubClient(e), "dev1")
        assertFalse(r.ok)
        assertTrue(e.requestHistory.isEmpty(), "no challenge should be burned")
        assertTrue(hello.signedNonces.isEmpty())
    }

    @Test
    fun `rate limiting surfaces the wait`() = runBlocking {
        val hello = FakeHello(status = Result.success(HelloStatus(true, true, "")))
        val e = MockEngine { respond("""{"detail":"Too many requests"}""", HttpStatusCode.TooManyRequests, io.ktor.http.headersOf(io.ktor.http.HttpHeaders.ContentType to listOf("application/json"), io.ktor.http.HttpHeaders.RetryAfter to listOf("30"))) }
        val r = DeviceAuthService(hello).signIn(hubClient(e), "dev1")
        assertTrue(r.message.contains("30s"), r.message)
    }

    @Test
    fun `a second action while one is running is refused, not queued behind a prompt`() = runBlocking {
        val gate = CountDownLatch(1)
        val entered = CompletableDeferred<Unit>()
        val hello = FakeHello(
            status = Result.success(HelloStatus(true, true, "")),
            sign = { entered.complete(Unit); gate.await(10, TimeUnit.SECONDS); Result.success("SIG") },
        )
        val svc = DeviceAuthService(hello)
        val first = async(Dispatchers.Default) { svc.signIn(hubClient(engine(challengeOk, tokenOk)), "dev1") }
        entered.await()
        val second = svc.signIn(hubClient(engine(challengeOk, tokenOk)), "dev1")
        assertFalse(second.ok)
        assertTrue(second.message.contains("already in progress"))
        gate.countDown()
        assertTrue(first.await().ok)
    }

    // ---- verify / forget -----------------------------------------------------------

    @Test
    fun `verify without a token asks to sign in first`() = runBlocking {
        val r = DeviceAuthService(FakeHello()).verify(hubClient(engine()), "dev1")
        assertFalse(r.ok)
        assertTrue(r.message.contains("sign in"))
    }

    @Test
    fun `verify treats a bearer rejection at the proxy as expected, not broken`() = runBlocking {
        val hello = FakeHello(status = Result.success(HelloStatus(true, true, "")))
        val svc = DeviceAuthService(hello)
        svc.signIn(hubClient(engine(challengeOk, tokenOk)), "dev1")
        val r = svc.verify(hubClient(engine("/whoami" to { HttpStatusCode.Unauthorized to "" })), "dev1")
        assertFalse(r.ok)
        assertTrue(r.message.contains("expected"))
        assertNotNull(svc.cachedToken("dev1"), "a proxy rejection must not discard a good token")
    }

    @Test
    fun `verify confirms when the hub recognises the token`() = runBlocking {
        val hello = FakeHello(status = Result.success(HelloStatus(true, true, "")))
        val svc = DeviceAuthService(hello)
        svc.signIn(hubClient(engine(challengeOk, tokenOk)), "dev1")
        val r = svc.verify(hubClient(engine("/whoami" to { HttpStatusCode.OK to """{"authenticated":true,"device_id":"dev1","name":"laptop"}""" })), "dev1")
        assertTrue(r.ok, r.message)
    }

    @Test
    fun `forgetting drops the token and any half-finished enrolment`() = runBlocking {
        val hello = FakeHello(status = Result.success(HelloStatus(true, true, "")))
        val svc = DeviceAuthService(hello)
        svc.signIn(hubClient(engine(challengeOk, tokenOk)), "dev1")
        svc.forgetLocal()
        assertNull(svc.cachedToken("dev1"))
    }
}
