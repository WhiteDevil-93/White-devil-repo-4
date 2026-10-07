package com.whitedevil.agent

import kotlinx.serialization.json.*

/** Only the latest refresh may publish data, including after returning to the same tab. */
class LatestRequestGate {
    private var generation = 0L
    fun next(): Long = ++generation
    fun accepts(ticket: Long): Boolean = ticket == generation
}

/** HTTP success means transport success, not job completion. */
fun actionReplySummary(body: String): String {
    val reply = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull()
        ?: return "Request accepted"
    fun value(key: String) = (reply[key] as? JsonPrimitive)?.contentOrNull
    val status = value("status")
    if (value("ok") == "false" || value("success") == "false" || status in setOf("error", "failed")) {
        throw IllegalStateException(value("error")?.take(120)?.takeIf { it.isNotBlank() } ?: "Request rejected")
    }
    val id = value("job_id") ?: value("id")
    return (status?.takeIf { it.isNotBlank() } ?: "Request accepted") +
        (id?.takeIf { it.isNotBlank() }?.let { " · ${it.take(64)}" } ?: "")
}
