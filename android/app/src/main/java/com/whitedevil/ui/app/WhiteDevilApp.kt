package com.whitedevil.ui.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
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
                    containerColor = Color(0xD9121216),
                    modifier = Modifier.navigationBarsPadding(),
                ) {
                    val tab = host.uiTabPublic()
                    NavigationBarItem(
                        selected = tab == MainActivity.Tab.AGENT,
                        onClick = { host.selectTabPublic(MainActivity.Tab.AGENT) },
                        icon = { Icon(painterResource(R.drawable.ic_venice), contentDescription = "Agent") },
                        label = { Text("Agent") },
                        colors = navColors(tab == MainActivity.Tab.AGENT),
                    )
                    NavigationBarItem(
                        selected = tab == MainActivity.Tab.FORGE_HUB,
                        onClick = { host.selectTabPublic(MainActivity.Tab.FORGE_HUB) },
                        icon = { Icon(painterResource(R.drawable.ic_home), contentDescription = "Forge Hub") },
                        label = { Text("Hub") },
                        colors = navColors(tab == MainActivity.Tab.FORGE_HUB),
                    )
                    NavigationBarItem(
                        selected = tab == MainActivity.Tab.YOU,
                        onClick = { host.selectTabPublic(MainActivity.Tab.YOU) },
                        icon = { Icon(painterResource(R.drawable.ic_settings), contentDescription = "You") },
                        label = { Text("You") },
                        colors = navColors(tab == MainActivity.Tab.YOU),
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
private fun navColors(selected: Boolean) = NavigationBarItemDefaults.colors(
    selectedIconColor = WdColors.accent,
    selectedTextColor = WdColors.strong,
    unselectedIconColor = WdColors.muted,
    unselectedTextColor = WdColors.muted,
    indicatorColor = Color(0x26FFFFFF),
)
