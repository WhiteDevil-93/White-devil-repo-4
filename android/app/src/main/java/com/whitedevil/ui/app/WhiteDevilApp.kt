package com.whitedevil.ui.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.whitedevil.*
import com.whitedevil.MainActivity
import com.whitedevil.R
import com.whitedevil.ui.agent.AgentScreen
import com.whitedevil.ui.components.WdBottomBar
import com.whitedevil.ui.components.WdTabItem
import com.whitedevil.ui.files.PhoneFilesScreen
import com.whitedevil.ui.hub.ForgeHubScreen
import com.whitedevil.ui.onboarding.OnboardingScreen
import com.whitedevil.ui.security.BiometricLockScreen
import com.whitedevil.ui.settings.SettingsScreen
import com.whitedevil.ui.terminal.TerminalScreen
import com.whitedevil.ui.theme.WhiteDevilTheme
import com.whitedevil.ui.you.YouHomeScreen

private val tabs = listOf(
    WdTabItem("agent", "Agent", R.drawable.ic_venice),
    WdTabItem("hub", "Hub", R.drawable.ic_home),
    WdTabItem("you", "You", R.drawable.ic_settings),
)

@Composable
fun WhiteDevilApp(host: MainActivity) {
    WhiteDevilTheme {
        if (host.showOnboardingPublic()) {
            OnboardingScreen(onFinished = { host.completeOnboardingPublic() })
            return@WhiteDevilTheme
        }
        if (!host.appUnlockedPublic()) {
            BiometricLockScreen(
                statusLine = host.biometricStatusPublic(),
                onUnlock = { host.promptBiometricUnlockPublic() },
                onUsePassword = { host.unlockViaSettingsFallbackPublic() },
            )
            return@WhiteDevilTheme
        }
        var settingsForm by remember { mutableStateOf(host.readSettingsForm()) }
        val selectedTab = when (host.uiTabPublic()) {
            MainActivity.Tab.AGENT -> "agent"
            MainActivity.Tab.FORGE_HUB -> "hub"
            MainActivity.Tab.YOU -> "you"
        }
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = Color.Black,
            bottomBar = {
                WdBottomBar(
                    items = tabs,
                    selectedId = selectedTab,
                    onSelect = { id ->
                        when (id) {
                            "agent" -> host.selectTabPublic(MainActivity.Tab.AGENT)
                            "hub" -> host.selectTabPublic(MainActivity.Tab.FORGE_HUB)
                            "you" -> host.selectTabPublic(MainActivity.Tab.YOU)
                        }
                    },
                )
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
                            onPhoneFiles = { host.showYouSub(MainActivity.YouSub.FILES) },
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
                        MainActivity.YouSub.FILES -> PhoneFilesScreen(host)
                    }
                }
            }
        }
    }
}
