package com.whitedevil

import com.whitedevil.agent.Artifacts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtifactsTest {

    @Test
    fun aFencedHtmlPageIsReturnedAsIs() {
        val page = "<!DOCTYPE html><html><body><h1>Hi</h1></body></html>"
        assertEquals(page, Artifacts.previewable("Here you go:\n```html\n$page\n```\nEnjoy."))
    }

    @Test
    fun anHtmlFragmentIsWrappedSoItRendersOnAPhone() {
        val out = Artifacts.previewable("```html\n<button onclick=\"alert(1)\">x</button>\n```")!!
        assertTrue(out, out.startsWith("<!doctype html>") && out.contains("viewport") && out.contains("<button"))
    }

    @Test
    fun svgIsPreviewableAndAnUnlabelledFenceWithHtmlIsDetected() {
        val svg = Artifacts.previewable("```svg\n<svg width=\"10\" height=\"10\"><circle r=\"4\"/></svg>\n```")!!
        assertTrue(svg, svg.contains("<svg") && svg.contains("<!doctype html>"))
        assertNotNull(Artifacts.previewable("```\n<html><body>x</body></html>\n```"))
    }

    @Test
    fun ordinaryCodeAndEmptyBlocksAreNotPreviewable() {
        assertNull(Artifacts.previewable("```python\nprint('<html>')\n```"))
        assertNull(Artifacts.previewable("```kotlin\nval x = 1\n```"))
        assertNull(Artifacts.previewable("```html\n\n```"))
        assertNull(Artifacts.previewable("no code at all"))
        assertNull(Artifacts.previewable("```\nplain text block\n```"))
    }

    @Test
    fun theFirstPreviewableBlockWinsAndOthersAreSkipped() {
        val md = "```python\nx=1\n```\n```html\n<p>one</p>\n```\n```html\n<p>two</p>\n```"
        val out = Artifacts.previewable(md)!!
        assertTrue(out, out.contains("<p>one</p>") && !out.contains("two"))
    }
}
