package com.whitedevil.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import com.whitedevil.desktop.skills.Skill
import com.whitedevil.desktop.skills.SkillStore
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CommandsTest {
    private val skills = listOf(
        Skill("wan-14b-prompting", "Writing prompts for Wan 2.2 14B video clips.", "body"),
        Skill("gpu-cost-guard", "Rules for spending the user's money on cloud GPUs.", "body"),
    )

    // ---- what a line means

    @Test fun `app commands, the agent's own, and skills all resolve`() {
        assertEquals(Commands.Resolved.Local("new", ""), Commands.resolve("/new", skills))
        assertEquals(Commands.Resolved.Local("chats", "l40 price"), Commands.resolve("  /chats   l40 price ", skills))
        assertEquals(Commands.Resolved.Local("remember", "I prefer 5 s clips."), Commands.resolve("/remember I prefer 5 s clips.", skills))
        assertEquals(Commands.Resolved.Local("help", ""), Commands.resolve("/HELP", skills), "case does not matter")
        assertEquals(Commands.Resolved.PassThrough, Commands.resolve("/review", skills))
        assertEquals(Commands.Resolved.PassThrough, Commands.resolve("/cycle start rounds=2", skills))
        val r = Commands.resolve("/gpu-cost-guard rent an A100 for 3 hours", skills) as Commands.Resolved.Rewrite
        assertTrue("gpu-cost-guard" in r.forAgent && "use_skill" in r.forAgent && r.forAgent.endsWith("rent an A100 for 3 hours"))
        assertEquals("/gpu-cost-guard rent an A100 for 3 hours", r.shown, "the chat keeps what you typed")
        assertTrue(Commands.resolve("/skill wan-14b-prompting check this", skills) is Commands.Resolved.Rewrite)
        assertTrue((Commands.resolve("/wan-14b-prompting", skills) as Commands.Resolved.Rewrite).forAgent.contains("current situation"), "no task is fine")
    }

    @Test fun `ordinary text, paths and lone slashes are not commands`() {
        listOf("hello", "", "/", "  /  ", "/api/ltx/status", "/c/Users/anon3", "//comment", "/ new", "look at /new").forEach { assertNull(Commands.resolve(it, skills), "'$it'") }
    }

    @Test fun `an unknown command says what it might have meant`() {
        val u = Commands.resolve("/gpu", skills) as Commands.Resolved.Unknown
        assertEquals("/gpu", u.typed); assertTrue("/gpu-cost-guard" in u.close, u.close.toString())
        assertTrue(Commands.resolve("/zzzzz", skills) is Commands.Resolved.Unknown)
        assertTrue(Commands.resolve("/skill nope", skills) is Commands.Resolved.Unknown)
    }

    // ---- the menu

    @Test fun `typing a slash lists commands and skills, narrowing as you type`() {
        val all = Commands.suggestions("/", skills, emptyList(), emptyList())
        assertEquals(Commands.MAX, all.size); assertEquals("/help", all.first().label)
        val ne = Commands.suggestions("/ne", skills, emptyList(), emptyList()).map { it.insert }
        assertEquals("/new ", ne.first(), "the name that starts with it comes first"); assertTrue("/review " !in ne && "/gpu-cost-guard " !in ne, "short queries do not match descriptions: $ne")
        assertEquals("/gpu-cost-guard ", Commands.suggestions("/gpu", skills, emptyList(), emptyList()).first().insert)
        assertTrue(Commands.suggestions("/spending", skills, emptyList(), emptyList()).any { it.label == "/gpu-cost-guard" }, "matches the description too")
        assertTrue(Commands.suggestions("/new now", skills, emptyList(), emptyList()).isEmpty(), "no menu once you are typing arguments")
        assertTrue(Commands.suggestions("hello /ne", skills, emptyList(), emptyList()).isEmpty(), "a slash mid-sentence is just text")
    }

    @Test fun `typing an at sign offers skills, connectors, files and clips`() {
        val clips = listOf(ClipRef("smoke_ltx_0412.mp4", "LTX · 12 Apr"), ClipRef("pack3_c2.mp4", "Pack 3 · part 2"))
        val m = Commands.suggestions("look at @", skills, listOf("google-drive"), clips).map { it.label }
        assertTrue("@file" in m && "@clip:" in m && "@google-drive" in m && "@wan-14b-prompting" in m, m.toString())
        assertTrue(Commands.suggestions("@file", skills, emptyList(), clips).single { it.attachFile }.label == "@file")
        assertEquals(listOf("@google-drive "), Commands.suggestions("use @goo", skills, listOf("google-drive"), clips).map { it.insert })
        val c = Commands.suggestions("@clip:pack", skills, emptyList(), clips)
        assertEquals(listOf("@clip:pack3_c2.mp4 "), c.map { it.insert }); assertEquals("Pack 3 · part 2", c.single().label, "shows the readable name, inserts the real file name")
        assertTrue(Commands.suggestions("email me@example.com", skills, emptyList(), clips).isEmpty(), "an at sign inside a word is not a mention")
    }

    @Test fun `choosing a suggestion fills the box correctly`() {
        val cmd = Commands.suggestions("/ne", skills, emptyList(), emptyList()).first()
        assertEquals("/new ", Commands.complete("/ne", cmd))
        val at = Commands.suggestions("use @goo", skills, listOf("google-drive"), emptyList()).first()
        assertEquals("use @google-drive ", Commands.complete("use @goo", at))
    }

    // ---- mentions reach the agent

    @Test fun `mentions add a short instruction for the agent and leave your words alone`() {
        val out = Commands.expandMentions("check @wan-14b-prompting then @google-drive and @clip:pack3_c2.mp4, thanks", skills, listOf("google-drive"))
        assertTrue(out.startsWith("check @wan-14b-prompting then @google-drive and @clip:pack3_c2.mp4, thanks"))
        assertTrue("[Use the skill \"wan-14b-prompting\": call use_skill first.]" in out)
        assertTrue("[Use the google-drive connector's tools (named google-drive__...) for this.]" in out)
        assertTrue("[Hub clip file: /clips/pack3_c2.mp4]" in out, out)
        assertEquals("nothing here @unknown", Commands.expandMentions("nothing here @unknown", skills, emptyList()))
        assertEquals(1, Regex("wan-14b-prompting\": call").findAll(Commands.expandMentions("@wan-14b-prompting @wan-14b-prompting", skills, emptyList())).count(), "no duplicates")
    }

    @Test fun `hints are hidden again when a saved chat is reopened`() {
        val msg = Commands.expandMentions("review @clip:a.mp4", skills, emptyList())
        assertEquals("review @clip:a.mp4", Commands.stripHints(msg))
    }

    @Test fun `help lists everything`() {
        val h = Commands.helpText(skills)
        assertTrue(Commands.BUILT_IN.all { "/${it.name}" in h } && "/wan-14b-prompting" in h && "@clip:" in h)
        assertFalse(Commands.helpText(emptyList()).contains("Skills —"))
    }

    // ---- the real composer

    @OptIn(ExperimentalTestApi::class)
    @Test fun `typing a slash in the real chat box opens the menu, Tab completes, Enter runs`() = runComposeUiTest {
        val dir = Files.createTempDirectory("cmd").toFile()
        val store = SkillStore(dir); store.seedDefaults()
        val session = AgentSession(null)
        session.lines.add(ChatLine(ROLE_USER, "You", "an earlier message"))
        setContent { MaterialTheme(colorScheme = WhiteDevilColors) { AgentScreen(Settings(hubUrl = "http://127.0.0.1:9", veniceApiKey = ""), onOpenSettings = {}, session = session, skills = store) } }
        waitForIdle()
        val box = onNode(hasSetTextAction())
        box.performClick(); box.performTextInput("/ne"); waitForIdle()
        assertTrue(onAllNodesWithText("/new").fetchSemanticsNodes().isNotEmpty(), "the menu offers /new")
        assertTrue(onAllNodesWithText("Start a new chat", substring = true).fetchSemanticsNodes().isNotEmpty(), "with its description")
        box.performKeyInput { pressKey(Key.Tab) }; waitForIdle()
        assertTrue(onAllNodesWithText("/new ").fetchSemanticsNodes().isNotEmpty(), "Tab filled the box with '/new '")
        box.performKeyInput { pressKey(Key.Enter) }; waitForIdle()
        assertTrue(session.lines.isEmpty(), "Enter ran /new: the chat is empty again")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun `an unknown command is explained in the chat and never sent to the model`() = runComposeUiTest {
        val session = AgentSession(null)
        setContent { MaterialTheme(colorScheme = WhiteDevilColors) { AgentScreen(Settings(hubUrl = "http://127.0.0.1:9", veniceApiKey = ""), onOpenSettings = {}, session = session) } }
        waitForIdle()
        val box = onNode(hasSetTextAction())
        box.performClick(); box.performTextInput("/zzzzz"); box.performKeyInput { pressKey(Key.Enter) }; waitForIdle()
        assertEquals(1, session.lines.size); assertEquals(ROLE_ERROR, session.lines.single().role)
        assertTrue("/zzzzz is not a command" in session.lines.single().body && "/help" in session.lines.single().body)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun `slash help prints the command list in the chat`() = runComposeUiTest {
        val session = AgentSession(null)
        setContent { MaterialTheme(colorScheme = WhiteDevilColors) { AgentScreen(Settings(hubUrl = "http://127.0.0.1:9", veniceApiKey = ""), onOpenSettings = {}, session = session) } }
        waitForIdle()
        val box = onNode(hasSetTextAction())
        box.performClick(); box.performTextInput("/help"); box.performKeyInput { pressKey(Key.Enter) }; waitForIdle()
        assertTrue(session.lines.single().body.contains("/remember <note>") && session.lines.single().body.contains("@clip:"))
    }
}
