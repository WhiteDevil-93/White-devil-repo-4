package com.whitedevil.desktop.ops

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * Thunder Compute, from hub/thunder.py. Routes relied on:
 *   GET  /api/thunder/state                 thunder.py:101-112  (instances[], snapshots, pricing, specs, templates, status)
 *   GET  /api/thunder/queue                 thunder.py:220-237  ({runner, comfy{}, jobs[]})
 * Mutating (each behind a confirmation dialog):
 *   POST /api/thunder/queue                 thunder.py:247-296  submit14: HTTP 200 with ok:false on partial failure
 *   POST /api/thunder/queue/{jid}/{action}  thunder.py:299-303  cancel|retry (runner reply passed through)
 *   POST /api/thunder/instances             thunder.py:123-126  create (bills)
 *   POST /api/thunder/instances/{id}/delete thunder.py:135-137
 *   POST /api/thunder/snapshots             thunder.py:170-172
 *   POST /api/thunder/snapshots/{id}/delete thunder.py:175-177
 * Not offered here (use the web screen): modify/resize and ports.
 *
 * Hub behaviours the UI must not misread:
 *  - /state swallows a failed snapshot listing and returns [] (thunder.py:106-109), and
 *    pricing/specs/templates/status come back null when their fetch failed with no cache.
 *  - /queue swallows a failed runner call and returns runner:false, jobs:[] (thunder.py:229-230).
 *  - /state has a server-side effect: it may repoint and restart the Comfy tunnel
 *    (follow_instance, thunder.py:84-98). A read from our side, not a spend.
 */

data class ThunderInstance(
    val id: String,
    val name: String?,
    val status: String?,
    val ip: String?,
    val port: Int?,
    val gpuType: String?,
    val numGpus: Int?,
    val cpuCores: Int?,
    val storageGb: Int?,
    val template: String?,
    val createdAt: String?,
    val httpPorts: List<String>,
    val memoryGb: Int?,
) {
    val running: Boolean get() = status.equals("running", ignoreCase = true)

    /** ssh command as the web screen builds it; null without an address. */
    val sshCommand: String? get() = ip?.let { "ssh -p ${port ?: 22} ubuntu@$it" }
}

data class ThunderSnapshot(val id: String, val name: String?, val status: String?, val createdAt: String?, val minDiskGb: Int?)
data class ThunderSpec(val key: String, val displayName: String?, val vramGb: Int?, val vcpuOptions: List<Int>)
data class ThunderTemplate(val value: String, val label: String)

