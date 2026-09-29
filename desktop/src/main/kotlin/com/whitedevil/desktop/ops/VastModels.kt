package com.whitedevil.desktop.ops

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URLEncoder

/*
 * Vast.ai, from hub/vast.py. Routes relied on:
 *   GET  /api/vast/state                   vast.py:97-106   ({credit, instances[], image})
 *   GET  /api/vast/offers?min_vram&gpu&max_price&disk_gb&num_gpus   vast.py:142-150  (list)
 * Mutating (each behind a typed confirmation):
 *   POST /api/vast/instances               vast.py:198-200  rent: {"id": "<contract>"} or an HTTP error
 *   POST /api/vast/instances/{id}/start    vast.py:209-211  (bills again)
 *   POST /api/vast/instances/{id}/stop     vast.py:214-216  (GPU released, disk keeps billing)
 *   POST /api/vast/instances/{id}/delete   vast.py:219-221  (destroys the instance and its disk)
 * Not offered: /label.
 *
 * Hub behaviours the UI must not misread:
 *  - `credit` is 0.0 when Vast omitted it (vast.py:103-106), so 0 is "possibly unknown".
 *  - start/stop/delete pass Vast's reply through; Vast can answer HTTP 200 with success:false.
 */

data class VastInstance(
    val id: String,
    val label: String?,
    val gpu: String?,
    val numGpus: Int?,
    val vramGb: Int?,
    val status: String?,
    val intended: String?,
    val statusMsg: String?,
    val pricePerHour: Double?,
    val stoppedPricePerHour: Double?,
    val diskGb: Int?,
    val location: String?,
    val host: String?,
    val port: Int?,
    val startedEpochSec: Double?,
    val gpuUtil: Double?,
    val diskUsedGb: Double?,
) {
    val running: Boolean get() = status.equals("running", ignoreCase = true)
    val stopped: Boolean get() = status.equals("exited", ignoreCase = true) || intended.equals("stopped", ignoreCase = true)
    val sshCommand: String? get() = host?.let { "ssh -p ${port ?: 22} root@$it" }
}

data class VastState(val credit: Double?, val instances: List<VastInstance>, val image: String?) {
    companion object {
        fun parse(json: JsonElement): OpsResult<VastState> {
            val o = json as? JsonObject ?: return shapeError("Vast state", "a JSON object", json)
            val arr = o["instances"] as? JsonArray
                ?: return OpsResult.Err(
                    OpsError(
                        "The hub's Vast state has no instance list; it sent: ${o.keys.take(12).joinToString(", ").ifEmpty { "an empty object" }}.",
                        kind = OpsErrorKind.BadShape, body = capBody(o.toString()),
                    ),
                )
            return OpsResult.Ok(
                VastState(
                    credit = o.num("credit"),
                    instances = arr.objects().mapNotNull(::parseInstance),
                    image = o.nonBlankStr("image"),
                ),
            )
        }

        private fun parseInstance(i: JsonObject): VastInstance? {
            val id = i.nonBlankStr("id") ?: return null
            return VastInstance(
                id = id, label = i.nonBlankStr("label"), gpu = i.nonBlankStr("gpu"), numGpus = i.int("num_gpus"),
                vramGb = i.int("vram_gb"), status = i.nonBlankStr("status"), intended = i.nonBlankStr("intended"),
                statusMsg = i.nonBlankStr("status_msg"), pricePerHour = i.num("price"),
                stoppedPricePerHour = i.num("stopped_price"), diskGb = i.int("disk_gb"),
                location = i.nonBlankStr("location"), host = i.nonBlankStr("host"), port = i.int("port"),
                startedEpochSec = i.num("started"), gpuUtil = i.num("gpu_util"), diskUsedGb = i.num("disk_used_gb"),
            )
        }
    }
}

/** Search filters, with the hub's defaults (vast.py:143-144). */
data class OfferQuery(
    val minVramGb: Double = 40.0,
    val gpu: String? = null,
    val maxPrice: Double? = null,
    val diskGb: Int = 150,
    val numGpus: Int = 1,
) {
    fun path(): String {
        val q = buildList {
            add("min_vram=$minVramGb")
            gpu?.trim()?.takeIf { it.isNotEmpty() }?.let { add("gpu=" + URLEncoder.encode(it, "UTF-8")) }
            maxPrice?.let { add("max_price=$it") }
            add("disk_gb=$diskGb")
            add("num_gpus=$numGpus")
        }
        return "/api/vast/offers?" + q.joinToString("&")
    }
}

