package com.whitedevil

import com.whitedevil.security.DeviceAuthCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * Pure-JVM coverage for the parts of device auth that do not need a phone:
 * PEM encoding, DER/base64 shape, request bodies, token expiry.
 *
 * The AndroidKeyStore and BiometricPrompt paths cannot run here — they need a
 * physical device with secure hardware and an enrolled fingerprint/PIN. This uses
 * an ordinary JCE P-256 key to exercise the same encoders over the same shapes.
 */
class DeviceAuthCodecTest {

    private fun p256() = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()

    // ------------------------------------------------------------------- PEM

    @Test
    fun pemHasHeadersAndWrapsAtSixtyFour() {
        val pem = DeviceAuthCodec.publicKeyPem(p256().public.encoded)
        val lines = pem.trim().lines()
        assertEquals("-----BEGIN PUBLIC KEY-----", lines.first())
        assertEquals("-----END PUBLIC KEY-----", lines.last())
        val body = lines.subList(1, lines.size - 1)
        assertTrue("expected at least one body line", body.isNotEmpty())
        body.dropLast(1).forEach { assertEquals(64, it.length) }
        assertTrue("last body line overflows", body.last().length in 1..64)
        assertTrue(pem.endsWith("\n"))
    }

    @Test
    fun pemRoundTripsBackToTheSameKey() {
        val key = p256().public
        val pem = DeviceAuthCodec.publicKeyPem(key.encoded)
        val b64 = pem.lines()
            .filterNot { it.startsWith("-----") || it.isBlank() }
            .joinToString("")
        val der = java.util.Base64.getDecoder().decode(b64)
        // Byte-identical SubjectPublicKeyInfo is what hub/auth.py's
        // load_pem_public_key parses; anything else would fail there, not here.
        assertArrayEquals(key.encoded, der)
        val restored = java.security.KeyFactory.getInstance("EC")
            .generatePublic(java.security.spec.X509EncodedKeySpec(der))
        assertEquals(key, restored)
    }

    private fun assertArrayEquals(a: ByteArray, b: ByteArray) =
        org.junit.Assert.assertArrayEquals(a, b)

    // ------------------------------------------------------- signature shape

    @Test
    fun realP256SignatureIsRecognisedAsDer() {
        val pair = p256()
        val nonce = "Yl9rQ2hhbGxlbmdlLW5vbmNlLTEyMzQ1Njc4OTA"
        val signer = Signature.getInstance("SHA256withECDSA").apply {
            initSign(pair.private)
            update(DeviceAuthCodec.nonceBytes(nonce))
        }
        val der = signer.sign()
        assertTrue("SHA256withECDSA output should be DER", DeviceAuthCodec.looksLikeEcdsaDer(der))

        val b64 = DeviceAuthCodec.signatureBase64(der)
        // Standard alphabet, single line: the hub does b64decode(validate=True).
        assertFalse(b64.contains('\n'))
        assertFalse(b64.contains('-'))
        assertFalse(b64.contains('_'))
        assertArrayEquals(der, java.util.Base64.getDecoder().decode(b64))

        // And it actually verifies over the RAW UTF-8 nonce bytes, which is the
        // one thing hub/auth.py checks.
        val verifier = Signature.getInstance("SHA256withECDSA").apply {
            initVerify(pair.public)
            update(nonce.toByteArray(Charsets.UTF_8))
        }
        assertTrue(verifier.verify(der))
    }

    @Test
    fun rawConcatenatedSignatureIsRejected() {
        // A 64-byte r||s blob is what a naive implementation would send; the hub
        // would answer a bare 401, so catch it locally instead.
        assertFalse(DeviceAuthCodec.looksLikeEcdsaDer(ByteArray(64) { 0x7F }))
        assertFalse(DeviceAuthCodec.looksLikeEcdsaDer(ByteArray(0)))
        assertFalse(DeviceAuthCodec.looksLikeEcdsaDer(byteArrayOf(0x30, 0x06, 0x02, 0x01, 0x01)))
    }

    // -------------------------------------------------------------- JSON body

    @Test
    fun enrolBodyEscapesPemNewlinesProperly() {
        val pem = DeviceAuthCodec.publicKeyPem(p256().public.encoded)
        val body = DeviceAuthCodec.encodeEnrol(
            DeviceAuthCodec.EnrolRequest(name = "Pixel \"test\"\n8", publicKeyPem = pem),
        )
        // The whole point of using kotlinx-serialization: escape sequences, not
        // literal control characters, inside the JSON string.
        assertFalse("raw newline leaked into the body", body.contains('\n'))
        assertTrue(body.contains("\\n"))
        assertTrue(body.contains("\"public_key_pem\""))
        assertTrue(body.contains("\\\"test\\\""))

        val decoded = DeviceAuthCodec.json.decodeFromString(
            DeviceAuthCodec.EnrolRequest.serializer(), body,
        )
        assertEquals(pem, decoded.publicKeyPem)
        assertEquals("Pixel \"test\"\n8", decoded.name)
    }

    @Test
    fun enrolBodyCarriesTheEnrolmentCodeOnlyWhenThereIsOne() {
        val pem = DeviceAuthCodec.publicKeyPem(p256().public.encoded)
        val without = DeviceAuthCodec.encodeEnrol(DeviceAuthCodec.EnrolRequest(name = "phone", publicKeyPem = pem))
        // Older hubs and hubs with the code gate off must see exactly the body earlier builds sent.
        assertFalse(without, without.contains("enrol_code"))
        val with = DeviceAuthCodec.encodeEnrol(
            DeviceAuthCodec.EnrolRequest(name = "phone", publicKeyPem = pem, enrolCode = "ABCD-1234"),
        )
        assertTrue(with, with.contains("\"enrol_code\":\"ABCD-1234\""))
    }

