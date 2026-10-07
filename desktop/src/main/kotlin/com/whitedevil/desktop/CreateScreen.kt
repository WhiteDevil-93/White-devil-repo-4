package com.whitedevil.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

const val CREATE_WAN = "wan"
const val CREATE_LTX = "ltx"

/**
 * Create: where renders are built. Wan 2.2 14B (describe the scene, the hub writes the chain, send it to the
 * 14B runner) and LTX 2.5 (picture, prompt, render). Each tab is a full builder; nothing here is a link out.
 */
@Composable
fun CreateScreen(settings: Settings, tab: String, onTab: (String) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Wan 2.2 14B", tab == CREATE_WAN) { onTab(CREATE_WAN) }
            Chip("LTX 2.5", tab == CREATE_LTX) { onTab(CREATE_LTX) }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (tab == CREATE_LTX) LtxBuilderScreen(settings) else WanBuilderScreen(settings)
        }
    }
}

/**
 * The strip at the top of Thunder, Colab and Vast. The hub has one 14B runner connection and one ComfyUI
 * connection, whichever machine they are tunnelled to, so a render is built in Create and goes to whatever
 * is connected; this card says which builder suits this provider and opens it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RenderHereCard(provider: String, lines: List<String>, onCreate: (String) -> Unit) {
    Card("Render on $provider") {
        lines.forEach { Tip(it) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallButton("BUILD A WAN 2.2 14B RENDER", true) { onCreate(CREATE_WAN) }
            SmallButton("BUILD AN LTX 2.5 RENDER", false) { onCreate(CREATE_LTX) }
        }
    }
}
