package com.whitedevil.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

/**
 * WhiteDevil desktop — the laptop-end interface.
 *
 * Deliberately not a webview. The agent loop it drives is [com.whitedevil.agent.Agent]
 * from :shared, the same class the Android app runs, so a fix to the loop lands on
 * both surfaces instead of being applied twice and drifting — which is exactly what
 * happened between the Android app and the venice-agent CLI.
 *
 * The hub on the OCI VM stays the system of record: it is reachable when this
 * laptop is not, which is what lets the phone keep working while the laptop is
 * away. This app is a client of it, not a replacement for it.
 */
fun main() = application {
    val windowState = rememberWindowState(size = DpSize(1280.dp, 860.dp))

    Window(
        onCloseRequest = ::exitApplication,
        state = windowState,
        title = "WhiteDevil",
    ) {
        var settings by remember { mutableStateOf(Settings.load()) }
        var screen by remember { mutableStateOf(Screen.Agent) }
        val agentSession = remember { AgentSession() }
        DisposableEffect(agentSession) { onDispose { agentSession.close() } }

        MaterialTheme(colorScheme = WhiteDevilColors) {
            Surface(color = MaterialTheme.colorScheme.background) {
                Row(Modifier.fillMaxSize()) {
                    NavRail(current = screen, onSelect = { screen = it })
                    Box(Modifier.weight(1f)) {
                        when (screen) {
                            Screen.Agent -> AgentScreen(
                                settings = settings,
                                session = agentSession,
                                onOpenSettings = { screen = Screen.Settings },
                            )
                            // Kept alive across tab switches: restarting the shell
                            // on every switch would discard the session and any
                            // long-running command in it.
                            Screen.Terminal -> TerminalScreen()
                            Screen.Renders -> RendersScreen(settings)
                            Screen.Gallery -> GalleryScreen(settings)
                            Screen.Colab -> ColabScreen(settings)
                            Screen.Thunder -> ThunderScreen(settings)
                            Screen.Ltx -> LtxScreen(settings)
                            Screen.Vast -> VastScreen(settings)
                            Screen.Setup -> SetupScreen(settings)
                            Screen.Settings -> SettingsScreen(
                                initial = settings,
                                onSave = { settings = it; screen = Screen.Agent },
                                onBack = { screen = Screen.Agent },
                            )
                        }
                    }
                }
            }
        }
    }
}

private enum class Screen(val label: String) {
    Agent("Agent"),
    Terminal("Shell"),
    Renders("Renders"),
    Gallery("Gallery"),
    Colab("Colab"),
    Thunder("Thunder"),
    Ltx("LTX"),
    Vast("Vast"),
    Setup("Setup"),
    Settings("Settings"),
}

/**
 * Left rail rather than a top tab strip: it matches the Forge Hub design's
 * sidebar, and leaves the full window height for the conversation and the shell.
 */
@Composable
private fun NavRail(current: Screen, onSelect: (Screen) -> Unit) {
    NavigationRail(
        modifier = Modifier.fillMaxHeight().width(132.dp).verticalScroll(rememberScrollState()),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Spacer(Modifier.height(12.dp))
        val groups = linkedMapOf(
            "Create" to listOf(Screen.Agent, Screen.Ltx),
            "Monitor" to listOf(Screen.Renders, Screen.Gallery, Screen.Colab, Screen.Thunder, Screen.Vast),
            "Operate" to listOf(Screen.Terminal, Screen.Setup),
            "Account" to listOf(Screen.Settings),
        )
        groups.forEach { (group, screens) ->
        Text(group, modifier = Modifier.padding(top = 12.dp), style = MaterialTheme.typography.labelSmall)
        screens.forEach { screen ->
            NavigationRailItem(
                selected = current == screen,
                onClick = { onSelect(screen) },
                icon = {},
                label = { Text(screen.label, style = MaterialTheme.typography.labelMedium) },
            )
        }
        }
    }
}
