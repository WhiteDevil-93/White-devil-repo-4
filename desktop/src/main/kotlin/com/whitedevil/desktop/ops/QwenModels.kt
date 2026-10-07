package com.whitedevil.desktop.ops

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Operations state for Qwen API and its GPU host VM, returned from GET /api/qwen/state.
 */
data class QwenVmInfo(
    val instanceId: String,
    val status: String?,
    val gpu: String?,
    val vramGb: Int?,
    val pricePerHour: Double?,
    val stoppedPricePerHour: Double?,
    val host: String?,
    val port: Int?,
)

data class QwenState(
    val status: String,
    val gatewayAlive: Boolean,
    val modelReady: Boolean,
    val modelAlias: String,
    val gatewayUrl: String,
    val healthLatencyMs: Double?,
    val readyLatencyMs: Double?,
    val activeRequests: Int?,
    val maxConcurrency: Int?,
    val lastCheckedEpoch: Long,
    val error: String?,
    val vm: QwenVmInfo?,
)

fun qwenStateFrom(element: JsonElement): QwenState {
    val obj = element as? JsonObject ?: return QwenState(
        status = "offline",
        gatewayAlive = false,
        modelReady = false,
        modelAlias = "qwen-agent",
        gatewayUrl = "",
        healthLatencyMs = null,
        readyLatencyMs = null,
        activeRequests = null,
        maxConcurrency = null,
        lastCheckedEpoch = 0L,
        error = "Malformed payload from hub",
        vm = null,
    )

    val vmObj = obj.obj("vm")
    val vmInfo = if (vmObj != null && vmObj.containsKey("instance_id")) {
        QwenVmInfo(
            instanceId = vmObj.nonBlankStr("instance_id") ?: "",
            status = vmObj.nonBlankStr("status"),
            gpu = vmObj.nonBlankStr("gpu"),
            vramGb = vmObj.int("vram_gb"),
            pricePerHour = vmObj.num("price_per_hour"),
            stoppedPricePerHour = vmObj.num("stopped_price_per_hour"),
            host = vmObj.nonBlankStr("host"),
            port = vmObj.int("port"),
        )
    } else null

    return QwenState(
        status = obj.nonBlankStr("status") ?: "offline",
        gatewayAlive = obj.bool("gateway_alive") ?: false,
        modelReady = obj.bool("model_ready") ?: false,
        modelAlias = obj.nonBlankStr("model_alias") ?: "qwen-agent",
        gatewayUrl = obj.nonBlankStr("gateway_url") ?: "",
        healthLatencyMs = obj.num("health_latency_ms"),
        readyLatencyMs = obj.num("ready_latency_ms"),
        activeRequests = obj.int("active_requests"),
        maxConcurrency = obj.int("max_concurrency"),
        lastCheckedEpoch = obj.num("last_checked_epoch")?.toLong() ?: 0L,
        error = obj.nonBlankStr("error"),
        vm = vmInfo,
    )
}

const val QWEN_POLL_MS = 15_000L

class QwenApi(private val reader: OpsReader) {
    suspend fun state(gatewayUrl: String? = null, vastId: String? = null): OpsResult<QwenState> {
        val q = mutableListOf<String>()
        if (!gatewayUrl.isNullOrBlank()) q.add("gateway_url=${java.net.URLEncoder.encode(gatewayUrl, "UTF-8")}")
        if (!vastId.isNullOrBlank()) q.add("vast_instance_id=${java.net.URLEncoder.encode(vastId, "UTF-8")}")
        val path = if (q.isEmpty()) "/api/qwen/state" else "/api/qwen/state?${q.joinToString("&")}"
        return reader.getJson(path).map { qwenStateFrom(it) }
    }
}

class QwenActions(private val actor: OpsActor) {
    suspend fun startVm(instanceId: String): ActionOutcome {
        val body = buildJsonObject {
            put("instance_id", instanceId)
            put("action", "start")
        }
        return actor.post("/api/qwen/vm/power", body).toOutcome { interpretGeneric(it, "Start Qwen VM") }
    }

    suspend fun stopVm(instanceId: String): ActionOutcome {
        val body = buildJsonObject {
            put("instance_id", instanceId)
            put("action", "stop")
        }
        return actor.post("/api/qwen/vm/power", body).toOutcome { interpretGeneric(it, "Stop Qwen VM") }
    }
}
