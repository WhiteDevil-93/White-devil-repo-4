package com.whitedevil

import android.content.Context
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.whitedevil.ui.app.AttachmentUi
import com.whitedevil.ui.app.HubScreenUi
import com.whitedevil.ui.app.SettingsFormState
import com.whitedevil.ui.chat.ChatUiMessage

/**
 * Compose-facing API for [MainActivity]. Keeps WebView + agent logic in the Activity while the
 * entire visible shell is Jetpack Compose ([com.whitedevil.ui.app.WhiteDevilApp]).
 */
@Suppress("TooManyFunctions")
internal object MainActivityPublicApi

fun MainActivity.uiTabPublic(): MainActivity.Tab = uiTab
fun MainActivity.uiYouSubPublic(): MainActivity.YouSub = uiYouSub
fun MainActivity.showOnboardingPublic(): Boolean = showOnboarding
fun MainActivity.chatMessagesPublic(): SnapshotStateList<ChatUiMessage> = chatMessages
fun MainActivity.chatScrollTriggerPublic(): Int = chatScrollTrigger
fun MainActivity.agentThinkingPublic(): Boolean = agentThinking
fun MainActivity.agentInputTextPublic(): String = agentInputText
fun MainActivity.setAgentInputText(v: String) { agentInputText = v }
fun MainActivity.agentShowProgressPublic(): Boolean = agentShowProgress
fun MainActivity.agentComposerEnabledPublic(): Boolean = agentComposerEnabled
fun MainActivity.pendingAttachmentsUiPublic(): List<AttachmentUi> = pendingAttachmentsUi
fun MainActivity.connectionSummaryPublic(): String = connectionSummary
fun MainActivity.hubBannerVisiblePublic(): Boolean = hubBannerVisible
fun MainActivity.hubBannerTextPublic(): String = hubBannerText
fun MainActivity.hubConnectionLabelPublic(): String = hubConnectionLabel
fun MainActivity.hubLoadProgressPublic(): Float = hubLoadProgress
fun MainActivity.terminalLoadProgressPublic(): Float = terminalLoadProgress
fun MainActivity.hubScreensUiPublic(): List<HubScreenUi> = hubScreensUi
fun MainActivity.hubCurrentScreenIdPublic(): String? = hubCurrentScreenIdState
fun MainActivity.terminalPasteOpenPublic(): Boolean = terminalPasteOpen
fun MainActivity.terminalPasteTextPublic(): String = terminalPasteText
fun MainActivity.setTerminalPasteOpen(open: Boolean) { terminalPasteOpen = open }
fun MainActivity.setTerminalPasteText(t: String) { terminalPasteText = t }
fun MainActivity.terminalWebViewPublic() = terminalWebView
fun MainActivity.agentSelectedModelPublic(): String = agentSelectedModel
fun MainActivity.sendPasteToTerminalPublic() = sendPasteToTerminal()
fun MainActivity.veniceKeyConfiguredPublic(): Boolean = veniceKeyConfigured()
fun MainActivity.confirmClearAgentChatPublic() = confirmClearAgentChat()
fun MainActivity.onHubBannerClickPublic() { onHubBannerClick() }

fun MainActivity.selectTabPublic(tab: MainActivity.Tab) = selectTab(tab)
fun MainActivity.completeOnboardingPublic() = completeOnboarding()
fun MainActivity.readSettingsForm(): SettingsFormState = readSettingsFormFromPrefs()

fun MainActivity.removePendingAttachment(index: Int) = removePendingAttachmentAt(index)

fun MainActivity.obtainHubHostFrame(context: Context): FrameLayout = ensureHubHostFrame(context)
fun MainActivity.ensureTerminalWebViewMounted(context: Context): FrameLayout = ensureTerminalHostFrame(context)