data class ThunderState(
    val instances: List<ThunderInstance>,
    val snapshots: List<ThunderSnapshot>,
    /** "l40_x1" -> $/h, plus "disk_gb". Null when the hub could not fetch pricing. */
    val pricing: Map<String, Double>?,
    val specs: Map<String, ThunderSpec>?,
    val templates: List<ThunderTemplate>?,
    /** "l40_x1" -> "available" | ... . Null when the hub could not fetch status. */
    val stock: Map<String, String>?,
) {
    /** $/h for the given GPU type and count, from the pricing table, or null when not derivable. */
    fun ratePerHour(gpuType: String?, numGpus: Int?): Double? {
        val p = pricing ?: return null
        val g = gpuType?.lowercase() ?: return null
        val n = numGpus ?: 1
        return p["${g}_x$n"] ?: p[g]?.times(n)
    }

    /** GPU type keys ("l40") from specs, falling back to the web screen's list. */
    fun gpuTypes(): List<String> {
        val fromSpecs = specs?.keys?.map { it.replace(Regex("_x\\d+$"), "") }?.distinct().orEmpty()
        return fromSpecs.ifEmpty { listOf("t4", "a100xl", "h100") }
    }

    fun gpuLabel(type: String): String {
        val s = specs?.get("${type}_x1") ?: specs?.entries?.firstOrNull { it.key.startsWith("${type}_x") }?.value
        return s?.displayName?.removePrefix("NVIDIA ")?.ifBlank { null } ?: type.uppercase()
    }

    companion object {
        fun parse(json: JsonElement): OpsResult<ThunderState> {
            val o = json as? JsonObject ?: return shapeError("Thunder state", "a JSON object", json)
            val rawInstances = o["instances"]
            val instances: List<ThunderInstance> = when (rawInstances) {
                is JsonArray -> rawInstances.objects().mapNotNull(::parseInstance)
                is JsonObject -> rawInstances.entries.mapNotNull { (k, v) -> (v as? JsonObject)?.let { parseInstance(JsonObject(it + ("id" to JsonPrimitive(k)))) } }
                else -> return OpsResult.Err(
                    OpsError(
                        "The hub's Thunder state has no instance list; it sent: ${o.keys.take(12).joinToString(", ").ifEmpty { "an empty object" }}.",
                        kind = OpsErrorKind.BadShape, body = capBody(o.toString()),
                    ),
                )
            }
            val snaps = when (val s = o["snapshots"]) {
                is JsonArray -> s.objects().mapNotNull(::parseSnapshot)
                is JsonObject -> s.entries.mapNotNull { (k, v) -> (v as? JsonObject)?.let { parseSnapshot(JsonObject(it + ("id" to JsonPrimitive(k)))) } }
                else -> emptyList()
            }
            return OpsResult.Ok(
                ThunderState(
                    instances = instances,
                    snapshots = snaps,
                    pricing = o.obj("pricing")?.obj("pricing")?.numberMap(),
                    specs = o.obj("specs")?.obj("specs")?.let { m ->
                        m.entries.mapNotNull { (k, v) -> (v as? JsonObject)?.let { sp -> k to parseSpec(k, sp) } }.toMap()
                    },
                    templates = parseTemplates(o["templates"]),
                    stock = o.obj("status")?.obj("specs")?.let { m ->
                        m.entries.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.content?.let { c -> k to c } }.toMap()
                    },
                ),
            )
        }

        private fun JsonObject.numberMap(): Map<String, Double> =
            entries.mapNotNull { (k, _) -> num(k)?.let { k to it } }.toMap()

        private fun parseInstance(i: JsonObject): ThunderInstance? {
            val id = i.nonBlankStr("id") ?: return null
            return ThunderInstance(
                id = id, name = i.nonBlankStr("name"), status = i.nonBlankStr("status"),
                ip = i.nonBlankStr("ip"), port = i.int("port"), gpuType = i.nonBlankStr("gpuType"),
                numGpus = i.int("numGpus"), cpuCores = i.int("cpuCores"), storageGb = i.int("storage"),
                template = i.nonBlankStr("template"), createdAt = i.nonBlankStr("createdAt"),
                httpPorts = i.arr("httpPorts").strings(), memoryGb = i.int("memory"),
            )
        }

        private fun parseSnapshot(s: JsonObject): ThunderSnapshot? {
            val id = s.nonBlankStr("id") ?: return null
            return ThunderSnapshot(id, s.nonBlankStr("name"), s.nonBlankStr("status"), s.nonBlankStr("createdAt"), s.int("minimumDiskSizeGb"))
        }

        private fun parseSpec(key: String, s: JsonObject) = ThunderSpec(
            key = key, displayName = s.nonBlankStr("displayName"), vramGb = s.int("vramGB"),
            vcpuOptions = s.arr("vcpuOptions")?.mapNotNull { (it as? JsonPrimitive)?.content?.toDoubleOrNull()?.toInt() }.orEmpty(),
        )

        private fun parseTemplates(t: JsonElement?): List<ThunderTemplate>? = when (t) {
            is JsonArray -> t.mapNotNull { x ->
                when (x) {
                    is JsonPrimitive -> x.content.takeIf { it.isNotBlank() }?.let { ThunderTemplate(it, it) }
                    is JsonObject -> {
                        val v = x.nonBlankStr("name") ?: x.nonBlankStr("id")
                        v?.let { ThunderTemplate(it, x.nonBlankStr("displayName") ?: x.nonBlankStr("display_name") ?: it) }
                    }
                    else -> null
                }
            }
            is JsonObject -> t.entries.map { (k, v) ->
                ThunderTemplate(k, (v as? JsonObject)?.let { it.nonBlankStr("displayName") ?: it.nonBlankStr("display_name") } ?: k)
            }
            else -> null
        }
    }
}

data class ComfyStatus(val online: Boolean?, val why: String?, val running: Int?, val pending: Int?, val runningPrefix: String?)

data class ThunderJob(
    val id: String?,
    val name: String?,
    val chainId: String?,
    val status: String?,
    val done: Int?,
    val total: Int?,
    val currentClip: Int?,
    val avgSeconds: Int?,
    val created: String?,
    val error: String?,
) {
    val canCancel: Boolean get() = status in setOf("queued", "rendering", "waiting")
    val canRetry: Boolean get() = status in setOf("error", "cancelled")
}

