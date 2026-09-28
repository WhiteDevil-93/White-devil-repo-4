package com.whitedevil.ui.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.whitedevil.*
import com.whitedevil.MainActivity
import com.whitedevil.R
import com.whitedevil.ui.agent.AgentScreen
import com.whitedevil.ui.hub.ForgeHubScreen
import com.whitedevil.ui.onboarding.OnboardingScreen
import com.whitedevil.ui.settings.SettingsScreen
import com.whitedevil.ui.terminal.TerminalScreen
import com.whitedevil.ui.theme.WhiteDevilTheme
import com.whitedevil.ui.theme.WdColors
import com.whitedevil.ui.you.YouHomeScreen

@Composable
fun WhiteDevilApp(host: MainActivity) {
    WhiteDevilTheme {
        if (host.showOnboardingPublic()) {
            OnboardingScreen(onFinished = { host.completeOnboardingPublic() })
            return@WhiteDevilTheme
        }
        var settingsForm by remember { mutableStateOf(host.readSettingsForm()) }
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = Color.Transparent,
            bottomBar = {
                NavigationBar(
                    containerColor = Color(0xF0101012),
                    tonalElevation = 0.dp,
                    modifier = Modifier.navigationBarsPadding(),
                ) {
                    val tab = host.uiTabPublic()
                    navItem(
                        selected = tab == MainActivity.Tab.AGENT,
                        onClick = { host.selectTabPublic(MainActivity.Tab.AGENT) },
                        iconRes = R.drawable.ic_venice,
                        label = "Agent",
                        contentDescription = "Agent",
                    )
                    navItem(
                        selected = tab == MainActivity.Tab.FORGE_HUB,
                        onClick = { host.selectTabPublic(MainActivity.Tab.FORGE_HUB) },
                        iconRes = R.drawable.ic_home,
                        label = "Hub",
                        contentDescription = "Forge Hub",
                    )
                    navItem(
                        selected = tab == MainActivity.Tab.YOU,
                        onClick = { host.selectTabPublic(MainActivity.Tab.YOU) },
                        iconRes = R.drawable.ic_settings,
                        label = "You",
                        contentDescription = "You",
                    )
                }
            },
        ) { padding ->
            Box(Modifier.padding(padding)) {
                when (host.uiTabPublic()) {
                    MainActivity.Tab.AGENT -> AgentScreen(host)
                    MainActivity.Tab.FORGE_HUB -> ForgeHubScreen(host)
                    MainActivity.Tab.YOU -> when (host.uiYouSubPublic()) {
                        MainActivity.YouSub.HOME -> YouHomeScreen(
                            connectionSummary = host.connectionSummaryPublic(),
                            veniceReady = host.veniceKeyConfiguredPublic(),
                            onTerminal = { host.showYouSub(MainActivity.YouSub.TERMINAL) },
                            onSettings = {
                                settingsForm = host.readSettingsForm()
                                host.showYouSub(MainActivity.YouSub.SETTINGS)
                            },
                            onTestConnections = { host.runQuickConnectionTest(updateYouHome = true) },
                            onAddVeniceKey = { host.showVeniceKeySheet() },
                        )
                        MainActivity.YouSub.TERMINAL -> TerminalScreen(host, showBack = true)
                        MainActivity.YouSub.SETTINGS -> SettingsScreen(
                            host = host,
                            showBack = true,
                            form = settingsForm,
                            onFormChange = { settingsForm = it },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.navItem(
    selected: Boolean,
    onClick: () -> Unit,
    iconRes: Int,
    label: String,
    contentDescription: String,
) {
    NavigationBarItem(
        selected = selected,
        onClick = onClick,
        icon = {
            Box(
                modifier = Modifier
                    .size(width = 56.dp, height = 32.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (selected) Color(0x33CDB88F) else Color.Transparent),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(iconRes),
                    contentDescription = contentDescription,
                    tint = if (selected) WdColors.accent else WdColors.muted,
                )
            }
        },
        label = { Text(label, fontSize = 11.sp) },
        colors = NavigationBarItemDefaults.colors(
            selectedIconColor = WdColors.accent,
            selectedTextColor = WdColors.strong,
            unselectedIconColor = WdColors.muted,
            unselectedTextColor = WdColors.muted,
            indicatorColor = Color.Transparent,
        ),
    )
}
