package com.whitedevil.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class UiScaleTest {
    @Test fun `steps stay on tenths and inside the range`() {
        assertEquals(1.1f, UiScale.step(1.0f, +1)); assertEquals(0.9f, UiScale.step(1.0f, -1))
        var v = 1.0f; repeat(30) { v = UiScale.step(v, +1) }; assertEquals(2.0f, v, "stops at 200%")
        repeat(30) { v = UiScale.step(v, -1) }; assertEquals(0.8f, v, "stops at 80%")
        var drift = 1.0f; repeat(7) { drift = UiScale.step(drift, +1) }; repeat(7) { drift = UiScale.step(drift, -1) }
        assertEquals(1.0f, drift, "seven bigger then seven smaller is exactly where it started")
        assertEquals(1.0f, UiScale.clamp(Float.NaN)); assertEquals(2.0f, UiScale.clamp(9f)); assertEquals(0.8f, UiScale.clamp(-3f)); assertEquals(1.3f, UiScale.clamp(1.2999999f))
        assertEquals("150%", UiScale.percent(1.5f)); assertEquals("100%", UiScale.percent(1.0f))
    }

    @Test fun `settings saved before the size existed still load at 100 percent`() {
        val old = """{"hubUrl":"https://h.example","relayUser":"u","relayPass":"p","veniceApiKey":"k","model":"m","enableWebSearch":false}"""
        val s = Json { ignoreUnknownKeys = true }.decodeFromString(Settings.serializer(), old)
        assertEquals(1.0f, s.uiScale); assertEquals("https://h.example", s.hubUrl)
        val round = Json.decodeFromString(Settings.serializer(), Json { encodeDefaults = true }.encodeToString(Settings.serializer(), s.copy(uiScale = 1.5f)))
        assertEquals(1.5f, round.uiScale)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun `the sidebar buttons make the interface bigger, smaller and back to 100 percent`() = runComposeUiTest {
        var scale by mutableStateOf(1.0f)
        setContent { MaterialTheme(colorScheme = WhiteDevilColors) { SizeControl(scale) { scale = it } } }
        onNodeWithText("A+").performClick(); onNodeWithText("A+").performClick()
        assertEquals(1.2f, scale); onNodeWithText("120%").assertExists()
        onNodeWithText("A−").performClick(); assertEquals(1.1f, scale)
        onNodeWithText("110%").performClick(); assertEquals(1.0f, scale, "clicking the percentage resets")
        scale = 2.0f; onNodeWithText("A+").performClick(); assertEquals(2.0f, scale, "bigger is disabled at 200%")
    }
}