    @Test
    fun enrolmentCodeIsTrimmedAndBlankMeansNone() {
        assertEquals("ABCD-1234", DeviceAuthCodec.normaliseEnrolCode("  ABCD-1234\n"))
        assertEquals(null, DeviceAuthCodec.normaliseEnrolCode(""))
        assertEquals(null, DeviceAuthCodec.normaliseEnrolCode("   \n\t"))
        assertEquals(null, DeviceAuthCodec.normaliseEnrolCode(null))
    }

    @Test
    fun authConfigSaysWhetherTheHubWantsACode() {
        val yes = DeviceAuthCodec.decodeConfig(
            """{"require_enrol_code":true,"require_enrol_code_configured":false,"forward_auth_mode":"strict","devices_enrolled":1,"modes":["permissive"]}""",
        )
        assertTrue(yes.requireEnrolCode)
        assertFalse(DeviceAuthCodec.decodeConfig("""{"require_enrol_code":false,"forward_auth_mode":"permissive"}""").requireEnrolCode)
        // A hub that does not send the field is not assumed to want one.
        assertFalse(DeviceAuthCodec.decodeConfig("""{}""").requireEnrolCode)
    }

    @Test
    fun hubDetailGivesTheSentenceNotTheJson() {
        assertEquals(
            "An enrolment code is required.",
            DeviceAuthCodec.hubDetail("""{"detail":"An enrolment code is required."}"""),
        )
        assertEquals(
            "the request was rejected as invalid",
            DeviceAuthCodec.hubDetail("""{"detail":[{"loc":["body","name"],"msg":"field required"}]}"""),
        )
        // Not JSON (a proxy page): shortened and flattened, never dumped whole.
        val page = DeviceAuthCodec.hubDetail("<html>\n<body>" + "Bad gateway ".repeat(100) + "</body></html>")
        assertTrue(page.length <= 200)
        assertFalse(page.contains('\n'))
        assertEquals("HTTP 401", DeviceAuthCodec.hubDetail("HTTP 401"))
    }

    @Test
    fun requestBodiesUseServerFieldNames() {
        assertEquals(
            """{"device_id":"abc123"}""",
            DeviceAuthCodec.encodeChallenge(DeviceAuthCodec.ChallengeRequest("abc123")),
        )
        assertEquals(
            """{"device_id":"abc123","signature_b64":"MEQ="}""",
            DeviceAuthCodec.encodeToken(DeviceAuthCodec.TokenRequest("abc123", "MEQ=")),
        )
    }

    @Test
    fun responsesDecodeAndTolerateUnknownFields() {
        val enrol = DeviceAuthCodec.decodeEnrol(
            """{"id":"deadbeef","name":"Pixel","created":1.5,"future":true}""",
        )
        assertEquals("deadbeef", enrol.id)

        val challenge = DeviceAuthCodec.decodeChallenge("""{"nonce":"abc","expires_in":120}""")
        assertEquals("abc", challenge.nonce)
        assertEquals(120L, challenge.expiresIn)

        val token = DeviceAuthCodec.decodeTokenResponse(
            """{"token":"t","expires_in":43200,"device_id":"deadbeef"}""",
        )
        assertEquals(43200L, token.expiresIn)
        assertEquals("deadbeef", token.deviceId)

        val anon = DeviceAuthCodec.decodeWhoami("""{"authenticated":false,"devices_enrolled":2}""")
        assertFalse(anon.authenticated)
        assertEquals(2, anon.devicesEnrolled)

        val me = DeviceAuthCodec.decodeWhoami(
            """{"authenticated":true,"device_id":"deadbeef","name":"Pixel"}""",
        )
        assertTrue(me.authenticated)
        assertEquals("deadbeef", me.deviceId)
    }

    // ----------------------------------------------------------------- expiry

    @Test
    fun tokenExpiryLeavesSkewBeforeTheDeadline() {
        val now = 1_700_000_000_000L
        val expiresAt = DeviceAuthCodec.expiryAtMs(now, 12 * 3600)
        assertEquals(now + 12 * 3600 * 1000L, expiresAt)

        assertTrue(DeviceAuthCodec.tokenUsable(expiresAt, now))
        // Still fine an hour in, gone once we are inside the skew window.
        assertTrue(DeviceAuthCodec.tokenUsable(expiresAt, now + 3600_000L))
        assertFalse(
            "a token inside the skew window must be treated as expired",
            DeviceAuthCodec.tokenUsable(expiresAt, expiresAt - DeviceAuthCodec.EXPIRY_SKEW_MS + 1),
        )
        assertFalse(DeviceAuthCodec.tokenUsable(expiresAt, expiresAt + 1))
    }

    @Test
    fun missingOrNegativeExpiryIsNeverUsable() {
        val now = 1_700_000_000_000L
        assertFalse(DeviceAuthCodec.tokenUsable(0L, now))
        assertEquals(now, DeviceAuthCodec.expiryAtMs(now, -5))
        assertFalse(DeviceAuthCodec.tokenUsable(DeviceAuthCodec.expiryAtMs(now, -5), now))
    }
}
