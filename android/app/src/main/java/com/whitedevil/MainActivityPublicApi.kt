package com.whitedevil

import androidx.compose.runtime.snapshots.SnapshotStateList
import com.whitedevil.ui.app.AttachmentUi
import com.whitedevil.ui.app.HubScreenUi
import com.whitedevil.ui.app.SettingsFormState
import com.whitedevil.ui.chat.ChatUiMessage

/**
 * Compose-facing API for [MainActivity]. Relay, agent, and hub JSON fetching live in the Activity;
 * the visible shell is Jetpack Compose ([com.whitedevil.ui.app.WhiteDevilApp]).
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
fun MainActivity.hubScreensUiPublic(): List<HubScreenUi> = hubScreensUi
fun MainActivity.hubCurrentScreenIdPublic(): String? = hubCurrentScreenIdState
fun MainActivity.hubScreenJsonPublic(): String = hubScreenJson
fun MainActivity.hubScreenLoadingPublic(): Boolean = hubScreenLoading
fun MainActivity.hubScreenErrorPublic(): String? = hubScreenError
fun MainActivity.hubBlockedByUpdatePublic(): Boolean = hubBlockedByUpdate
fun MainActivity.hubForceUpdateVersionPublic(): Int = hubForceUpdateVersion
fun MainActivity.hubAppVersionPublic(): Int = BuildConfig.VERSION_CODE
fun MainActivity.terminalPasteOpenPublic(): Boolean = terminalPasteOpen
fun MainActivity.terminalPasteTextPublic(): String = terminalPasteText
fun MainActivity.setTerminalPasteOpen(open: Boolean) { terminalPasteOpen = open }
fun MainActivity.setTerminalPasteText(t: String) { terminalPasteText = t }
fun MainActivity.terminalLogPublic(): SnapshotStateList<String> = terminalLog
fun MainActivity.terminalRunningPublic(): Boolean = terminalRunning
fun MainActivity.agentSelectedModelPublic(): String = agentSelectedModel
fun MainActivity.sendPasteToTerminalPublic() = sendPasteToTerminal()
fun MainActivity.runTerminalCommandPublic(cmd: String) = runTerminalCommand(cmd)
fun MainActivity.clearTerminalLogPublic() { terminalLog.clear() }
fun MainActivity.veniceKeyConfiguredPublic(): Boolean = veniceKeyConfigured()
fun MainActivity.confirmClearAgentChatPublic() = confirmClearAgentChat()
fun MainActivity.onHubBannerClickPublic() { onHubBannerClick() }
fun MainActivity.dismissHubBannerPublic() = dismissHubBanner()
fun MainActivity.agentHasConversationPublic(): Boolean = agentHasConversation()
fun MainActivity.agentStatusSubtitlePublic(): String = agentStatusSubtitle

fun MainActivity.selectTabPublic(tab: MainActivity.Tab) = selectTab(tab)
fun MainActivity.completeOnboardingPublic() = completeOnboarding()
fun MainActivity.readSettingsForm(): SettingsFormState = readSettingsFormFromPrefs()
fun MainActivity.loadHubManifestPublic() = loadHubManifest()
fun MainActivity.showHubScreenPublic(id: String) = showHubScreen(id)
fun MainActivity.refreshHubNativeScreenPublic() = refreshHubNativeScreen()
fun MainActivity.downloadHubUpdatePublic() = downloadHubUpdate()

fun MainActivity.relayBasePublic(): String = relayBaseUrl()

fun MainActivity.relayAuthPublic(): String = relayAuthorization()

fun MainActivity.hubRelayPostPublic(path: String, jsonBody: String, refreshAfter: Boolean = true) =
    hubRelayPost(path, jsonBody, refreshAfter)

fun MainActivity.hubRelayPostWithResponsePublic(path: String, jsonBody: String, onResult: (String) -> Unit) =
    hubRelayPostWithResponse(path, jsonBody, onResult)

fun MainActivity.removePendingAttachment(index: Int) = removePendingAttachmentAt(index)

fun MainActivity.appUnlockedPublic(): Boolean = appUnlocked
fun MainActivity.biometricStatusPublic(): String = biometricStatusLabel()
fun MainActivity.promptBiometricUnlockPublic() = promptBiometricUnlock()
fun MainActivity.unlockViaSettingsFallbackPublic() = unlockViaSettingsFallback()
fun MainActivity.phoneFolderUriPublic(): android.net.Uri? = phoneFolderUri()
fun MainActivity.pickPhoneFolderPublic() = pickPhoneFolder()
fun MainActivity.pickPhoneFilesPublic() = pickPhoneFiles()
fun MainActivity.pickPhoneMediaPublic() = pickPhoneMedia()
fun MainActivity.requestPhoneMediaPermissionPublic() = requestPhoneMediaPermission()
fun MainActivity.attachPhoneUriPublic(uri: android.net.Uri) = attachPhoneUri(uri)

// Device-bound auth (hub/auth.py). Additive to relay basic auth, never a replacement.
fun MainActivity.deviceAuthStatePublic(): com.whitedevil.security.DeviceAuth.State = deviceAuthState
fun MainActivity.deviceAuthBusyPublic(): Boolean = deviceAuthBusy
fun MainActivity.deviceAuthMessagePublic(): String = deviceAuthMessage
fun MainActivity.enrolDeviceAuthPublic(name: String) = enrolDeviceAuth(name)
fun MainActivity.signInDeviceAuthPublic() = signInDeviceAuth()
fun MainActivity.forgetDeviceAuthPublic() = forgetDeviceAuth()
