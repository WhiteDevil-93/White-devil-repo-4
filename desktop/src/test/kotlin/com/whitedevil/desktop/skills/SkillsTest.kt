package com.whitedevil.desktop.skills

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runComposeUiTest
import com.whitedevil.agent.ToolBox
import com.whitedevil.desktop.AgentScreen
import com.whitedevil.desktop.Settings
import com.whitedevil.desktop.SkillsPanel
import com.whitedevil.desktop.WhiteDevilColors
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SkillsTest {
    private fun dir() = Files.createTempDirectory("skills").toFile()

    // ---- the bundled defaults (the real resources)

    private val indexNames get() = SkillStore.bundled("_index")!!.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }

    @Test fun `every bundled skill is well formed`() {
        val names = indexNames
        assertTrue(names.size >= 35, "a real library, not a handful: ${names.size}")
        assertEquals(names.size, names.toSet().size, "no duplicates in the index")
        for (n in names) {
            val text = assertNotNull(SkillStore.bundled(n), "$n is listed but has no file")
            val s = SkillStore.parse(n, text)
            assertEquals(n, s.name, "front-matter name matches the file name")
            assertEquals(n, SkillStore.clean(n), "$n is a clean slug")
            assertTrue(s.description.length in 30..230, "$n description length ${s.description.length}")
            val lines = s.body.lines().count { it.isNotBlank() }
            assertTrue(lines in 4..60, "$n body is a playbook, not a stub or an essay: $lines non-blank lines")
            assertFalse(Regex("(?i)(password\\s*[:=]\\s*\\S{4,}|sk-[a-z0-9]{16,}|bearer\\s+[a-z0-9._-]{20,})").containsMatchIn(text), "$n must not contain credentials")
        }
    }

    @Test fun `every skill file on disk is in the index`() {
        val onDisk = File(javaClass.classLoader.getResource("skills/_index.txt")!!.toURI()).parentFile.listFiles { f -> f.name.endsWith(".md") }!!.map { it.nameWithoutExtension }.toSet()
        assertEquals(onDisk, indexNames.toSet(), "an unlisted skill would never be installed")
    }

    @Test fun `the skills the agent most needs exist`() {
        val need = listOf("wan-14b-prompting", "ltx-prompting", "gpu-cost-guard", "render-sync-verify", "instance-idle-shutdown", "setup-bot-recipes", "gemma4-litert-export", "service-restart-safety", "verification-contract", "git-hygiene", "windows-shell-ops", "mcp-connectors", "skill-authoring")
        assertTrue(indexNames.containsAll(need), "missing: ${need - indexNames.toSet()}")
    }

    // ---- the store

    @Test fun `defaults are seeded once, your edits stay, and a deleted default is not forced back`() {
        val d = dir(); val store = SkillStore(d)
        val n = store.seedDefaults()
        assertEquals(indexNames.size, n); assertEquals(n, store.list().size)
        store.save("gpu-cost-guard", "my own version of the rule", "Always ask.")
        store.delete("ltx-prompting")
        assertEquals(0, SkillStore(d).seedDefaults(), "second start adds nothing")
        val again = SkillStore(d)
        assertEquals("Always ask.", again.get("gpu-cost-guard")!!.body); assertNull(again.get("ltx-prompting"))
        assertEquals(n - 1, again.list().size)
    }

    @Test fun `restore defaults brings the bundled ones back but keeps your own skills`() {
        val store = SkillStore(dir()); store.seedDefaults()
        store.save("my-thing", "something of mine for testing", "Do the thing.")
        store.save("gpu-cost-guard", "edited", "edited body"); store.delete("ltx-prompting")
        val n = store.resetDefaults()
        assertEquals(indexNames.size, n)
        assertTrue(store.get("gpu-cost-guard")!!.body.contains("Ask before")); assertNotNull(store.get("ltx-prompting")); assertNotNull(store.get("my-thing"))
    }

    @Test fun `saving validates and cleans names, and files stay readable by a person`() {
        val d = dir(); val s = SkillStore(d)
        assertNull(s.save("  My Cool Skill!! ", "does a cool thing when asked", "1. do it"))
        assertEquals("my-cool-skill", s.list().single().name)
        assertTrue(File(d, "my-cool-skill/SKILL.md").readText().startsWith("---\nname: my-cool-skill\ndescription: does a cool thing when asked\n---\n"))
        assertNotNull(s.save("!!!", "d", "b")); assertNotNull(s.save("x", " ", "b")); assertNotNull(s.save("x", "d", "  "))
        assertNull(s.save("../escape", "tries to leave the folder", "body")); assertTrue(File(d, "escape/SKILL.md").exists() && !File(d.parentFile, "escape").exists(), "path tricks stay inside the folder")
        assertFalse(s.delete("")); assertFalse(s.delete("nope"))
    }

    @Test fun `a hand-edited file with windows line endings or no front matter still loads`() {
        val d = dir(); File(d, "a").mkdirs(); File(d, "b").mkdirs()
        File(d, "a/SKILL.md").writeText("---\r\nname: a\r\ndescription: has crlf endings\r\n---\r\n\r\nStep one.\r\n")
        File(d, "b/SKILL.md").writeText("just instructions, no header")
        val l = SkillStore(d).list()
        assertEquals("has crlf endings", l[0].description); assertEquals("Step one.", l[0].body)
        assertEquals("b", l[1].name); assertEquals("just instructions, no header", l[1].body)
    }

    // ---- what the agent sees and can do

    @Test fun `the prompt carries a one-line index and no bodies`() {
        val skills = listOf(Skill("a-skill", "does a", "SECRET BODY TEXT"), Skill("b-skill", "does b", "other"))
        val p = systemPromptWithSkills("BASE", skills)
        assertTrue(p.startsWith("BASE") && "- a-skill: does a" in p && "- b-skill: does b" in p && "use_skill" in p)
        assertFalse("SECRET BODY TEXT" in p); assertEquals("BASE", systemPromptWithSkills("BASE", emptyList()))
    }

    @Test fun `the agent can list, load and save skills through its tool box`() {
        val d = dir(); val store = SkillStore(d); store.seedDefaults()
        val box = ToolBox(File(d, "ws"), "http://127.0.0.1:9", "u", "p", extension = SkillsExtension(store))
        val names = box.definitions.map { it.function.name }
        assertTrue(listOf("list_skills", "use_skill", "save_skill", "hub_request").all { it in names })
        assertTrue(box.executeDetailed("list_skills", "{}").text.contains("gpu-cost-guard:"))
        val body = box.executeDetailed("use_skill", """{"name":"gpu-cost-guard"}""").text
        assertTrue(body.startsWith("# gpu-cost-guard") && "Ask before" in body)
        assertTrue(box.executeDetailed("use_skill", """{"name":"zzz"}""").text.startsWith("Error: no skill named 'zzz'. Available:"))
        assertEquals("Saved skill 'new-one'.", box.executeDetailed("save_skill", """{"name":"New One","description":"for testing the tool","body":"1. a"}""").text)
        assertEquals("1. a", store.get("new-one")!!.body)
        assertTrue(box.executeDetailed("save_skill", """{"name":"x"}""").text.startsWith("Error:"), "missing fields are refused")
        assertTrue(box.executeDetailed("use_skill", "not json").text.startsWith("Error"))
    }

    @Test fun `skills and connectors share the single tool hook`() {
        val a = SkillsExtension(SkillStore(dir()).also { it.seedDefaults() })
        val fake = object : com.whitedevil.agent.ToolExtension {
            override fun definitions() = listOf(com.whitedevil.agent.ToolDefinition(function = com.whitedevil.agent.ToolFunctionSpec("fake__t", "d", kotlinx.serialization.json.JsonObject(emptyMap()))))
            override fun handles(name: String) = name == "fake__t"
            override fun execute(name: String, argumentsJson: String) = "fake ran"
        }
        val c = CompositeExtension(listOf(a, fake))
        assertTrue(c.definitions().map { it.function.name }.containsAll(listOf("use_skill", "fake__t")))
        assertEquals("fake ran", c.execute("fake__t", "{}")); assertTrue(c.execute("list_skills", "{}").contains("verification-contract"))
        assertFalse(c.handles("other"))
    }

    // ---- the panel and the screen

    @OptIn(ExperimentalTestApi::class)
    @Test fun `the skills panel lists searches edits and saves`() = runComposeUiTest {
        val store = SkillStore(dir()); store.seedDefaults()
        setContent { MaterialTheme(colorScheme = WhiteDevilColors) { SkillsPanel(store, onClose = {}) } }
        mainClock.advanceTimeBy(1_000)
        waitUntil(timeoutMillis = 8_000) { onAllNodesWithText("gpu-cost-guard").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(onAllNodesWithText("gpu-cost-guard").fetchSemanticsNodes().isNotEmpty(), "the list shows skills")
        val listItem = androidx.compose.ui.test.hasText("gpu-cost-guard") and androidx.compose.ui.test.hasClickAction()
        onNode(listItem).performScrollTo().performClick(); waitForIdle(); mainClock.advanceTimeBy(500)
        assertEquals(2, onAllNodesWithText("gpu-cost-guard").fetchSemanticsNodes().size, "picking a skill puts its name in the editor as well as the list")
        onNodeWithText("SAVE").performClick(); waitForIdle()
        assertTrue(onAllNodesWithText("Saved 'gpu-cost-guard'.").fetchSemanticsNodes().isNotEmpty(), "saving reports back")
        onNodeWithText("RESTORE DEFAULTS").performClick(); waitForIdle()
        assertTrue(onAllNodesWithText("Restored", substring = true).fetchSemanticsNodes().isNotEmpty())
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun `the venice screen offers the skills button`() = runComposeUiTest {
        val store = SkillStore(dir()); store.seedDefaults()
        setContent { MaterialTheme(colorScheme = WhiteDevilColors) { AgentScreen(Settings(hubUrl = "http://127.0.0.1:9", veniceApiKey = ""), onOpenSettings = {}, skills = store) } }
        waitForIdle()
        onNodeWithText("SKILLS").performClick(); mainClock.advanceTimeBy(1_000)
        waitUntil(timeoutMillis = 8_000) { onAllNodesWithText("Venice skills (${store.list().size})").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(onAllNodesWithText("Venice skills (${store.list().size})").fetchSemanticsNodes().isNotEmpty())
    }
}
