package com.whitedevil.desktop.ops

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.assertIs

/** A hub stand-in: every request is recorded, so tests can assert on the methods actually sent. */
internal class FakeHub(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) {
    val engine = MockEngine(handler)
    val http = OpsHttp("https://hub.test", "anon3", "s3cret-pw", engine)
    val reader = OpsReader(http)
    val actor = OpsActor(http)

    val methods: List<HttpMethod> get() = engine.requestHistory.map { it.method }
    val paths: List<String> get() = engine.requestHistory.map { it.url.encodedPathAndQuery }

    /** Fixed reply for every path. */
    constructor(body: String, status: HttpStatusCode = HttpStatusCode.OK) : this({ jsonReply(body, status) })
}

internal fun MockRequestHandleScope.jsonReply(body: String, status: HttpStatusCode = HttpStatusCode.OK): HttpResponseData =
    respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

internal inline fun <reified T : OpsResult<*>> OpsResult<*>.expect(): T {
    assertIs<T>(this, "expected ${T::class.simpleName} but got $this")
    return this as T
}

internal fun <T> OpsResult<T>.okValue(): T = (this as? OpsResult.Ok)?.value
    ?: error("expected Ok but got $this")

internal fun OpsResult<*>.errValue(): OpsError = (this as? OpsResult.Err)?.error
    ?: error("expected Err but got $this")

/** Shaped like the real hub's /api/colab/state (hub/colab.py:189-204), plus a field the hub does not send today. */
internal val COLAB_STATE_JSON = """
{
  "runner_mode": "wanbot",
  "runner_online": true,
  "comfy_online": false,
  "paused": null,
  "billing": true,
  "instance": {"name": "colab", "endpoint": "gpu-abc123", "accelerator": "G4", "status": "IDLE", "running": true,
               "raw": "[colab] gpu-abc123 | Hardware: G4 | Status: IDLE"},
  "status": {"label": "Running", "kind": "ok", "billing": true, "detail": "G4 billing ${'$'}0.77/h · wanbot up"},
  "gpu": "NVIDIA G4",
  "jobs": [
    {"id": "j1", "name": "goon_p01", "chain_id": "c1", "status": "rendering",
     "progress": {"done": 3, "total": 10}, "current": 4, "avg_seconds": 312, "error": null},
    {"id": "j2", "name": "goon_p02", "chain_id": "c2", "status": "queued", "progress": 0.0, "current": null, "avg_seconds": null, "error": "boom"}
  ],
  "heartbeat": {"state": "ok", "beat": "17", "at": 1790000000.5},
  "usage": {"balance": 42.5, "rate_per_hr": 0.77, "active": 1, "checked": 1790000000.0},
  "recover_running": false,
  "recover_log": ["restarting tunnel", "runner up"],
  "resume_packs": ["1", "2"],
  "a_future_field": {"nested": [1, 2, 3]}
}
"""
