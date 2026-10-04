package com.whitedevil.desktop.mcp

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Asks you before Venice acts on your computer or in your browser. A connector's `askBefore` globs in
 * mcp-servers.json name the tools that need a yes (clicks, typing, key presses, launching apps); reading the screen
 * and browsing do not.
 *
 * The agent runs tools on a background thread, so [ask] blocks there until you answer in the app (or it times out,
 * which counts as no). "Allow for this chat" covers that one tool until a new chat starts. Every decision is
 * appended to [log] so there is a record of what Venice did on the machine.
 */
class ApprovalGate(private val log: File? = null, private val timeoutMs: Long = 5 * 60_000L) {
    enum class Answer { Once, ForChat, Deny }

    class Pending(val server: String, val tool: String, val argumentsJson: String) {
        internal val answer = CompletableFuture<Answer>()
    }

    /** The action waiting for an answer, for the dialog. Null when nothing is waiting. */
    var pending by mutableStateOf<Pending?>(null)
        private set

    private val allowedForChat = mutableSetOf<String>()

    /** True if the call may run. Blocks the calling (tool) thread while the dialog is open. */
    fun ask(server: String, tool: String, argumentsJson: String): Boolean {
        val key = "$server/$tool"
        synchronized(allowedForChat) {
            if (key in allowedForChat) { record("auto-allowed (this chat)", server, tool, argumentsJson); return true }
        }
        val p = Pending(server, tool, argumentsJson)
        synchronized(this) {
            // One question at a time; a second tool call waits for the first to be answered.
            while (pending != null) (this as Object).wait(250)
            pending = p
        }
        val answer = try {
            p.answer.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            Answer.Deny
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt(); Answer.Deny
        } finally {
            synchronized(this) { if (pending === p) pending = null; (this as Object).notifyAll() }
        }
        if (answer == Answer.ForChat) synchronized(allowedForChat) { allowedForChat += key }
        record(when (answer) { Answer.Once -> "allowed once"; Answer.ForChat -> "allowed for this chat"; Answer.Deny -> "denied" }, server, tool, argumentsJson)
        return answer != Answer.Deny
    }

    /** Called by the dialog. */
    fun answer(a: Answer) { pending?.answer?.complete(a) }

    /** Interrupt: whatever is waiting is refused. */
    fun denyPending() { pending?.answer?.complete(Answer.Deny) }

    /** New chat: "allow for this chat" ends. */
    fun reset() { synchronized(allowedForChat) { allowedForChat.clear() }; denyPending() }

    private fun record(what: String, server: String, tool: String, args: String) {
        val f = log ?: return
        runCatching {
            f.parentFile?.mkdirs()
            val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
            f.appendText("$stamp  $what  $server/$tool  ${args.replace("\n", " ").take(500)}\n")
        }
    }
}