data class ThunderQueue(val runnerUp: Boolean?, val comfy: ComfyStatus?, val jobs: List<ThunderJob>) {
    companion object {
        fun parse(json: JsonElement): OpsResult<ThunderQueue> {
            val o = json as? JsonObject ?: return shapeError("Thunder queue", "a JSON object", json)
            if ("runner" !in o && "jobs" !in o) {
                return OpsResult.Err(
                    OpsError(
                        "The hub's Thunder queue has neither runner nor jobs; it sent: ${o.keys.take(12).joinToString(", ").ifEmpty { "an empty object" }}.",
                        kind = OpsErrorKind.BadShape, body = capBody(o.toString()),
                    ),
                )
            }
            return OpsResult.Ok(
                ThunderQueue(
                    runnerUp = o.bool("runner"),
                    comfy = o.obj("comfy")?.let {
                        ComfyStatus(it.bool("online"), it.nonBlankStr("why"), it.int("running"), it.int("pending"), it.nonBlankStr("running_prefix"))
                    },
                    jobs = o.arr("jobs").objects().map { j ->
                        val p = j.obj("progress")
                        ThunderJob(
                            id = j.nonBlankStr("id"), name = j.nonBlankStr("name"), chainId = j.nonBlankStr("chain_id"),
                            status = j.nonBlankStr("status"), done = p?.int("done"), total = p?.int("total"),
                            currentClip = j.int("current"), avgSeconds = j.int("avg_seconds"),
                            created = j.nonBlankStr("created"), error = j.nonBlankStr("error"),
                        )
                    },
                ),
            )
        }
    }
}

/** Ids the hub accepts (thunder.py:24 `^[\w-]{1,64}$`); checked here too so a bad id never becomes a URL. */
private val THUNDER_ID = Regex("^[\\w-]{1,64}$")

/** Reads only. */
class ThunderApi(private val reader: OpsReader) {
    suspend fun state(): OpsResult<ThunderState> =
        reader.getJson(STATE_PATH, 120_000L).flatMap { ThunderState.parse(it) }

    suspend fun queue(): OpsResult<ThunderQueue> =
        reader.getJson(QUEUE_PATH, 60_000L).flatMap { ThunderQueue.parse(it) }

    companion object {
        const val STATE_PATH = "/api/thunder/state"
        const val QUEUE_PATH = "/api/thunder/queue"
    }
}

/**
 * A chain the operator picked from disk, validated the way the hub validates it
 * (thunder.py:250: type chain|chain_part and non-empty clips) before anything is sent.
 */
data class ChainSpec(val spec: JsonObject, val clipCount: Int, val suggestedName: String, val sourceFiles: Int) {
    companion object {
        /** Merges part files like the web screen: clips sorted by index, duplicate indexes dropped. */
        fun fromFiles(texts: List<String>): OpsResult<ChainSpec> {
            if (texts.isEmpty()) return err("No file chosen.")
            val specs = texts.mapIndexed { i, t ->
                (parseJsonOrNull(t) as? JsonObject) ?: return err("File ${i + 1} is not a JSON object.")
            }
            val clips = specs.flatMap { it.arr("clips").objects() }
                .sortedBy { it.int("index") ?: Int.MAX_VALUE }
                .let { sorted ->
                    val seen = HashSet<Int?>()
                    sorted.filter { c -> val k = c.int("index"); k == null || seen.add(k) }
                }
            if (clips.isEmpty()) return err("No clips in that file, so the hub would refuse it.")
            val base = specs.first()
            val type = base.nonBlankStr("type")
            if (specs.size == 1 && type != "chain" && type != "chain_part") {
                return err("That file's type is \"${type ?: "missing"}\"; the hub only accepts chain or chain_part.")
            }
            val baseId = base.nonBlankStr("chain_id")
                ?: base.obj("brief")?.nonBlankStr("idea")?.lowercase()?.replace(Regex("[^a-z0-9]+"), "-")?.take(40)
                ?: "chain"
            val chainId = baseId.replace(Regex("-part\\d+$"), "") + "-14b"
            val merged = JsonObject(
                base.filterKeys { it != "part" && it != "parts" } + mapOf(
                    "type" to JsonPrimitive("chain"),
                    "clips" to JsonArray(clips),
                    "chain_id" to JsonPrimitive(chainId),
                    "settings" to JsonObject((base.obj("settings") ?: JsonObject(emptyMap())) + ("clip_count" to JsonPrimitive(clips.size))),
                ),
            )
            return OpsResult.Ok(ChainSpec(merged, clips.size, chainId.replace('-', '_'), specs.size))
        }

        private fun err(msg: String) = OpsResult.Err(OpsError(msg, kind = OpsErrorKind.BadShape))
    }
}

/** The mutating calls. Only ever run from behind [ActionController.confirm]. */
class ThunderActions(private val actor: OpsActor) {
    suspend fun submit(chain: ChainSpec, name: String?, seed: Int?, nextUp: Boolean): ActionOutcome =
        actor.post(QUEUE_PATH, submitBody(chain, name, seed, nextUp), timeoutMs = 150_000L).toOutcome(::interpretThunderSubmit)

