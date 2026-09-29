package com.whitedevil.desktop.ops

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Source-level guard for "no action from LaunchedEffect / on-start / auto-refresh".
 *
 * It cannot prove behaviour (no UI runs in unit tests), but it fails the build if someone wires a
 * mutating call around the confirmation gate: the POST path, the gate's `confirm`, and every
 * effect that runs on its own are each confined to the few files that are meant to hold them.
 */
class ActionGateGuardTest {

    private val root = listOf(File("src/main/kotlin"), File("desktop/src/main/kotlin")).first { it.isDirectory }
    private val screens = listOf("ColabScreen.kt", "ThunderScreen.kt", "VastScreen.kt", "SetupScreen.kt")

    /** The ops package plus the four ops screens; other screens (Agent, Shell, ...) are not this guard's business. */
    private fun sources(): List<File> =
        File(root, "com/whitedevil/desktop/ops").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() +
            screens.map { File(root, "com/whitedevil/desktop/$it") }
    private fun File.code(): String = readText().lines().filterNot { it.trim().startsWith("*") || it.trim().startsWith("//") || it.trim().startsWith("/*") }.joinToString("\n")

    /** Text of the first `{ ... }` block found at or after [from], braces balanced. */
    private fun balancedBlockAfter(text: String, from: Int): String {
        val open = text.indexOf('{', from)
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return text.substring(open + 1, i)
            }
        }
        error("unbalanced braces")
    }

    private fun filesContaining(needle: String): Set<String> = sources().filter { needle in it.code() }.map { it.name }.toSet()

    @Test
    fun `only the confirmation dialog calls confirm`() {
        assertEquals(setOf("ConfirmDialog.kt"), filesContaining(".confirm()"))
    }

    @Test
    fun `only the API wrappers POST, and only the wrappers build an actor`() {
        val posters = filesContaining("actor.post(")
        assertTrue(posters.all { it.endsWith("Models.kt") }, "actor.post used outside the *Models.kt wrappers: $posters")
        assertEquals(setOf("OpsUi.kt"), filesContaining("OpsActor(http)"), "OpsActor must be constructed in one place")
        screens.forEach { name ->
            val code = File(root, "com/whitedevil/desktop/$name").code()
            assertTrue("actor.post(" !in code && "OpsActor(" !in code && "OpsHttp(" !in code, "$name talks to the hub directly")
        }
    }

    @Test
    fun `automatic effects only ever read`() {
        // LaunchedEffect lives only in the shared LoadOnce / PollWhileVisible helpers.
        assertEquals(setOf("OpsUi.kt"), filesContaining("LaunchedEffect("))
        val ui = File(root, "com/whitedevil/desktop/ops/OpsUi.kt").code()
        val effects = Regex("LaunchedEffect\\(").findAll(ui).map { balancedBlockAfter(ui, it.range.last) }.toList()
        assertEquals(2, effects.size, "expected exactly LoadOnce and PollWhileVisible")
        effects.forEach { body ->
            assertTrue("refresh()" in body, "an automatic effect should only refresh a panel: $body")
            listOf("actions.", "actor", "controller", "request(", "confirm", ".post(").forEach { forbidden ->
                assertTrue(forbidden !in body, "automatic effect touches '$forbidden': $body")
            }
        }
        // Screens declare no effects of their own.
        screens.forEach { name ->
            val code = File(root, "com/whitedevil/desktop/$name").code()
            assertTrue("LaunchedEffect" !in code && "DisposableEffect" !in code && "SideEffect" !in code, "$name has its own effect")
        }
    }

    @Test
    fun `the read-only clients cannot post at all`() {
        // OpsReader exposes only getJson; if a post method is ever added to it this fails.
        val reader = OpsReader::class.java.declaredMethods.map { it.name }
        assertTrue(reader.none { it.contains("post", ignoreCase = true) || it.contains("put", ignoreCase = true) || it.contains("delete", ignoreCase = true) }, reader.toString())
        val apis = listOf(ColabApi::class, ThunderApi::class, VastApi::class, SetupApi::class)
        apis.forEach { api ->
            val ctor = api.java.declaredConstructors.single()
            assertEquals(listOf(OpsReader::class.java), ctor.parameterTypes.toList(), "${api.simpleName} must hold only an OpsReader")
        }
    }
}
