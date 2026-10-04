package com.whitedevil

import com.whitedevil.relay.RelayHttp
import com.whitedevil.ui.hub.LoraTrainJson
import com.whitedevil.ui.hub.ltTrainConsequences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoraTrainModelsTest {
    private val ds = """{"id":"abcdefabcdef","name":"Test Man","lora":"Test_Man","kind":"character","trigger":"ohwx_man",
        "items":[{"file":"a_1.jpg","type":"image","size":2048,"caption":"the man sits","caption_by":"x-ai/grok-4.5"},
                 {"file":"b_2.mp4","type":"video","size":4096,"caption":"","caption_error":"refused"}],
        "ready":false,"problems":["2 items; needs at least 20"],"warnings":["consent"],
        "estimate":{"minutes":95,"steps":2000,"seconds_per_step":1.2,"cost_units":14.1,"units_per_hr":8.9},
        "captioning":{"status":"running","done":1,"total":2},"settings":{"steps":1500},"kind_info":{"recommended":"25-40 items"}}"""

    @Test fun datasetParses() {
        val d = LoraTrainJson.dataset(ds)!!
        assertEquals(1, d.images); assertEquals(1, d.videos); assertEquals(1, d.captioned)
        assertEquals("refused", d.items[1].error); assertEquals(14.1, d.estimate!!.costUnits!!, 0.0)
        assertEquals(1500, d.steps); assertNull(d.rank); assertFalse(d.ready); assertEquals("running", d.captioning)
    }

    @Test fun runsAndUploadsParse() {
        val r = LoraTrainJson.runs("""[{"id":"111111111111","dataset":"abcdefabcdef","name":"T","status":"training","step":"x",
            "step_now":250,"step_total":2000,"pulled":[{"size":10,"verified":true}],"final":null}]""")!!.single()
        assertTrue(r.active); assertEquals(250, r.stepNow); assertTrue(r.pulled.single().verified); assertNull(r.final)
        val u = LoraTrainJson.upload("""{"added":3,"captions":1,"skipped":["junk.bin: not an image or video ffmpeg can read"]}""")!!
        assertEquals(3, u.added); assertEquals(1, u.captions); assertTrue("ffmpeg" in u.skipped.single())
        assertNull(LoraTrainJson.dataset("<html>502</html>"))
    }

    @Test fun confirmTalksComputeUnitsNotDollars() {
        val c = ltTrainConsequences(LoraTrainJson.dataset(ds)!!)
        assertTrue(c.any { "14.1 Colab compute units" in it }); assertTrue(c.any { "renders are refused" in it })
        assertTrue(c.none { "$" in it })
    }

    @Test fun requestBodies() {
        assertEquals("""{"trigger":"zz","steps":1200}""", LoraTrainJson.settings("zz", 1200, null))
        assertEquals("""{"a.jpg":"x \"y\""}""", LoraTrainJson.captions(mapOf("a.jpg" to "x \"y\"")))
        assertEquals("""{"name":"N","kind":"motion","trigger":"t"}""", LoraTrainJson.newDataset(" N ", "motion", "t "))
    }

    @Test fun hubDetailIsShown() {
        assertEquals("A LoRA is already training; wait for it or cancel it.",
            RelayHttp.hubDetail("""{"detail":"A LoRA is already training; wait for it or cancel it."}"""))
        assertEquals("say \"hi\"", RelayHttp.hubDetail("""{"detail":"say \"hi\""}"""))
        assertNull(RelayHttp.hubDetail("<html></html>"))
    }
}
