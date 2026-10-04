package com.whitedevil.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

/**
 * Renders the Venice screen off-screen to a PNG for comparing with the UX Pilot design. Skipped unless SHOT_DIR is
 * set; it touches no real data (no session file, an unreachable hub).
 */
class VeniceLayoutShot {
    @OptIn(ExperimentalTestApi::class)
    @Test fun `render the venice screen`() {
        val dir = System.getenv("SHOT_DIR")
        assumeTrue(!dir.isNullOrBlank(), "SHOT_DIR not set")
        for ((w, h, name) in listOf(Triple(1680, 1000, "venice_wide.png"), Triple(900, 900, "venice_narrow.png"))) {
            runDesktopComposeUiTest(w, h) {
                val session = AgentSession(null)
                session.lines.addAll(
                    listOf(
                        ChatLine(ROLE_USER, "You", "Check the overnight LTX renders and tell me which one to re-run."),
                        ChatLine(ROLE_TOOL_CALL, "Tool · hub_request", """{"method":"GET","path":"/api/ltx/jobs"}"""),
                        ChatLine(ROLE_TOOL_OUT, "Output · hub_request", "[{\"id\":\"d74678524da9\",\"status\":\"done\"}]"),
                        ChatLine(ROLE_TOOL_CALL, "Tool · review_latest_render", "{}"),
                        ChatLine(ROLE_TOOL_OUT, "Output · review_latest_render", "Contact sheet: 8 frames, flicker around frame 48."),
                        ChatLine(ROLE_VENICE, "Venice", "The newest LTX chain finished. Frames 40-56 flicker in the wings; I'd re-run clip 3 with a slower camera move. Want me to queue it?"),
                    ),
                )
                val clips = (1..4).map { MediaClip("ltx_chain_d7467852_c$it.mp4", it, 1791051302.0 + it * 60, 12.0, "ltx") }
                // The workspace has a live clock and a 30 s hub refresh: step time by hand or the test never idles.
                mainClock.autoAdvance = false
                setContent {
                    MaterialTheme(colorScheme = WhiteDevilColors) {
                        AgentScreen(
                            Settings(hubUrl = "http://127.0.0.1:9", veniceApiKey = ""), onOpenSettings = {}, session = session,
                            library = LibraryUiState.Loaded(listOf(MediaGroup("ltx", "LTX", "test", "ltx", null, clips)), emptyList()),
                        )
                    }
                }
                repeat(20) { mainClock.advanceTimeByFrame() }
                Thread.sleep(1500)
                repeat(20) { mainClock.advanceTimeByFrame() }
                val img = onRoot().captureToImage().toAwtImage()
                ImageIO.write(img, "png", File(dir, name))
            }
        }
    }
}
