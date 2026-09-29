package com.whitedevil.ui.chat

import com.whitedevil.MainActivity

data class ChatUiMessage(
    val id: Long,
    val sender: String,
    val message: String,
    val role: Int,
    val toolExpanded: Boolean = false,
)

fun ChatUiMessage.isTool(): Boolean =
    role == MainActivity.ROLE_TOOL_CALL || role == MainActivity.ROLE_TOOL_OUTPUT

fun ChatUiMessage.isUser(): Boolean = role == MainActivity.ROLE_USER

fun ChatUiMessage.isInfo(): Boolean = role == MainActivity.ROLE_INFO
