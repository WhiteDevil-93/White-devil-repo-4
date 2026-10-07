package com.whitedevil.agent

/**
 * A bundle of tools the host plugs into [ToolBox]: memory, skills, MCP servers.
 * The host builds the list of extensions per agent run; [ToolBox] offers their definitions to the
 * model and routes calls whose name an extension [handles].
 */
interface ToolExtension {
    val definitions: List<ToolDefinition>
        get() = definitions()

    fun definitions(): List<ToolDefinition> = emptyList()

    fun handles(name: String): Boolean = (definitions.ifEmpty { definitions() }).any { it.function.name == name }

    /** Runs the tool and returns text for the model. Must not throw for ordinary failures: return "Error: ...". */
    fun execute(name: String, argumentsJson: String): String

    /** Text plus any images (screenshots) for a vision model. Text-only extensions need not override it. */
    fun executeDetailed(name: String, argumentsJson: String): ToolExecution = ToolExecution(execute(name, argumentsJson))

    /** Text appended to the system prompt each run (memories, skill index, ...). Empty = nothing. */
    fun promptBlock(): String = ""
}
