package com.whitedevil.agentic

/**
 * Side-track agentic client stubs - not wired into MainActivity.
 * Talks to Hub /api/agentic/ endpoints when a future settings toggle opts in.
 * Default app Agent tab keeps using the main Agent unchanged.
 */
data class AgenticStatus(
    val enabled: Boolean = false,
    val jobsRunning: Int = 0,
    val hint: String = "",
)

data class AgenticJob(
    val id: String,
    val goal: String,
    val status: String,
    val step: Int? = null,
    val result: String? = null,
    val error: String? = null,
)

object AgenticDefaults {
    const val PATH_STATUS = "/api/agentic/status"
    const val PATH_JOBS = "/api/agentic/jobs"
    const val PATH_CONFIG = "/api/agentic/config"
    const val PATH_MEMORY = "/api/agentic/memory"
    const val PATH_PERMISSIONS = "/api/agentic/permissions"
    /** Settings key - when false (default), UI must not call side-track runners. */
    const val PREF_SIDE_TRACK = "agentic_side_track_enabled"
}
