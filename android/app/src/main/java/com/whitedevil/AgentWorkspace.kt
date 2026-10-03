package com.whitedevil

import com.whitedevil.agent.ConversationStore
import com.whitedevil.agent.McpDiscovery
import com.whitedevil.agent.McpRegistry
import com.whitedevil.agent.McpTools
import com.whitedevil.agent.MemoryStore
import com.whitedevil.agent.MemoryTools
import com.whitedevil.agent.SkillStore
import com.whitedevil.agent.SkillTools
import com.whitedevil.agent.ToolExtension
import java.io.File

/**
 * Everything the agent keeps on this phone beyond one chat: conversations, memory, skills and MCP
 * servers. All of it lives in the app's private storage ([root], normally `filesDir`).
 */
class AgentWorkspace(private val root: File) {
    val conversations = ConversationStore(File(root, "conversations"))
    val memory = MemoryStore(File(root, "memory.json"))
    val skills = SkillStore(File(root, "skills"))
    val mcp = McpRegistry(File(root, "mcp_servers.json"))

    @Volatile private var mcpCache: Pair<Long, List<McpDiscovery>>? = null

    /** The open conversation's id. On first use this migrates the old single chat, or starts a new one. */
    fun ensureCurrent(): String {
        conversations.currentId()?.let { return it }
        conversations.importLegacy(File(root, "agent_history.json"))?.let {
            conversations.setCurrent(it.id)
            return it.id
        }
        return conversations.create().id
    }

    fun invalidateMcp() { mcpCache = null }

    /** Connects to the enabled MCP servers (blocking, call off the main thread). Cached for 5 minutes. */
    fun mcpDiscoveries(force: Boolean = false): List<McpDiscovery> {
        val cached = mcpCache
        if (!force && cached != null && System.currentTimeMillis() - cached.first < MCP_TTL_MS) return cached.second
        val fresh = McpRegistry.discover(mcp.list())
        mcpCache = System.currentTimeMillis() to fresh
        return fresh
    }

    /** Extensions for one agent run. [confirm] must block for the user's Allow/Deny. */
    fun extensions(confirm: (title: String, detail: String) -> Boolean): List<ToolExtension> =
        listOf(MemoryTools(memory), SkillTools(skills), McpTools(mcpDiscoveries(), confirm))

    private companion object {
        const val MCP_TTL_MS = 5 * 60 * 1000L
    }
}
