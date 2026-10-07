package com.whitedevil.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import java.nio.file.Files
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class AgentNavigationUiTest {
    @Test fun `leaving screen retains draft and run until explicit stop`() = runComposeUiTest {
        val session = AgentSession(Files.createTempDirectory("wd-navigation-").toFile().resolve("history.json"))
        try {
            setContent {
                var away by remember { mutableStateOf(false) }
                MaterialTheme(colorScheme = WhiteDevilColors) {
                    Column {
                        TextButton(onClick = { away = !away }) { Text(if (away) "Return to Agent" else "Navigate away") }
                        if (!away) AgentScreen(Settings(veniceApiKey = "offline-key"), session) {}
                    }
                }
            }
            onNodeWithText("Goal").performTextInput("unfinished goal")
            runOnIdle {
                session.busy = true
                session.job = session.scope.launch { try { awaitCancellation() } finally { session.busy = false } }
            }
            onNodeWithText("Navigate away").performClick()
            runOnIdle { assertTrue(session.job!!.isActive); assertEquals("unfinished goal", session.input) }
            onNodeWithText("Return to Agent").performClick()
            onNodeWithText("unfinished goal").assertExists()
            onNodeWithText("Stop").performClick()
            waitUntil { session.job!!.isCompleted }
            runOnIdle { assertEquals("unfinished goal", session.input); assertFalse(session.busy) }
        } finally { session.close() }
    }
    @Test fun `tool output has explicit expand and copy and clear asks confirmation`() = runComposeUiTest {
        val session = AgentSession(Files.createTempDirectory("wd-controls-").toFile().resolve("history.json"))
        try {
            session.lines += ChatLine("tool_out", "Output · offline tool", "short output")
            setContent { MaterialTheme(colorScheme = WhiteDevilColors) { AgentScreen(Settings(veniceApiKey = "offline-key"), session) {} } }
            onNodeWithText("Copy").assertExists()
            onNodeWithText("short output").assertDoesNotExist()
            onNodeWithText("Expand").performClick()
            onNodeWithText("short output").assertExists()
            onNodeWithText("Clear").performClick()
            onNodeWithText("Clear conversation?").assertExists()
            onNodeWithText("Cancel").performClick()
            runOnIdle { assertEquals(1, session.lines.size) }
        } finally { session.close() }
    }
}
