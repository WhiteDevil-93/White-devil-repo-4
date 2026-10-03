package com.whitedevil.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * These drive the real screens: open, click, type. A crash that only happens on open or on a click (the Venice
 * model list put a lazy list inside a dropdown menu, which measures its content in a way a lazy list refuses to
 * answer) used to reach the user because nothing ever opened the control. Now something does.
 */
@OptIn(ExperimentalTestApi::class)
class UiInteractionTest {
    private val models = listOf(
        VeniceModel("zai-org-glm-5-2", "GLM 5.2", 1_000_000, toolCalling = true, offline = false, reasoning = true),
        VeniceModel("gemini-3-6-flash", "Gemini 3.6 Flash", 1_048_576, toolCalling = true, offline = false, reasoning = false),
        VeniceModel("qwen3-6-27b", "Qwen 3.6 27B", 256_000, toolCalling = true, offline = false, reasoning = true),
    )

    @Test fun `the venice model picker opens, searches and picks without crashing`() = runComposeUiTest {
        var picked: String? = null
        setContent {
            MaterialTheme(colorScheme = WhiteDevilColors) {
                VeniceModelPicker("key", "zai-org-glm-5-2", enabled = true, onPick = { picked = it }, loadModels = { MediaResult.Ok(usableVeniceModels(models)) })
            }
        }
        onNodeWithText("zai-org-glm-5-2").performClick()                      // open the menu
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Gemini 3.6 Flash").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(1, onAllNodesWithText("Qwen 3.6 27B").fetchSemanticsNodes().size, "every usable model is listed")

        onNode(hasSetTextAction()).performTextInput("flash")                  // search narrows the list
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Qwen 3.6 27B").fetchSemanticsNodes().isEmpty() }
        assertEquals(1, onAllNodesWithText("Gemini 3.6 Flash").fetchSemanticsNodes().size)

        onNodeWithText("Gemini 3.6 Flash").performClick()                     // pick one
        assertEquals("gemini-3-6-flash", picked)
    }

    @Test fun `the venice model picker offers a typed id when the list cannot be loaded`() = runComposeUiTest {
        var picked: String? = null
        setContent {
            MaterialTheme(colorScheme = WhiteDevilColors) {
                VeniceModelPicker("", "zai-org-glm-5-2", enabled = true, onPick = { picked = it }, loadModels = { MediaResult.Failure(MediaError(MediaErrorKind.Config, "No Venice API key is set.")) })
            }
        }
        onNodeWithText("zai-org-glm-5-2").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("No Venice API key is set.").fetchSemanticsNodes().isNotEmpty() }
        onNode(hasSetTextAction()).performTextInput("my-custom-model")
        onNodeWithText("USE").performClick()
        assertEquals("my-custom-model", picked)
    }

    @Test fun `a picker that is disabled while the agent works does not open`() = runComposeUiTest {
        setContent {
            MaterialTheme(colorScheme = WhiteDevilColors) {
                VeniceModelPicker("key", "zai-org-glm-5-2", enabled = false, onPick = {}, loadModels = { MediaResult.Ok(models) })
            }
        }
        onNodeWithText("zai-org-glm-5-2").performClick()
        mainClock.advanceTimeBy(500)
        assertTrue(onAllNodesWithText("GLM 5.2").fetchSemanticsNodes().isEmpty())
    }

    @Test fun `the sort menu in the search bar opens and changes the sort`() = runComposeUiTest {
        var filter by androidx.compose.runtime.mutableStateOf(MediaFilter())
        setContent {
            MaterialTheme(colorScheme = WhiteDevilColors) {
                MediaFilterBar(filter, { filter = it }, FilterCounts(10, listOf("vast" to 6, "ltx" to 4), tests = 2, keepers = 1), shown = 10)
            }
        }
        onNodeWithText("Sort: Newest ▾").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Largest").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Largest").performClick()
        assertEquals(SortKey.Largest, filter.sort)
        onNodeWithText("ltx  4").performClick(); assertEquals("ltx", filter.source)
        onNodeWithText("Show unsorted  2").performClick(); assertTrue(filter.showTests)
        onNodeWithText("By project").performClick(); assertEquals(ViewMode.Projects, filter.view)
    }

    @Test fun `the generic picker used by the builders opens and picks`() = runComposeUiTest {
        var value by androidx.compose.runtime.mutableStateOf("a")
        setContent { MaterialTheme(colorScheme = WhiteDevilColors) { Pick(value, listOf("a" to "First option", "b" to "Second option")) { value = it } } }
        onNodeWithText("First option").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Second option").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Second option").performClick()
        assertEquals("b", value)
    }
}