    suspend fun jobAction(jobId: String, action: String): ActionOutcome {
        if (action != "cancel" && action != "retry") return localRefusal("Unknown job action \"$action\".")
        if (!THUNDER_ID.matches(jobId)) return localRefusal("Refusing to send: \"$jobId\" is not a valid job id.")
        return actor.post("$QUEUE_PATH/$jobId/$action").toOutcome { interpretGeneric(it, "${action.replaceFirstChar(Char::uppercase)} job $jobId") }
    }

    suspend fun createInstance(gpuType: String, numGpus: Int, cpuCores: Int, template: String, diskGb: Int): ActionOutcome {
        val body = buildJsonObject {
            put("gpu_type", gpuType); put("num_gpus", numGpus); put("cpu_cores", cpuCores)
            put("template", template); put("disk_size_gb", diskGb)
        }
        return actor.post("/api/thunder/instances", body, timeoutMs = 120_000L).toOutcome { interpretGeneric(it, "Create Thunder instance") }
    }

    suspend fun deleteInstance(id: String): ActionOutcome {
        if (!THUNDER_ID.matches(id)) return localRefusal("Refusing to send: \"$id\" is not a valid instance id.")
        return actor.post("/api/thunder/instances/$id/delete", timeoutMs = 120_000L).toOutcome { interpretGeneric(it, "Delete Thunder instance $id") }
    }

    suspend fun createSnapshot(instanceId: String, name: String): ActionOutcome {
        if (!THUNDER_ID.matches(instanceId)) return localRefusal("Refusing to send: \"$instanceId\" is not a valid instance id.")
        val body = buildJsonObject { put("instance_id", instanceId); put("name", name.take(60)) }
        return actor.post("/api/thunder/snapshots", body, timeoutMs = 120_000L).toOutcome { interpretGeneric(it, "Snapshot instance $instanceId") }
    }

    suspend fun deleteSnapshot(id: String): ActionOutcome {
        if (!THUNDER_ID.matches(id)) return localRefusal("Refusing to send: \"$id\" is not a valid snapshot id.")
        return actor.post("/api/thunder/snapshots/$id/delete").toOutcome { interpretGeneric(it, "Delete snapshot $id") }
    }

    companion object {
        const val QUEUE_PATH = "/api/thunder/queue"

        internal fun submitBody(chain: ChainSpec, name: String?, seed: Int?, nextUp: Boolean): JsonObject = buildJsonObject {
            put("spec", chain.spec)
            put("name", name?.trim()?.takeIf { it.isNotEmpty() })
            put("seed", seed)
            put("first", nextUp)
        }
    }
}

internal fun localRefusal(msg: String) = ActionOutcome.Failed(headline = "$msg Nothing was sent to the hub.")

/**
 * submit14 answers HTTP 200 with ok:false when the job was queued but the jobs it
 * jumped ahead of could not be put back (thunder.py:289-296). So: success only
 * when ok is true, and a failure lists which parts worked.
 */
internal fun interpretThunderSubmit(reply: HubReply): ActionOutcome {
    val o = reply.json as? JsonObject ?: return notAnObject(reply, "Submit chain")
    val ok = o.bool("ok")
    val id = o.nonBlankStr("id")
    val clips = o.int("clips")
    val requeued = o.int("requeued")
    val warning = o.nonBlankStr("warning")
    val raw = capBody(reply.rawBody)
    if (ok == true) {
        return ActionOutcome.Succeeded(
            headline = "Queued job ${id ?: "(the runner returned no job id)"} on the 14B runner${clips?.let { " — $it clips" } ?: ""}.",
            detail = requeued?.takeIf { it > 0 }?.let { "$it queued job(s) it jumped ahead of were put back in the queue." },
            rawBody = raw, status = reply.status,
        )
    }
    val parts = buildList {
        add(OutcomePart("Job accepted by the 14B runner", id != null, if (id != null) "job $id${clips?.let { ", $it clips" } ?: ""}" else "no job id in the reply"))
        if (warning != null || requeued != null) {
            add(OutcomePart("Jobs it jumped ahead of put back in the queue", warning == null, warning ?: "$requeued re-queued"))
        }
    }
    return ActionOutcome.Failed(
        headline = if (ok == false) "Submitted with problems: the hub answered HTTP ${reply.status} but reported ok=false."
        else "The hub did not confirm the submit: its reply has no ok flag (HTTP ${reply.status}).",
        parts = parts, rawBody = raw, status = reply.status, mayHaveExecuted = ok == null,
    )
}
