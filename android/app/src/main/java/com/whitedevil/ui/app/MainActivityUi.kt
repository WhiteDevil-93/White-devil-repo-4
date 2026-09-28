package com.whitedevil.ui.app

import android.net.Uri
import com.whitedevil.agent.Attachments

/** Compose-observable attachment row for the Agent composer. */
data class AttachmentUi(
    val uri: Uri,
    val mime: String,
    val name: String,
    val size: Long,
    val kind: Attachments.Kind,
)

data class HubScreenUi(val id: String, val title: String)

/** Settings form bound to Compose (mirrors encrypted prefs). */
data class SettingsFormState(
    val veniceKey: String = "",
    val systemPrompt: String = "",
    val webSearch: Boolean = false,
    val relayUrl: String = "",
    val relayUser: String = "",
    val relayPass: String = "",
    val laptopUser: String = "",
    val laptopPass: String = "",
)
