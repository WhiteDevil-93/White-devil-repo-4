package com.whitedevil.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.HealthAndSafety
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.ModelTraining
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class Screen(val label: String, val icon: ImageVector, val group: NavGroup) {
    Home("Home", Icons.Outlined.Home, NavGroup.Studio),
    Create("Create", Icons.Outlined.AddCircleOutline, NavGroup.Studio),
    Renders("Renders", Icons.Outlined.Movie, NavGroup.Studio),
    Gallery("Gallery", Icons.Outlined.Image, NavGroup.Studio),
    Terminal("Shell", Icons.Outlined.Terminal, NavGroup.Studio),
    Colab("Colab", Icons.Outlined.Memory, NavGroup.Laptop),
    Ltx("LTX", Icons.Outlined.Bolt, NavGroup.Laptop),
    Thunder("Thunder", Icons.Outlined.Cloud, NavGroup.Laptop),
    Vast("Vast", Icons.Outlined.Dns, NavGroup.Laptop),
    Setup("Setup", Icons.Outlined.Tune, NavGroup.Laptop),
    LoraTrain("Train LoRA", Icons.Outlined.ModelTraining, NavGroup.Laptop),
    Qwen("Qwen API", Icons.Outlined.Memory, NavGroup.Laptop),
    Agent("Venice", Icons.Outlined.AutoAwesome, NavGroup.Laptop),
    Caretaker("Caretaker", Icons.Outlined.HealthAndSafety, NavGroup.Laptop),
    Settings("Settings", Icons.Outlined.Settings, NavGroup.None),
}

enum class NavGroup(val title: String?) { Studio("STUDIO"), Laptop("LAPTOP"), None(null) }

/** The Forge Hub left sidebar: wordmark, two labelled groups, session footer. 236dp like the design. */
@Composable
fun ForgeSidebar(current: Screen, onSelect: (Screen) -> Unit, scale: Float = UiScale.DEFAULT, onScale: (Float) -> Unit = {}) {
    Column(
        Modifier.width(236.dp).fillMaxHeight().background(Forge.Side)
            .border(width = 1.dp, color = Forge.Line, shape = RoundedCornerShape(0.dp)),
    ) {
        Row(
            Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(28.dp).clip(RoundedCornerShape(8.dp))
                    .background(Brush.linearGradient(listOf(Forge.Acc, Forge.Acc2))),
                contentAlignment = Alignment.Center,
            ) { Text("F", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp) }
            Spacer(Modifier.width(10.dp))
            Text("Forge Hub", color = Forge.Fg, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        }

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
            listOf(NavGroup.Studio, NavGroup.Laptop).forEach { group ->
                Text(
                    group.title.orEmpty(),
                    color = Forge.Dim, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.4.sp,
                    modifier = Modifier.padding(start = 10.dp, top = 22.dp, bottom = 8.dp),
                )
                Screen.entries.filter { it.group == group }.forEach { SidebarItem(it, it == current) { onSelect(it) } }
            }
        }

        Column(Modifier.padding(12.dp)) {
            SidebarItem(Screen.Settings, Screen.Settings == current) { onSelect(Screen.Settings) }
            SizeControl(scale, onScale)
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Forge.Panel).padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(28.dp).clip(CircleShape).background(Forge.Panel2), contentAlignment = Alignment.Center) {
                    Text("O", color = Forge.Acc3, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                }
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("Operator", color = Forge.Fg, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Text("local session", color = Forge.Dim, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun SidebarItem(screen: Screen, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Row(
        Modifier.fillMaxWidth().height(38.dp).padding(vertical = 1.dp)
            .clip(shape)
            .background(if (selected) Forge.Panel2 else Color.Transparent)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 3dp accent bar on the active row, the same cue the design uses.
        Box(Modifier.width(3.dp).height(18.dp).clip(RoundedCornerShape(2.dp)).background(if (selected) Forge.Acc else Color.Transparent))
        Spacer(Modifier.width(9.dp))
        Icon(screen.icon, contentDescription = null, tint = if (selected) Forge.Acc3 else Forge.Mut, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(12.dp))
        Text(
            screen.label,
            color = if (selected) Forge.Fg else Forge.Mut,
            fontSize = 14.sp, fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

enum class HubHealth { Connecting, Online, Unreachable }

/** 52dp top bar: page title and a hub status pill. The pill reports only what was observed. */
@Composable
fun ForgeTopBar(title: String, health: HubHealth, hubLabel: String) {
    Row(
        Modifier.fillMaxWidth().height(52.dp).background(Forge.Bg).padding(horizontal = 28.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(title, color = Forge.Fg, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        val (color, text) = when (health) {
            HubHealth.Online -> Forge.Ok to "hub connected"
            HubHealth.Unreachable -> Forge.Bad to "hub unreachable"
            HubHealth.Connecting -> Forge.Warn to "connecting"
        }
        StatusPill(text, color)
        Spacer(Modifier.weight(1f))
        Text(hubLabel, color = Forge.Dim, fontSize = 12.sp)
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Forge.Line))
}

@Composable
fun StatusPill(text: String, color: Color) {
    Row(
        Modifier.clip(RoundedCornerShape(99.dp)).background(color.copy(alpha = 0.08f))
            .border(1.dp, color.copy(alpha = 0.28f), RoundedCornerShape(99.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Text and interface size: A- / A+ and the current percentage (click it for 100%). Ctrl + / Ctrl - / Ctrl 0 do the same. */
@Composable
fun SizeControl(scale: Float, onScale: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        SizeButton("A−", enabled = scale > UiScale.MIN + 0.001f) { onScale(UiScale.step(scale, -1)) }
        Text(UiScale.percent(scale), color = Forge.Mut, fontSize = 12.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f).clickable { onScale(UiScale.DEFAULT) }.padding(vertical = 6.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        SizeButton("A+", enabled = scale < UiScale.MAX - 0.001f) { onScale(UiScale.step(scale, +1)) }
    }
}

@Composable
private fun SizeButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Text(
        label, color = if (enabled) Forge.Fg else Forge.Dim, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(shape).background(Forge.Panel).border(1.dp, Forge.Line, shape)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 14.dp, vertical = 6.dp),
    )
}
