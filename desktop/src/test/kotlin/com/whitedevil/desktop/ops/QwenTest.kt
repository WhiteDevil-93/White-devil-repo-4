package com.whitedevil.desktop.ops

import io.ktor.http.HttpMethod
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QwenTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `parses ready state correctly`() {
        val payload = """
            {
                "status": "ready",
                "gateway_alive": true,
                "model_ready": true,
                "model_alias": "qwen-agent",
                "gateway_url": "http://127.0.0.1:18080",
                "health_latency_ms": 1.2,
                "ready_latency_ms": 3.4,
                "active_requests": 0,
                "max_concurrency": 1,
                "last_checked_epoch": 1780000000,
                "error": null,
                "vm": {
                    "instance_id": "123456",
                    "status": "running",
                    "gpu": "A100 SXM4 80GB",
                    "vram_gb": 80,
                    "price_per_hour": 1.45,
                    "stopped_price_per_hour": 0.02,
                    "host": "ssh4.vast.ai",
                    "port": 34567
                }
            }
        """.trimIndent()

        val state = qwenStateFrom(json.parseToJsonElement(payload))
        assertEquals("ready", state.status)
        assertTrue(state.gatewayAlive)
        assertTrue(state.modelReady)
        assertEquals("qwen-agent", state.modelAlias)
        assertEquals(1.2, state.healthLatencyMs)
        assertEquals(3.4, state.readyLatencyMs)
        assertEquals(0, state.activeRequests)
        assertEquals(1, state.maxConcurrency)
        assertNull(state.error)

        val vm = state.vm
        assertNotNull(vm)
        assertEquals("123456", vm.instanceId)
        assertEquals("running", vm.status)
        assertEquals("A100 SXM4 80GB", vm.gpu)
        assertEquals(80, vm.vramGb)
        assertEquals(1.45, vm.pricePerHour)
    }

    @Test
    fun `parses loading and offline states`() {
        val loadingPayload = """
            {
                "status": "loading",
                "gateway_alive": true,
                "model_ready": false,
                "model_alias": "qwen-agent",
                "gateway_url": "http://127.0.0.1:18080",
                "health_latency_ms": 2.1,
                "ready_latency_ms": null,
                "last_checked_epoch": 1780000000,
                "error": "Model upstream not ready or loading",
                "vm": null
            }
        """.trimIndent()

        val loadingState = qwenStateFrom(json.parseToJsonElement(loadingPayload))
        assertEquals("loading", loadingState.status)
        assertTrue(loadingState.gatewayAlive)
        assertFalse(loadingState.modelReady)
        assertEquals("Model upstream not ready or loading", loadingState.error)
        assertNull(loadingState.vm)

        val offlinePayload = """
            {
                "status": "offline",
                "gateway_alive": false,
                "model_ready": false,
                "model_alias": "qwen-agent",
                "gateway_url": "http://127.0.0.1:18080",
                "health_latency_ms": null,
                "ready_latency_ms": null,
                "last_checked_epoch": 1780000000,
                "error": "Gateway unreachable: connection refused",
                "vm": null
            }
        """.trimIndent()

        val offlineState = qwenStateFrom(json.parseToJsonElement(offlinePayload))
        assertEquals("offline", offlineState.status)
        assertFalse(offlineState.gatewayAlive)
        assertFalse(offlineState.modelReady)
        assertTrue(offlineState.error?.contains("Gateway unreachable") == true)
    }

    @Test
    fun `QwenActions posts start and stop actions`() = runTest {
        val hub = FakeHub("""{"ok": true, "msg": "VM power updated"}""")
        val actions = QwenActions(hub.actor)

        val startOutcome = actions.startVm("998877")
        assertIs<ActionOutcome.Succeeded>(startOutcome)
        assertEquals("/api/qwen/vm/power", hub.paths[0])

        val stopOutcome = actions.stopVm("998877")
        assertIs<ActionOutcome.Succeeded>(stopOutcome)
        assertEquals("/api/qwen/vm/power", hub.paths[1])
        assertEquals(listOf(HttpMethod.Post, HttpMethod.Post), hub.methods)
    }

    @Test
    fun `QwenApi calls get state with query parameters`() = runTest {
        val hub = FakeHub("""{"status": "ready", "gateway_alive": true, "model_ready": true}""")
        val api = QwenApi(hub.reader)

        val res = api.state("http://localhost:18080", "12345").okValue()
        assertEquals("ready", res.status)
        assertTrue(hub.paths[0].contains("gateway_url=http%3A%2F%2Flocalhost%3A18080"))
        assertTrue(hub.paths[0].contains("vast_instance_id=12345"))
    }
}
