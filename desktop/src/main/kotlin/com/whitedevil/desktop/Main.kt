package com.whitedevil.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
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
 * WhiteDevil desktop — the laptop-end interface (Forge Hub).
 *
 * Deliberately not a webview. The agent loop it drives is [com.whitedevil.agent.Agent]
 * from :shared, the same class the Android app runs, so a fix to the loop lands on
 * both surfaces instead of being applied twice and drifting — which is exactly what
 * happened between the Android app and the venice-agent CLI.
 *
 * The hub on the OCI VM stays the system of record: it is reachable when this
 * laptop is not, which is what lets the phone keep working while the laptop is
 * away. This app is a client of it, not a replacement for it.
 *
 * The shell (sidebar, top bar, Home) follows the UX Pilot "Forge Hub laptop PWA" design;
 * colours come from [Forge], which mirrors hub/static/ui/tokens.css.
 */
fun main() = application {
    val windowState = rememberWindowState(size = DpSize(1280.dp, 860.dp))

    Window(
        onCloseRequest = ::exitApplication,
        state = windowState,
        title = "Forge Hub",
    ) {
        var settings by remember { mutableStateOf(Settings.load()) }
        var screen by remember { mutableStateOf(Screen.Home) }

        MaterialTheme(colorScheme = WhiteDevilColors) {
            Surface(color = Forge.Bg) {
                // One library request for the shell: it drives the status pill and Home. The
                // Renders and Gallery screens keep their own, so they can refresh independently.
                val client = rememberMediaClient(settings)
                val library = rememberLibrary(client)
                val nowMs by rememberNowMs()
                val health = when (library.state) {
                    is LibraryUiState.Loaded -> HubHealth.Online
                    is LibraryUiState.Error -> HubHealth.Unreachable
                    else -> HubHealth.Connecting
                }

                Row(Modifier.fillMaxSize()) {
                    ForgeSidebar(current = screen, onSelect = { screen = it })
                    Column(Modifier.weight(1f).fillMaxSize()) {
                        ForgeTopBar(title = screen.label, health = health, hubLabel = client.hubLabel)
                        Box(Modifier.weight(1f)) {
                            when (screen) {
                                Screen.Home -> HomeScreen(library.state, nowMs, onOpen = { screen = it })
                                Screen.Agent -> AgentScreen(
                                    settings = settings,
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
                                    onSave = { settings = it; screen = Screen.Home },
                                    onBack = { screen = Screen.Home },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
