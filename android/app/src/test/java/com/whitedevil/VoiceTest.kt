package com.whitedevil

import com.whitedevil.agent.SpeechText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceTest {

    @Test
    fun cleanRemovesMarkdownAndCodeButKeepsTheWords() {
        val md = "## Plan\n\n- **Boil** the _water_\n- Steep for `3 min`\n\n> Note: [the guide](https://example.com/tea) helps.\n\n" +
            "```kotlin\nfun main() { println(\"hi\") }\n```\nDone. See https://example.com/x for more."
        val out = SpeechText.clean(md)
        assertEquals("Plan Boil the water Steep for 3 min Note: the guide helps. (code block omitted) Done. See link for more.", out)
        assertFalse(out.contains("*") || out.contains("`") || out.contains("#") || out.contains("http"))
    }

    @Test
    fun cleanHandlesEmptyAndPlainText() {
        assertEquals("", SpeechText.clean("   \n\n "))
        assertEquals("Just a sentence.", SpeechText.clean("Just a sentence."))
        assertEquals("", SpeechText.clean("![diagram](http://x/y.png)"))
    }

    @Test
    fun chunksStayUnderTheLimitBreakAtSentencesAndLoseNothing() {
        val sentence = "This is one sentence of moderate length. "
        val text = sentence.repeat(300).trim()                      // ~12,000 chars
        val parts = SpeechText.chunks(text, 3500)
        assertTrue(parts.size >= 4)
        assertTrue(parts.all { it.length <= 3500 })
        assertTrue("breaks should land on sentence ends", parts.dropLast(1).all { it.endsWith(".") })
        assertEquals(text.replace(Regex("\\s+"), " "), parts.joinToString(" ").replace(Regex("\\s+"), " "))
        assertEquals(listOf("short"), SpeechText.chunks("short", 3500))
        assertTrue(SpeechText.chunks("", 3500).isEmpty())
    }

    @Test
    fun chunksWithNoSpacesStillSplit() {
        val parts = SpeechText.chunks("x".repeat(9000), 3500)
        assertEquals(listOf(3500, 3500, 2000), parts.map { it.length })
    }
}
