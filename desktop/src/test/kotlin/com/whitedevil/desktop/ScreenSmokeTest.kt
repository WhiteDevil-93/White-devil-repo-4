package com.whitedevil.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test

/**
 * Puts every screen on a real (offscreen) Compose scene with a hub that cannot be reached, lets its loads fail,
 * and clicks the controls added in this app. A screen that throws while composing, measuring or reacting to a click
 * fails here. The Venice model picker crashed the whole window on open because nothing had ever opened it.
 */
@OptIn(ExperimentalTestApi::class)
class ScreenSmokeTest {
    // Port 9 (discard) refuses connections at once, so every load fails fast and the error states get drawn too.
    private val offline = Settings(hubUrl = "http://127.0.0.1:9", relayUser = "u", relayPass = "p", veniceApiKey = "")

    /** Composes [content], gives the loads real time to fail, and advances the clock so the results are drawn. */
    private fun ComposeUiTest.show(content: @Composable () -> Unit) {
        mainClock.autoAdvance = false
        setContent { MaterialTheme(colorScheme = WhiteDevilColors) { content() } }
        repeat(3) { mainClock.advanceTimeBy(400); Thread.sleep(250) }
        waitForIdle()
    }

    private fun ComposeUiTest.settle() { repeat(3) { mainClock.advanceTimeBy(300); Thread.sleep(150) }; waitForIdle() }

    // the first match: a short label like "20" can appear on more than one control
    private fun ComposeUiTest.press(text: String) { onAllNodesWithText(text)[0].performClick(); settle() }

    @Test fun `home draws while loading, with data and on error`() {
        val client = MediaClient("http://127.0.0.1:9", "u", "p")
        val clip = MediaClip("smoke_a_c01_wanbot.mp4", 1, 1_790_000_000.0, 3.0, "vast")
        val loaded = LibraryUiState.Loaded(listOf(MediaGroup("g", "Chain", "chain", "vast", 1_790_000_000.0, listOf(clip))), emptyList())
        runComposeUiTest { show { HomeScreen(LibraryUiState.Loading, 1_790_000_100_000, client) {} } }
        runComposeUiTest { show { HomeScreen(loaded, 1_790_000_100_000, client) {} } }
        runComposeUiTest { show { HomeScreen(LibraryUiState.Error(MediaError(MediaErrorKind.Network, "down")), 0, client) {} } }
        client.close()
    }

    @Test fun `create opens the wan builder and the ltx builder and switches between them`() = runComposeUiTest {
        var tab = CREATE_WAN
        show { CreateScreen(offline, tab) { tab = it } }
        press("LTX 2.5"); tab = CREATE_LTX
        mainClock.advanceTimeBy(100)
    }

    @Test fun `the wan builder's controls respond`() = runComposeUiTest {
        show { WanBuilderScreen(offline) }
        press("Text-to-video"); press("Hard cuts"); press("Portrait 720×1280"); press("Dynamic"); press("20")
        press("Show ▾")                          // the OpenRouter box
        press("Generate chain")                  // nothing typed: must say so, not crash
    }

    @Test fun `the ltx builder's controls respond`() = runComposeUiTest {
        show { LtxBuilderScreen(offline) }
        press("10 s"); press("Portrait"); press("5")
        press("Show model settings ▾")
        press("↺  New render")
    }

    @Test fun `ltx screen switches between build and monitor`() = runComposeUiTest {
        show { LtxScreen(offline) }
        press("Monitor & QA cycle"); press("Build")
    }

    @Test fun `renders and gallery draw their search bars and react to the chips`() {
        runComposeUiTest { show { RendersScreen(offline) } }
        runComposeUiTest { show { GalleryScreen(offline) } }
    }

    @Test fun `the provider screens draw with the render card`() {
        runComposeUiTest { show { ThunderScreen(offline) {} } }
        runComposeUiTest { show { ColabScreen(offline) {} } }
        runComposeUiTest { show { VastScreen(offline) {} } }
    }

    @Test fun `setup shows the bot, then the lora pack, then the bot again`() = runComposeUiTest {
        show { SetupScreen(offline) }
        press("LTX 2.5 LoRA pack"); press("Install a setup")
    }

    @Test fun `venice and settings draw`() {
        runComposeUiTest { show { AgentScreen(offline, onOpenSettings = {}) } }
        runComposeUiTest { show { SettingsScreen(offline, onSave = {}, onBack = {}) } }
    }
}
