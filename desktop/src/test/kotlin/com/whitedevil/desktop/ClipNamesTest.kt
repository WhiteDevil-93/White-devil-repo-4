package com.whitedevil.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** Real file names from the hub, and what the app should call them. */
class ClipNamesTest {
    @Test fun `runner prefixes, tags and job ids are not part of the title`() {
        assertEquals("bestfriends night v3 · clip 1", prettyClipName("smoke_bestfriends-night-v3_c01_wanbot.mp4"))
        assertEquals("friends room · clip 30", prettyClipName("smoke_friends-room_c30_14b.mp4"))
        assertEquals("clip 1", prettyClipName("smoke_529d7449a4a1_c01_14b.mp4"), "a bare job id says nothing, so only the clip number is left")
        assertEquals("test14b · clip 1", prettyClipName("smoke_test14b_c01_14b.mp4"))
    }

    @Test fun `pack names lose the baked-in settings but keep the resolution`() {
        assertEquals("goon p05 · clip 1 · 1280×704", prettyClipName("smoke_goon_p05_c01_1280x704_f81_s28.mp4"))
        assertEquals("goon p01 · clip 64 · 1280×704", prettyClipName("smoke_goon_p01_c64_1280x704_f81_s28.mp4"))
    }

    @Test fun `ltx renders read as their prompt and length`() {
        assertEquals("duration 10 seconds start state person a", prettyClipName("ltx_chain_duration-10-seconds-start-state-person-a_1fc9ca3b01a7.mp4"))
        assertEquals("two men on a sofa · 4 s", prettyClipName("ltx_two-men-on-a-sofa_b2c3d4e5f6a7_97f.mp4"))
        assertEquals("two men on a sofa · 10 s · sharpened 2×", prettyClipName("ltx_two-men-on-a-sofa_b2c3d4e5f6a7_241f_2x.mp4"))
    }

    @Test fun `other names are tidied but never invented`() {
        assertEquals("KEEPER 011 · seed 20260924", prettyClipName("KEEPER_011_seed20260924.mp4"))
        assertEquals("ti2v5b CLEAN BASE · 832×480 · seed 42", prettyClipName("smoke_ti2v5b_CLEAN_BASE_832x480_f81_s20_seed42.mp4"))
        assertEquals("notes", prettyClipName("notes.MP4"))
        assertEquals("smoke_.mp4", prettyClipName("smoke_.mp4"), "if nothing readable is left the real name is shown")
        assertEquals("", prettyClipName("").ifEmpty { "" })
    }

    @Test fun `the word smoke never reaches the screen for a runner-named clip`() {
        listOf("smoke_bestfriends-night-v3_c01_wanbot.mp4", "smoke_goon_p05_c01_1280x704_f81_s28.mp4", "smoke_ti2v5b_CLEAN_BASE_832x480_f81_s20_seed42.mp4", "smoke_529d7449a4a1_c01_14b.mp4")
            .forEach { assertFalse("smoke" in prettyClipName(it).lowercase(), it) }
    }

    @Test fun `the catch-all group is called unsorted, not tests`() {
        assertEquals("Unsorted renders", prettyGroupTitle("Tests & experiments", "test"))
        assertEquals("Pack 5 · Mirror Feedback Loop", prettyGroupTitle("Pack 5 · Mirror Feedback Loop", "pack"))
        assertEquals("Unsorted", kindLabel("test"))
        val sections = buildGallerySections(listOf(MediaGroup("t", "Tests & experiments", "test", null, null, listOf(MediaClip("smoke_a.mp4", null, 1.0, 1.0, null)))))
        assertEquals("Unsorted renders", sections.single().group.title)
        assertFalse(sections.single().group.title.contains("Test", ignoreCase = true))
    }
}