data class VastOffer(
    val id: String,
    val gpu: String?,
    val numGpus: Int?,
    val vramGb: Int?,
    /** $/h including the disk the search asked for (hub: dph_base + disk*storage/730). */
    val pricePerHour: Double?,
    val stoppedPricePerHour: Double?,
    val diskMaxGb: Int?,
    val location: String?,
    val reliabilityPct: Double?,
    val downMbps: Int?,
    val cpuCores: Int?,
    val ramGb: Int?,
    val cuda: String?,
    val rentable: Boolean?,
)

/** Offers together with the query they answered — the price is only valid for that disk size. */
data class VastOffers(val query: OfferQuery, val offers: List<VastOffer>) {
    companion object {
        fun parse(json: JsonElement, query: OfferQuery): OpsResult<VastOffers> {
            val arr = json as? JsonArray ?: return shapeError("Vast offers", "a JSON array", json)
            val offers = arr.objects().mapNotNull { o ->
                val id = o.nonBlankStr("id") ?: return@mapNotNull null
                VastOffer(
                    id = id, gpu = o.nonBlankStr("gpu"), numGpus = o.int("num_gpus"), vramGb = o.int("vram_gb"),
                    pricePerHour = o.num("price"), stoppedPricePerHour = o.num("stopped_price"),
                    diskMaxGb = o.int("disk_max_gb"), location = o.nonBlankStr("location"),
                    reliabilityPct = o.num("reliability"), downMbps = o.int("down_mbps"),
                    cpuCores = o.int("cpu_cores"), ramGb = o.int("ram_gb"), cuda = o.nonBlankStr("cuda"),
                    rentable = o.bool("rentable"),
                )
            }
            return OpsResult.Ok(VastOffers(query, offers))
        }
    }
}

/** Ids the hub accepts (vast.py:25 `^\d{1,12}$`). */
private val VAST_ID = Regex("^\\d{1,12}$")

/** Reads only. */
class VastApi(private val reader: OpsReader) {
    suspend fun state(): OpsResult<VastState> =
        reader.getJson(STATE_PATH, 60_000L).flatMap { VastState.parse(it) }

    /** A GET on the hub, which runs the search against Vast. It rents nothing. */
    suspend fun offers(query: OfferQuery): OpsResult<VastOffers> =
        reader.getJson(query.path(), 90_000L).flatMap { VastOffers.parse(it, query) }

    companion object {
        const val STATE_PATH = "/api/vast/state"
    }
}

/** The mutating calls. Only ever run from behind [ActionController.confirm]. */
class VastActions(private val actor: OpsActor) {
    /** [diskGb] must be the disk size the offer was priced at, i.e. [VastOffers.query]'s. */
    suspend fun rent(offerId: String, diskGb: Int, label: String?): ActionOutcome {
        if (!VAST_ID.matches(offerId)) return localRefusal("Refusing to send: \"$offerId\" is not a valid offer id.")
        val body = buildJsonObject {
            put("offer_id", offerId)
            put("disk_gb", diskGb)
            put("label", label?.trim()?.takeIf { it.isNotEmpty() })
        }
        return actor.post("/api/vast/instances", body, timeoutMs = 120_000L).toOutcome(::interpretVastRent)
    }

    suspend fun start(id: String) = instanceAction(id, "start", "Start Vast instance")
    suspend fun stop(id: String) = instanceAction(id, "stop", "Stop Vast instance")
    suspend fun delete(id: String) = instanceAction(id, "delete", "Delete Vast instance")

    private suspend fun instanceAction(id: String, verb: String, what: String): ActionOutcome {
        if (!VAST_ID.matches(id)) return localRefusal("Refusing to send: \"$id\" is not a valid instance id.")
        return actor.post("/api/vast/instances/$id/$verb", timeoutMs = 90_000L).toOutcome { interpretGeneric(it, "$what $id") }
    }
}

/** Rent succeeds only with a contract id (vast.py:195); anything else is not a rental. */
internal fun interpretVastRent(reply: HubReply): ActionOutcome {
    val o = reply.json as? JsonObject ?: return notAnObject(reply, "Rent Vast offer")
    val id = o.nonBlankStr("id")
    val raw = capBody(reply.rawBody)
    return if (id != null) {
        ActionOutcome.Succeeded(
            headline = "Vast accepted the rental: new instance $id. It shows as loading until the host boots it, and it bills from now until you stop or delete it.",
            rawBody = raw, status = reply.status,
        )
    } else {
        ActionOutcome.Failed(
            headline = "The hub answered HTTP ${reply.status} but returned no instance id, so no rental is confirmed.",
            rawBody = raw, status = reply.status, mayHaveExecuted = true,
        )
    }
}
