package com.whitedevil.desktop

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant
import javax.swing.JOptionPane
import javax.swing.SwingUtilities

/**
 * An unexpected error on the UI thread used to make the window just stop, with nothing to say why. This keeps
 * a log (crash.log next to the settings) and tells you once that something went wrong and where the details
 * are, so a crash is a thing you can report rather than a mystery.
 */
object CrashLog {
    private var warned = false

    fun file(dir: File = Settings.dir): File = File(dir, "crash.log")

    /** Appends one entry. Never throws: logging a crash must not cause another one. */
    fun write(thread: String, error: Throwable, dir: File = Settings.dir, now: Instant = Instant.now()): Boolean = runCatching {
        dir.mkdirs()
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        file(dir).appendText("=== $now  thread=$thread\n$trace\n")
        true
    }.getOrDefault(false)

    fun install() {
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            val saved = write(t.name, e)
            System.err.println("Unhandled error on ${t.name}: $e")
            if (!warned) {
                warned = true
                SwingUtilities.invokeLater {
                    runCatching {
                        JOptionPane.showMessageDialog(
                            null,
                            "Forge Hub hit an unexpected error: ${e.javaClass.simpleName}: ${e.message ?: "(no message)"}\n\n" +
                                (if (saved) "Details were saved to:\n${file().absolutePath}\n\n" else "") +
                                "If the window stops responding, close it and open it again.",
                            "Forge Hub", JOptionPane.ERROR_MESSAGE,
                        )
                    }
                }
            }
        }
    }
}
