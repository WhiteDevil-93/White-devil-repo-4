package com.whitedevil.desktop

import androidx.compose.runtime.*
import com.whitedevil.agent.Agent
import com.whitedevil.agent.ChatMessage
import com.whitedevil.agent.stripBlobs
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Window-owned state: navigating away must not cancel a goal or discard a draft. */
class AgentSession(private val file: File = File(Settings.dir, "agent-history.json")) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val lines = mutableStateListOf<ChatLine>()
    var input by mutableStateOf("")
    var busy by mutableStateOf(false)
    var job: Job? = null
    var history: List<ChatMessage> = emptyList()
        private set
    var saveStatus by mutableStateOf("No saved conversation")
        private set
    var loadFailed by mutableStateOf(false)
        private set
    private val json = Json { ignoreUnknownKeys = true }

    init {
        if (file.exists()) {
            try {
                history = json.decodeFromString<List<ChatMessage>>(file.readText())
                history.forEach { message ->
                    val text = message.textContent()
                    if (text.isNotBlank()) lines += ChatLine(
                        when (message.role) { "assistant" -> "venice"; "tool" -> "tool_out"; else -> message.role },
                        when (message.role) { "user" -> "You"; "assistant" -> "Venice"; else -> message.name ?: "Tool output" }, text,
                    )
                }
                saveStatus = "Saved locally"
            } catch (e: Exception) {
                loadFailed = true
                saveStatus = "History could not be loaded; original file preserved: ${e.javaClass.simpleName}"
            }
        }
    }

    fun adopt(messages: List<ChatMessage>) {
        history = messages.filter { it.role in setOf("user", "assistant", "tool") }
            .takeLast(Agent.MAX_HISTORY_MESSAGES).map { it.copy(content = stripBlobs(it.content)) }
        save()
    }

    fun save() {
        if (loadFailed) return // Explicit Clear is required; never overwrite unreadable history.
        try {
            file.parentFile?.mkdirs()
            val temporary = File(file.parentFile, "${file.name}.tmp")
            temporary.writeText(json.encodeToString(history))
            try {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            saveStatus = "Saved locally"
        } catch (e: Exception) {
            saveStatus = "Not saved — retry saving: ${e.javaClass.simpleName}"
        }
    }

    fun clear() {
        if (busy) return
        loadFailed = false
        history = emptyList()
        lines.clear()
        save()
    }

    fun close() { scope.cancel() }
}
