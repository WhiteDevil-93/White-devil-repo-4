package com.whitedevil.security

import com.whitedevil.relay.RelayHttp
import com.whitedevil.relay.RelayHttpException

/**
 * Thin client for hub/auth.py. Blocking — call it off the main thread.
 *
 * Every call still carries the existing relay basic auth, because Caddy fronts the
 * hub and enrolment is deliberately "guarded by whatever already guards the hub".
 * Device tokens are an ADDITION; nothing here replaces basic auth.
 */
object DeviceAuthClient {

    /** True when the relay predates device auth — the endpoint simply isn't there. */
    fun isMissingEndpoint(e: Throwable): Boolean =
        e is RelayHttpException && (e.code == 404 || e.code == 405 || e.code == 501)

    /** GET /api/auth/config: does this hub demand an enrolment code? Asked before a key is made. */
    fun config(relayBase: String, basicAuth: String): DeviceAuthCodec.AuthConfigResponse =
        DeviceAuthCodec.decodeConfig(RelayHttp.get(relayBase, basicAuth, "/api/auth/config"))

    fun enrol(
        relayBase: String,
        basicAuth: String,
        name: String,
        publicKeyPem: String,
        enrolCode: String? = null,
    ): DeviceAuthCodec.EnrolResponse {
        val body = DeviceAuthCodec.encodeEnrol(
            DeviceAuthCodec.EnrolRequest(name = name, publicKeyPem = publicKeyPem, enrolCode = enrolCode),
        )
        return DeviceAuthCodec.decodeEnrol(
            RelayHttp.post(relayBase, basicAuth, "/api/auth/devices", body),
        )
    }

    fun challenge(
        relayBase: String,
        basicAuth: String,
        deviceId: String,
    ): DeviceAuthCodec.ChallengeResponse {
        val body = DeviceAuthCodec.encodeChallenge(DeviceAuthCodec.ChallengeRequest(deviceId))
        return DeviceAuthCodec.decodeChallenge(
            RelayHttp.post(relayBase, basicAuth, "/api/auth/challenge", body),
        )
    }

    fun token(
        relayBase: String,
        basicAuth: String,
        deviceId: String,
        signatureB64: String,
    ): DeviceAuthCodec.TokenResponse {
        val body = DeviceAuthCodec.encodeToken(
            DeviceAuthCodec.TokenRequest(deviceId = deviceId, signatureB64 = signatureB64),
        )
        return DeviceAuthCodec.decodeTokenResponse(
            RelayHttp.post(relayBase, basicAuth, "/api/auth/token", body),
        )
    }

    /**
     * Check a token. Per the protocol this puts `Bearer <token>` in Authorization,
     * which is the one request that cannot also carry basic auth — so behind a Caddy
     * that enforces basic auth this is rejected by the proxy before the hub sees it.
     * That is a soft failure, never a hard one: callers treat any error here as
     * "device token not confirmed" and carry on over basic auth exactly as today.
     */
    fun whoami(relayBase: String, token: String): DeviceAuthCodec.WhoamiResponse =
        DeviceAuthCodec.decodeWhoami(
            RelayHttp.get(relayBase, "Bearer $token", "/api/auth/whoami"),
        )
}
