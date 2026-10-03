package com.whitedevil.desktop

import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CrashLogTest {
    @Test fun `an error is appended with its time, thread and stack`() {
        val dir = Files.createTempDirectory("crash").toFile()
        assertTrue(CrashLog.write("AWT-EventQueue-0", IllegalStateException("boom"), dir, Instant.parse("2026-10-03T20:00:00Z")))
        assertTrue(CrashLog.write("main", NoClassDefFoundError("com/x/SetupScreenKt"), dir, Instant.parse("2026-10-03T20:01:00Z")))
        val text = CrashLog.file(dir).readText()
        assertTrue("2026-10-03T20:00:00Z  thread=AWT-EventQueue-0" in text && "IllegalStateException: boom" in text)
        assertTrue("NoClassDefFoundError: com/x/SetupScreenKt" in text)
        assertEquals(2, Regex("=== 2026").findAll(text).count(), "entries are appended, not overwritten")
    }

    @Test fun `logging a crash never throws, even when the folder cannot be written`() {
        val file = Files.createTempFile("not-a-dir", ".txt").toFile()
        assertFalse(CrashLog.write("t", RuntimeException("x"), file), "a path that is a file cannot hold a log, and that is reported, not thrown")
    }
}
