package com.whitedevil

import android.app.AlertDialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.text.InputType
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.webkit.CookieManager
import android.webkit.HttpAuthHandler
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.whitedevil.ui.app.AttachmentUi
import com.whitedevil.ui.app.HubScreenUi
import com.whitedevil.ui.app.SettingsFormState
import com.whitedevil.ui.app.WhiteDevilApp
import com.whitedevil.ui.chat.ChatUiMessage
import com.whitedevil.agent.Agent
import com.whitedevil.agent.AgentEvent
import com.whitedevil.agent.Attachments
import com.whitedevil.agent.ChatMessage
import com.whitedevil.agent.MessageContent
import com.whitedevil.agent.ToolBox
import com.whitedevil.agent.VeniceClient
import com.whitedevil.agent.stripBlobs
import com.whitedevil.agent.textContent
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {

    enum class Tab { AGENT, FORGE_HUB, YOU }

    enum class YouSub { HOME, TERMINAL, SETTINGS }

    private data class Screen(val id: String, val title: String, val icon: String, val url: String)

    private val prefs by lazy { SettingsManager.getPrefs(this) }
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    internal var uiTab by mutableStateOf(Tab.AGENT)
    internal var uiYouSub by mutableStateOf(YouSub.HOME)
    internal var showOnboarding by mutableStateOf(false)
    internal var agentInputText by mutableStateOf("")
    internal val pendingAttachmentsUi = mutableStateListOf<AttachmentUi>()
    internal var agentShowProgress by mutableStateOf(false)
    internal var agentComposerEnabled by mutableStateOf(true)
    internal var connectionSummary by mutableStateOf("Tap Test connections on You home or in Settings.")
    internal var hubBannerText by mutableStateOf("")
    internal var hubBannerVisible by mutableStateOf(false)
    internal var agentStatusSubtitle by mutableStateOf("On-device agent")
    internal val hubScreensUi = mutableStateListOf<HubScreenUi>()
    internal var hubCurrentScreenIdState by mutableStateOf<String?>(null)
    internal var hubConnectionLabel by mutableStateOf("Checking…")
    internal var hubLoadProgress by mutableFloatStateOf(-1f)
    internal var terminalLoadProgress by mutableFloatStateOf(-1f)
    internal var terminalPasteOpen by mutableStateOf(false)
    internal var terminalPasteText by mutableStateOf("")

    private var hubHostFrame: FrameLayout? = null
    private var terminalHostFrame: FrameLayout? = null
    private lateinit var snackbarAnchor: View

    // Forge Hub UI state (isolated)
    private lateinit var hubContent: FrameLayout
    private val hubWebViews = HashMap<String, WebView>()
    private var hubScreens: List<Screen> = emptyList()
    private var currentHubScreenId: String? = null
    private var hubBlockedByUpdate = false
    private var hubWebRev = 0
    private val authTries = HashMap<String, Int>()

    // Terminal tab state (isolated)
    internal var terminalWebView: WebView? = null

    // Agent tab state
    internal val chatMessages = mutableStateListOf<ChatUiMessage>()
    internal var chatScrollTrigger by mutableIntStateOf(0)
    internal var agentThinking by mutableStateOf(false)
    private var nextChatId = 1L
    private val agentModels = listOf(
        "zai-org-glm-5-2",
        "zai-org-glm-5",
        "venice-uncensored",
        "venice-uncensored-1-2",
        "kimi-k2-6",
        "claude-opus-4-8",
    )
    internal var agentSelectedModel by mutableStateOf(SettingsManager.DEFAULT_MODEL)
    private var currentAgentJob: Job? = null

    // Agent attachments (photos, videos, audio, documents)
    private data class PendingAttachment(
        val uri: Uri,
        val mime: String,
        val name: String,
        val size: Long,
        val kind: Attachments.Kind,
    )
    private val pendingAttachments = mutableListOf<PendingAttachment>()
    private var pendingCameraUri: Uri? = null
    private var pendingPermissionAction: (() -> Unit)? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            if (grants.values.all { it }) {
                pendingPermissionAction?.invoke()
            } else {
                Toast.makeText(this, "Permission denied — the file picker still works without it", Toast.LENGTH_LONG).show()
            }
            pendingPermissionAction = null
        }

    private val pickImagesLauncher =
        registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
            uris.forEach { addContentAttachment(it) }
            renderAttachmentStrip()
        }

    private val pickVideosLauncher =
        registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
            uris.forEach { addContentAttachment(it) }
            renderAttachmentStrip()
        }

    private val pickFilesLauncher =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            uris.forEach { addContentAttachment(it) }
            renderAttachmentStrip()
        }

    private val takePictureLauncher =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
            if (ok) pendingCameraUri?.let { addContentAttachment(it) }
            pendingCameraUri = null
            renderAttachmentStrip()
        }

    private val captureVideoLauncher =
        registerForActivityResult(ActivityResultContracts.CaptureVideo()) { ok ->
            if (ok) pendingCameraUri?.let { addContentAttachment(it) }
            pendingCameraUri = null
            renderAttachmentStrip()
        }

    // Current navigation state (mirrors uiTab for legacy call sites)
    private val activeTab: Tab get() = uiTab
    private val youSubScreen: YouSub get() = uiYouSub
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var fullscreenView: View? = null
    private var apkDownloadId = -1L

    private val relayBase get() = prefs.getString(SettingsManager.KEY_RELAY_URL, SettingsManager.DEFAULT_RELAY_URL)!!.trimEnd('/')

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()

        when (intent?.data?.getQueryParameter("tab")) {
            "forge", "hub" -> selectTab(Tab.FORGE_HUB)
            "terminal", "term" -> {
                selectTab(Tab.YOU)
                showYouSub(YouSub.TERMINAL)
            }
            "settings" -> {
                selectTab(Tab.YOU)
                showYouSub(YouSub.SETTINGS)
            }
            else -> selectTab(Tab.AGENT)
        }
        maybeShowOnboarding()

        if (prefs.getString(SettingsManager.KEY_RELAY_PASS, "").isNullOrEmpty()) {
            // First run hint or open settings
        } else {
            loadHubManifest()
        }

        handleSharedIntent(intent)
        updateAgentSetupState()

        registerReceiver(downloadDone, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), RECEIVER_EXPORTED)
    }

    override fun onResume() {
        super.onResume()
        updateAgentSetupState()
        if (activeTab == Tab.FORGE_HUB && !hubBlockedByUpdate) {
            val wv = hubWebViews[currentHubScreenId]
            wv?.reload()
        }
    }

    override fun onDestroy() {
        try { unregisterReceiver(downloadDone) } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        when (intent.data?.getQueryParameter("tab")) {
            "agent" -> selectTab(Tab.AGENT)
            "forge", "hub" -> {
                selectTab(Tab.FORGE_HUB)
                intent.data?.getQueryParameter("screen")?.let { showHubScreen(it) }
            }
            "terminal", "term" -> {
                selectTab(Tab.YOU)
                showYouSub(YouSub.TERMINAL)
            }
            "settings" -> {
                selectTab(Tab.YOU)
                showYouSub(YouSub.SETTINGS)
            }
        }
        handleSharedIntent(intent)
    }

    // =========================================================================
    // UI Layout Construction
    // =========================================================================

    private fun buildUi() {
        @Suppress("DEPRECATION")
        window.setDecorFitsSystemWindows(false)
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.BLACK
        @Suppress("DEPRECATION")
        window.navigationBarColor = Color.BLACK
        agentSelectedModel = prefs.getString(SettingsManager.KEY_VENICE_MODEL, SettingsManager.DEFAULT_MODEL)
            ?: SettingsManager.DEFAULT_MODEL
        snackbarAnchor = window.decorView
        ensureHubHostFrame(this)
        setContent { WhiteDevilApp(this@MainActivity) }
        initAgentWelcomeMessages()
    }

    private fun maybeShowOnboarding() {
        showOnboarding = !prefs.getBoolean(SettingsManager.KEY_ONBOARDING_COMPLETE, false)
    }

    internal fun completeOnboarding() {
        prefs.edit().putBoolean(SettingsManager.KEY_ONBOARDING_COMPLETE, true).apply()
        showOnboarding = false
    }

    internal fun selectTab(tab: Tab) {
        uiTab = tab
        if (tab == Tab.YOU) {
            showYouSub(YouSub.HOME)
            refreshYouHomeSummary()
        }
        if (tab == Tab.FORGE_HUB && hubScreens.isEmpty()) {
            loadHubManifest()
        }
    }

    internal fun showYouSub(sub: YouSub) {
        uiYouSub = sub
        if (sub == YouSub.TERMINAL) {
            ensureTerminalWebViewLoaded()
        }
        if (sub == YouSub.HOME) refreshYouHomeSummary()
    }

    private fun initAgentWelcomeMessages() {
        if (chatMessages.isNotEmpty()) return
        val restoredHistory = loadAgentHistory()
        if (restoredHistory.isEmpty()) {
            agentStatusSubtitle = "On-device agent"
        } else {
            agentStatusSubtitle = "${restoredHistory.size} messages restored"
            renderHistoryBubbles(restoredHistory)
        }
    }

    internal fun readSettingsFormFromPrefs(): SettingsFormState = SettingsFormState(
        veniceKey = prefs.getString(SettingsManager.KEY_VENICE_API_KEY, "").orEmpty(),
        systemPrompt = prefs.getString(SettingsManager.KEY_VENICE_SYSTEM_PROMPT, SettingsManager.DEFAULT_SYSTEM_PROMPT).orEmpty(),
        webSearch = prefs.getBoolean(SettingsManager.KEY_VENICE_WEB_SEARCH, false),
        relayUrl = prefs.getString(SettingsManager.KEY_RELAY_URL, SettingsManager.DEFAULT_RELAY_URL).orEmpty(),
        relayUser = prefs.getString(SettingsManager.KEY_RELAY_USER, SettingsManager.DEFAULT_RELAY_USER).orEmpty(),
        relayPass = prefs.getString(SettingsManager.KEY_RELAY_PASS, "").orEmpty(),
        laptopUser = prefs.getString(SettingsManager.KEY_LAPTOP_USER, SettingsManager.DEFAULT_LAPTOP_USER).orEmpty(),
        laptopPass = prefs.getString(SettingsManager.KEY_LAPTOP_PASS, "").orEmpty(),
    )

    internal fun saveSettingsFromCompose(form: SettingsFormState) {
        prefs.edit()
            .putString(SettingsManager.KEY_VENICE_API_KEY, form.veniceKey.trim())
            .putString(SettingsManager.KEY_VENICE_SYSTEM_PROMPT, form.systemPrompt.trim())
            .putBoolean(SettingsManager.KEY_VENICE_WEB_SEARCH, form.webSearch)
            .putString(SettingsManager.KEY_RELAY_URL, form.relayUrl.trim().trimEnd('/'))
            .putString(SettingsManager.KEY_RELAY_USER, form.relayUser.trim())
            .putString(SettingsManager.KEY_RELAY_PASS, form.relayPass)
            .putString(SettingsManager.KEY_LAPTOP_USER, form.laptopUser.trim())
            .putString(SettingsManager.KEY_LAPTOP_PASS, form.laptopPass)
            .apply()
        updateAgentSetupState()
        UiFeedback.snackbar(snackbarAnchor, "Settings saved")
        hubWebViews.values.forEach { hubContent.removeView(it); it.destroy() }
        hubWebViews.clear()
        authTries.clear()
        terminalWebView?.reload()
        loadHubManifest()
    }

    internal fun ensureHubHostFrame(context: Context): FrameLayout {
        if (hubHostFrame == null) {
            hubContent = FrameLayout(context)
            hubHostFrame = hubContent
        }
        return hubHostFrame!!
    }

    internal fun ensureTerminalHostFrame(context: Context): FrameLayout {
        if (terminalHostFrame == null) {
            terminalHostFrame = FrameLayout(context)
            ensureTerminalWebViewLoaded()
        }
        return terminalHostFrame!!
    }

    private fun ensureTerminalWebViewLoaded() {
        if (terminalWebView != null) return
        val frame = terminalHostFrame ?: FrameLayout(this).also { terminalHostFrame = it }
        val wv = newGenericWebView(Screen("term", "Terminal", "terminal", "/app/term/"))
        terminalWebView = wv
        frame.addView(wv, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        wv.loadUrl(absoluteRelayUrl("/app/term/"))
    }

    internal fun openTerminalPasteSheetPublic() {
        terminalPasteOpen = true
        terminalPasteText = ""
    }

    internal fun removePendingAttachmentAt(index: Int) {
        if (index in pendingAttachments.indices) {
            pendingAttachments.removeAt(index)
            syncPendingAttachmentsUi()
        }
    }

    internal fun dismissHubBanner() {
        hubBannerVisible = false
    }

    internal fun agentHasConversation(): Boolean =
        chatMessages.any {
            it.role == ROLE_USER || it.role == ROLE_VENICE || it.role == ROLE_TOOL_CALL ||
                it.role == ROLE_TOOL_OUTPUT || it.role == ROLE_ERROR
        }

    internal fun onHubBannerClick() {
        hubBannerApkUrl?.let { downloadApk(it) }
            ?: run {
                if (hubBannerText.contains("update", ignoreCase = true)) {
                    UiFeedback.snackbar(snackbarAnchor, "Update link unavailable — open Forge Hub settings or retry sync")
                }
            }
    }

    private fun syncPendingAttachmentsUi() {
        pendingAttachmentsUi.clear()
        pendingAttachmentsUi.addAll(
            pendingAttachments.map { AttachmentUi(it.uri, it.mime, it.name, it.size, it.kind) },
        )
    }

    private fun syncHubScreensUi() {
        hubScreensUi.clear()
        hubScreensUi.addAll(hubScreens.map { HubScreenUi(it.id, it.title) })
        hubCurrentScreenIdState = currentHubScreenId
    }

    private fun refreshYouHomeSummary() {
        scope.launch {
            val snap = withContext(Dispatchers.IO) {
                ConnectionHealth.evaluate(
                    veniceKey = prefs.getString(SettingsManager.KEY_VENICE_API_KEY, "")?.trim().orEmpty(),
                    relayUrl = prefs.getString(SettingsManager.KEY_RELAY_URL, SettingsManager.DEFAULT_RELAY_URL).orEmpty(),
                    relayUser = prefs.getString(SettingsManager.KEY_RELAY_USER, SettingsManager.DEFAULT_RELAY_USER).orEmpty(),
                    relayPass = prefs.getString(SettingsManager.KEY_RELAY_PASS, "").orEmpty(),
                    laptopUser = prefs.getString(SettingsManager.KEY_LAPTOP_USER, SettingsManager.DEFAULT_LAPTOP_USER).orEmpty(),
                    laptopPass = prefs.getString(SettingsManager.KEY_LAPTOP_PASS, "").orEmpty(),
                )
            }
            connectionSummary = snap.multiline()
        }
    }

    internal fun runQuickConnectionTest(updateYouHome: Boolean = false, form: SettingsFormState? = null) {
        connectionSummary = "Testing…"
        scope.launch {
            val snap = withContext(Dispatchers.IO) {
                val f = form ?: readSettingsFormFromPrefs()
                ConnectionHealth.evaluate(
                    veniceKey = f.veniceKey.trim(),
                    relayUrl = f.relayUrl.trim(),
                    relayUser = f.relayUser.trim(),
                    relayPass = f.relayPass,
                    laptopUser = f.laptopUser.trim(),
                    laptopPass = f.laptopPass,
                )
            }
            val text = snap.multiline()
            connectionSummary = text
            if (updateYouHome) connectionSummary = text
        }
    }

    // =========================================================================
    // Tab 1: Native Venice Agent Tab
    // =========================================================================

    // buildAgentTab removed (Compose UI)


    internal fun veniceKeyConfigured(): Boolean =
        !prefs.getString(SettingsManager.KEY_VENICE_API_KEY, "")?.trim().isNullOrEmpty()

    private fun updateAgentSetupState() {
        // Compose Agent screen reads veniceKeyConfigured() directly.
    }

    internal fun showVeniceKeySheet() {
        val current = prefs.getString(SettingsManager.KEY_VENICE_API_KEY, "") ?: ""
        UiSheets.showSecretFieldSheet(
            this,
            title = "Venice API key",
            hint = "Paste your Venice API key",
            initial = current,
        ) { key ->
            prefs.edit().putString(SettingsManager.KEY_VENICE_API_KEY, key).apply()
            updateAgentSetupState()
            UiFeedback.snackbar(snackbarAnchor, "Venice API key saved")
        }
    }

    internal fun confirmClearAgentChat() {
        AlertDialog.Builder(this)
            .setTitle("Clear chat?")
            .setMessage("This removes the on-screen history and saved conversation file.")
            .setPositiveButton("Clear") { _, _ -> resetAgentChat() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    internal fun showModelPicker() {
        val labels = agentModels.map { UiPolish.modelLabel(it) }.toTypedArray()
        UiSheets.showListSheet(this, "Venice model", labels) { which ->
                agentSelectedModel = agentModels[which]
                prefs.edit().putString(SettingsManager.KEY_VENICE_MODEL, agentSelectedModel).apply()
        }
    }

    internal fun showSystemPromptDialog() {
        val currentPrompt = prefs.getString(SettingsManager.KEY_VENICE_SYSTEM_PROMPT, SettingsManager.DEFAULT_SYSTEM_PROMPT)
            ?: SettingsManager.DEFAULT_SYSTEM_PROMPT

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), 0)
        }
        val ed = EditText(this).apply {
            setText(currentPrompt)
            setTextColor(STRONG)
            textSize = 13f
            minLines = 6
            maxLines = 12
            background = createGlassDrawable(CARD_BG, dp(8), LINE)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        box.addView(ed)

        AlertDialog.Builder(this)
            .setTitle("Venice System Prompt")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val newPrompt = ed.text.toString().trim()
                prefs.edit().putString(SettingsManager.KEY_VENICE_SYSTEM_PROMPT, newPrompt).apply()
                Toast.makeText(this, "System prompt updated", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Reset Default") { _, _ ->
                prefs.edit().putString(SettingsManager.KEY_VENICE_SYSTEM_PROMPT, SettingsManager.DEFAULT_SYSTEM_PROMPT).apply()
                Toast.makeText(this, "Reset to default prompt", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun resetAgentChat() {
        currentAgentJob?.cancel()
        agentShowProgress = false
        runCatching { agentHistoryFile().delete() }
        chatMessages.clear()
        agentStatusSubtitle = "On-device agent"
        addMessageBubble("Agent Reset", "Chat context cleared. Ready for next task.", ROLE_VENICE)
    }

    private fun agentHistoryFile() = File(filesDir, "agent_history.json")

    private val historyJson = Json { ignoreUnknownKeys = true }

    /** Persists the conversation (image blobs stripped) so it survives app restarts. */
    private fun persistAgentHistory(messages: List<ChatMessage>) {
        runCatching {
            val lean = messages
                .filter { it.role in setOf("user", "assistant", "tool") }
                .takeLast(Agent.MAX_HISTORY_MESSAGES)
                .map { it.copy(content = stripBlobs(it.content)) }
            agentHistoryFile().writeText(
                historyJson.encodeToString(ListSerializer(ChatMessage.serializer()), lean),
            )
        }
    }

    private fun loadAgentHistory(): List<ChatMessage> {
        return runCatching {
            val file = agentHistoryFile()
            if (!file.isFile) return emptyList()
            historyJson.decodeFromString(ListSerializer(ChatMessage.serializer()), file.readText())
                .filter { it.role in setOf("user", "assistant", "tool") }
                .takeLast(Agent.MAX_HISTORY_MESSAGES)
        }.getOrDefault(emptyList())
    }

    /** Rebuilds chat bubbles from a restored conversation. */
    private fun renderHistoryBubbles(history: List<ChatMessage>) {
        for (msg in history) {
            when (msg.role) {
                "user" -> addMessageBubble("You", msg.textContent(), ROLE_USER)
                "assistant" -> {
                    val calls = msg.toolCalls
                    if (calls.isNullOrEmpty()) {
                        addMessageBubble("Venice", msg.textContent(), ROLE_VENICE)
                    } else {
                        calls.forEach { call ->
                            addMessageBubble("Tool Call: ${call.function.name}", call.function.arguments, ROLE_TOOL_CALL)
                        }
                    }
                }
                "tool" -> addMessageBubble("Output: ${msg.name ?: "tool"}", msg.textContent(), ROLE_TOOL_OUTPUT)
            }
        }
    }

    internal fun sendAgentMessage() {
        val text = agentInputText.trim()
        if (text.isEmpty() && pendingAttachments.isEmpty()) return
        val apiKey = prefs.getString(SettingsManager.KEY_VENICE_API_KEY, "")?.trim() ?: ""
        if (apiKey.isEmpty()) {
            updateAgentSetupState()
            UiFeedback.snackbar(snackbarAnchor, "Add a Venice API key to send messages", "Add key") { showVeniceKeySheet() }
            return
        }

        agentInputText = ""
        val attachmentSnapshot = pendingAttachments.toList()
        pendingAttachments.clear()
        syncPendingAttachmentsUi()
        val selectedModel = agentSelectedModel
        val savedPrompt = prefs.getString(SettingsManager.KEY_VENICE_SYSTEM_PROMPT, SettingsManager.DEFAULT_SYSTEM_PROMPT)
            ?: SettingsManager.DEFAULT_SYSTEM_PROMPT
        val sysPrompt = buildString {
            append(savedPrompt.trim())
            if (!savedPrompt.contains("review_latest_render")) {
                append("\n\n")
                append(SettingsManager.AGENT_INTEGRATION_PROMPT)
            }
        }
        val webSearch = prefs.getBoolean(SettingsManager.KEY_VENICE_WEB_SEARCH, false)

        val workspaceDir = File(filesDir, "workspace")
        val relayUser = prefs.getString(SettingsManager.KEY_RELAY_USER, SettingsManager.DEFAULT_RELAY_USER) ?: SettingsManager.DEFAULT_RELAY_USER
        val relayPass = prefs.getString(SettingsManager.KEY_RELAY_PASS, "") ?: ""

        val toolBox = ToolBox(
            workspaceDir = workspaceDir,
            relayBaseUrl = relayBase,
            relayUser = relayUser,
            relayPass = relayPass,
        )

        agentShowProgress = true
        agentThinking = true
        setAgentComposerEnabled(false)

        val currentClient = VeniceClient(apiKey = apiKey)
        currentAgentJob = scope.launch {
            var finishedAgent: Agent? = null
            try {
                // Heavy lifting (image downscale, file copies) off the main thread.
                val (fullText, imageDataUrls) = withContext(Dispatchers.IO) {
                    buildMessageContent(text, attachmentSnapshot)
                }
                if (fullText.isBlank() && imageDataUrls.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        agentThinking = false
                        agentShowProgress = false
                        setAgentComposerEnabled(true)
                        Toast.makeText(this@MainActivity, "Nothing to send", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }
                val agent = Agent(
                    client = currentClient,
                    model = selectedModel,
                    toolBox = toolBox,
                    systemPrompt = sysPrompt,
                    enableWebSearch = webSearch,
                    onEvent = { event ->
                        main.post {
                            when (event) {
                                is AgentEvent.User -> addMessageBubble("You", event.text, ROLE_USER)
                                is AgentEvent.Venice -> addMessageBubble("Venice", event.text, ROLE_VENICE)
                                is AgentEvent.ToolCall -> addMessageBubble("Tool Call: ${event.name}", event.arguments, ROLE_TOOL_CALL)
                                is AgentEvent.ToolOutput -> addMessageBubble("Output: ${event.name}", event.output, ROLE_TOOL_OUTPUT)
                                is AgentEvent.Error -> addMessageBubble("Error", event.message, ROLE_ERROR)
                            }
                        }
                    }
                )

                withContext(Dispatchers.IO) {
                    currentClient.use {
                        agent.restore(loadAgentHistory())
                        finishedAgent = agent
                        agent.send(fullText, imageDataUrls)
                    }
                }
            } catch (e: Exception) {
                addMessageBubble("Error", e.message ?: "Unknown error running Venice agent", ROLE_ERROR)
            } finally {
                finishedAgent?.let { persistAgentHistory(it.snapshot()) }
                agentShowProgress = false
                agentThinking = false
                setAgentComposerEnabled(true)
            }
        }
    }

    internal fun toggleToolMessage(id: Long) {
        val idx = chatMessages.indexOfFirst { it.id == id }
        if (idx < 0) return
        val cur = chatMessages[idx]
        chatMessages[idx] = cur.copy(toolExpanded = !cur.toolExpanded)
    }

    private fun setAgentComposerEnabled(enabled: Boolean) {
        agentComposerEnabled = enabled
    }

    // =========================================================================
    // Device integration: attachments, clipboard, share receive, permissions
    // =========================================================================

    internal fun showAttachSheet() {
        val items = arrayOf("Take photo", "Record video", "Choose images", "Choose video", "Choose file")
        UiSheets.showListSheet(this, "Attach to message", items) { which ->
            when (which) {
                0 -> withCapturePermission(video = false) { launchCamera(photo = true) }
                1 -> withCapturePermission(video = true) { launchCamera(photo = false) }
                2 -> pickImagesLauncher.launch("image/*")
                3 -> pickVideosLauncher.launch("video/*")
                4 -> pickFilesLauncher.launch(arrayOf("*/*"))
            }
        }
    }

    private fun withCapturePermission(video: Boolean, action: () -> Unit) {
        val need = mutableListOf(android.Manifest.permission.CAMERA)
        if (video) need += android.Manifest.permission.RECORD_AUDIO
        val missing = need.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()
        if (missing.isEmpty()) {
            action()
        } else {
            pendingPermissionAction = action
            permissionLauncher.launch(missing)
        }
    }

    private fun launchCamera(photo: Boolean) {
        val ext = if (photo) "jpg" else "mp4"
        val dir = File(cacheDir, "capture").apply { mkdirs() }
        val file = File(dir, "capture_${System.currentTimeMillis()}.$ext")
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        pendingCameraUri = uri
        if (photo) takePictureLauncher.launch(uri) else captureVideoLauncher.launch(uri)
    }

    private fun addContentAttachment(uri: Uri) {
        try {
            val mime = contentResolver.getType(uri) ?: "application/octet-stream"
            val kind = Attachments.kindOf(mime)
            var name: String? = null
            var size = -1L
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIdx >= 0) name = c.getString(nameIdx)
                    if (sizeIdx >= 0) size = c.getLong(sizeIdx)
                }
            }
            if (kind == Attachments.Kind.IMAGE &&
                pendingAttachments.count { it.kind == Attachments.Kind.IMAGE } >= Attachments.MAX_IMAGES_PER_MESSAGE
            ) {
                Toast.makeText(this, "Max ${Attachments.MAX_IMAGES_PER_MESSAGE} images per message", Toast.LENGTH_SHORT).show()
                return
            }
            pendingAttachments += PendingAttachment(
                uri = uri,
                mime = mime,
                name = Attachments.safeFileName(name, "attachment.${Attachments.extensionFor(mime)}"),
                size = size,
                kind = kind,
            )
            Toast.makeText(this, "Attached $name", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't attach file: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun renderAttachmentStrip() = syncPendingAttachmentsUi()

    /** Builds the outgoing message: images become multimodal data URLs, other files land in the workspace. */
    private fun buildMessageContent(baseText: String, attachments: List<PendingAttachment>): Pair<String, List<String>> {
        if (attachments.isEmpty()) return baseText to emptyList()
        val dataUrls = mutableListOf<String>()
        val notes = mutableListOf<String>()
        val uploadsDir = File(filesDir, "workspace/uploads").apply { mkdirs() }
        for (attachment in attachments) {
            try {
                if (attachment.kind == Attachments.Kind.IMAGE && dataUrls.size < Attachments.MAX_IMAGES_PER_MESSAGE) {
                    val bytes = loadScaledJpeg(attachment.uri)
                    if (bytes == null) {
                        notes += "[Image ${attachment.name} could not be read, skipped]"
                    } else if (bytes.size > Attachments.MAX_IMAGE_BASE64_BYTES) {
                        notes += "[Image ${attachment.name} too large, skipped]"
                    } else {
                        dataUrls += "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
                        val dest = uniqueFile(uploadsDir, attachment.name.substringBeforeLast('.') + ".jpg")
                        dest.writeBytes(bytes)
                        notes += "[Image saved to workspace uploads/${dest.name}]"
                    }
                } else if (attachment.kind == Attachments.Kind.IMAGE) {
                    notes += "[Image ${attachment.name} skipped: max ${Attachments.MAX_IMAGES_PER_MESSAGE} images per message]"
                } else {
                    val dest = copyToUploads(attachment, uploadsDir)
                    if (dest != null) {
                        notes += Attachments.workspaceNote("uploads/${dest.name}", attachment.mime, dest.length())
                    } else {
                        notes += "[File ${attachment.name} too large (>200 MB), skipped]"
                    }
                }
            } catch (e: Exception) {
                notes += "[Attachment ${attachment.name} failed: ${e.message}]"
            }
        }
        val fullText = (listOf(baseText.trim()).filter { it.isNotEmpty() } + notes).joinToString("\n")
        return fullText to dataUrls
    }

    private fun uniqueFile(dir: File, name: String): File {
        var candidate = File(dir, Attachments.safeFileName(name, "file.bin"))
        var n = 1
        val stem = candidate.nameWithoutExtension
        val ext = candidate.extension.let { if (it.isEmpty()) "" else ".$it" }
        while (candidate.exists()) {
            candidate = File(dir, "$stem-$n$ext")
            n++
        }
        return candidate
    }

    private fun loadScaledJpeg(uri: Uri): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val maxSide = maxOf(bounds.outWidth, bounds.outHeight)
        if (maxSide <= 0) return null
        var sample = 1
        while (maxSide / sample > Attachments.MAX_IMAGE_DIMENSION_PX * 2) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: return null
        val scale = Attachments.MAX_IMAGE_DIMENSION_PX / maxOf(decoded.width, decoded.height).toFloat()
        val bitmap = if (scale < 1f) {
            Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt(), (decoded.height * scale).toInt(), true)
                .also { if (it != decoded) decoded.recycle() }
        } else {
            decoded
        }
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, Attachments.IMAGE_JPEG_QUALITY, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    private fun copyToUploads(attachment: PendingAttachment, uploadsDir: File): File? {
        if (attachment.size > Attachments.MAX_FILE_COPY_BYTES) return null
        val dest = uniqueFile(uploadsDir, attachment.name)
        var copied = 0L
        contentResolver.openInputStream(attachment.uri)?.use { input ->
            dest.outputStream().use { output ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    copied += n
                    if (copied > Attachments.MAX_FILE_COPY_BYTES) {
                        dest.delete()
                        return null
                    }
                    output.write(buf, 0, n)
                }
            }
        } ?: return null
        return dest
    }

    internal fun copyToClipboard(text: String) {
        if (text.isBlank()) {
            Toast.makeText(this, "Nothing to copy", Toast.LENGTH_SHORT).show()
            return
        }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("WhiteDevil", text))
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }

    internal fun pasteFromClipboard() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip
        if (clip == null || clip.itemCount == 0) {
            Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
            return
        }
        val item = clip.getItemAt(0)
        val uri = item.uri
        if (uri != null) {
            val type = runCatching { contentResolver.getType(uri) }.getOrNull()
            if (type?.startsWith("image/") == true) {
                addContentAttachment(uri)
                renderAttachmentStrip()
                Toast.makeText(this, "Image pasted as attachment", Toast.LENGTH_SHORT).show()
                return
            }
        }
        val text = runCatching { item.coerceToText(this)?.toString() }.getOrNull()
        if (text.isNullOrEmpty()) {
            Toast.makeText(this, "Nothing pastable on the clipboard", Toast.LENGTH_SHORT).show()
            return
        }
        agentInputText = agentInputText + text
    }

    @Suppress("DEPRECATION")
    private fun handleSharedIntent(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            Intent.ACTION_SEND -> {
                var added = false
                intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }?.let { shared ->
                    agentInputText = if (agentInputText.isEmpty()) shared else "${agentInputText}\n$shared"
                    added = true
                }
                intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { uri ->
                    copySharedToCache(uri)?.let { addContentAttachment(it); added = true }
                }
                if (added) {
                    selectTab(Tab.AGENT)
                    renderAttachmentStrip()
                    Toast.makeText(this, "Shared content added to Agent", Toast.LENGTH_SHORT).show()
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                var added = false
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.forEach { uri ->
                    copySharedToCache(uri)?.let { addContentAttachment(it); added = true }
                }
                if (added) {
                    selectTab(Tab.AGENT)
                    renderAttachmentStrip()
                    Toast.makeText(this, "Shared files added to Agent", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** Copies a shared content URI into app storage so it survives the granting app going away. */
    private fun copySharedToCache(uri: Uri): Uri? {
        return try {
            val mime = contentResolver.getType(uri) ?: "application/octet-stream"
            var name: String? = null
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) name = c.getString(idx)
                }
            }
            val dir = File(cacheDir, "shared").apply { mkdirs() }
            val dest = uniqueFile(dir, Attachments.safeFileName(name, "shared.${Attachments.extensionFor(mime)}"))
            var copied = 0L
            contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        copied += n
                        if (copied > Attachments.MAX_FILE_COPY_BYTES) {
                            dest.delete()
                            return null
                        }
                        output.write(buf, 0, n)
                    }
                }
            } ?: return null
            Uri.fromFile(dest)
        } catch (_: Exception) {
            null
        }
    }

    private fun addMessageBubble(sender: String, message: String, role: Int) {
        chatMessages.add(
            ChatUiMessage(
                id = nextChatId++,
                sender = sender,
                message = message,
                role = role,
                toolExpanded = false,
            ),
        )
        chatScrollTrigger++
    }

    // =========================================================================
    // Tab 2: Isolated Forge Hub Component
    // =========================================================================

    // buildForgeHubTab removed (Compose UI)


    private fun renderHubChips() = syncHubScreensUi()

    internal fun reloadCurrentHubScreen() {
        val id = currentHubScreenId
        if (id.isNullOrEmpty()) {
            loadHubManifest()
            return
        }
        hubWebViews[id]?.reload()
        UiFeedback.snackbar(snackbarAnchor, "Reloading ${hubScreens.firstOrNull { it.id == id }?.title ?: "screen"}")
    }

    private var hubBannerApkUrl: String? = null

    private fun updateHubConnectionPill(online: Boolean, detail: String? = null) {
        hubConnectionLabel = when {
            online && detail == null -> "Online"
            detail != null -> detail
            online -> "Online"
            else -> "Offline"
        }
    }

    internal fun showHubScreen(id: String) {
        if (hubBlockedByUpdate) return
        val s = hubScreens.firstOrNull { it.id == id } ?: return
        if (id == currentHubScreenId && hubWebViews[id]?.visibility == View.VISIBLE) {
            hubWebViews[id]?.reload()
            return
        }
        currentHubScreenId = id
        prefs.edit().putString("last_hub_screen", id).apply()

        val wv = hubWebViews.getOrPut(id) {
            newGenericWebView(s).also {
                it.loadUrl(absoluteRelayUrl(s.url))
                hubContent.addView(it, 0)
            }
        }

        hubWebViews.values.forEach { if (it !== wv) it.visibility = View.GONE }
        wv.alpha = 0f
        wv.visibility = View.VISIBLE
        wv.animate().alpha(1f).setDuration(160).start()
        renderHubChips()
    }

    private fun loadHubManifest() {
        prefs.getString("manifest", null)?.let { applyHubManifest(it, fromCache = true) }
        thread {
            try {
                val conn = URL("$relayBase/api/manifest").openConnection() as HttpURLConnection
                conn.setRequestProperty("Authorization", basicAuth("wan"))
                conn.connectTimeout = 12000
                conn.readTimeout = 18000
                val code = conn.responseCode
                if (code == 401) {
                    main.post {
                        hubBannerText = "Relay authentication rejected. Check password in Settings."
                        hubBannerVisible = true
                    }
                    return@thread
                }
                val body = conn.inputStream.bufferedReader().readText()
                prefs.edit().putString("manifest", body).apply()
                main.post {
                    applyHubManifest(body, fromCache = false)
                    updateHubConnectionPill(online = true)
                }
            } catch (e: Exception) {
                main.post {
                    updateHubConnectionPill(online = false, detail = "Offline")
                    if (hubScreens.isEmpty()) showHubOffline(e.message)
                    else {
                        hubBannerText = "Relay offline - showing cached screens"
                        hubBannerVisible = true
                    }
                }
            }
        }
    }

    private fun applyHubManifest(body: String, fromCache: Boolean) {
        val json = JSONObject(body)
        val arr = json.getJSONArray("screens")
        val next = (0 until arr.length()).map { arr.getJSONObject(it) }.map {
            Screen(it.getString("id"), it.getString("title"), it.optString("icon"), it.getString("url"))
        }.filter { it.id != "term" && it.id != "venice" } // term and venice have dedicated native tabs!

        val rev = json.optInt("web_rev", json.optInt("apk_version", 0))
        val changedUrls = next.filter { n -> hubScreens.any { it.id == n.id && it.url != n.url } }.map { it.id }
        val drop = (hubWebViews.keys - next.map { it.id }.toSet() + changedUrls).toMutableSet()
        if (!fromCache && hubWebRev != 0 && rev != hubWebRev) drop.addAll(hubWebViews.keys)
        drop.forEach { id ->
            hubWebViews.remove(id)?.let { hubContent.removeView(it); it.destroy() }
        }
        if (!fromCache) hubWebRev = rev
        hubScreens = next

        if (!fromCache) checkHubUpdate(json)

        val want = currentHubScreenId ?: prefs.getString("last_hub_screen", null)
        if (!hubBlockedByUpdate) {
            showHubScreen(hubScreens.firstOrNull { it.id == want }?.id ?: hubScreens.firstOrNull()?.id ?: "home")
        }
        updateHubConnectionPill(online = !fromCache || hubScreens.isNotEmpty(), detail = if (fromCache) "Cached" else null)
    }

    private fun showHubOffline(msg: String?) {
        updateHubConnectionPill(online = false, detail = "Offline")
        hubContent.removeAllViews()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(32), dp(32), dp(32))
            addView(TextView(context).apply {
                text = "Forge Hub Relay Offline"
                textSize = 20f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(STRONG)
                gravity = Gravity.CENTER
            })
            addView(TextView(context).apply {
                text = "$relayBase\n${msg ?: "Could not reach relay"}\n\nNote: Venice Agent and local Terminal operate independently and are unaffected."
                textSize = 13f
                setTextColor(MUTED)
                gravity = Gravity.CENTER
                setPadding(0, dp(10), 0, dp(24))
            })
            addView(TextView(context).apply {
                text = "Retry Connection"
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#FF111111"))
                setPadding(dp(24), dp(12), dp(24), dp(12))
                background = GradientDrawable().apply {
                    setColor(ACCENT)
                    cornerRadius = dp(24).toFloat()
                }
                setOnClickListener { loadHubManifest() }
            })
        }
        hubContent.addView(box, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    private fun checkHubUpdate(json: JSONObject) {
        val latest = json.optInt("apk_version", 0)
        val force = json.optBoolean("force_update", false)
        if (latest <= BuildConfig.VERSION_CODE) {
            hubBlockedByUpdate = false
            hubBannerVisible = false
            return
        }
        val apk = json.optString("apk_url", "/app/forgehub.apk")
        if (force) {
            hubBlockedByUpdate = true
            hubWebViews.values.forEach { it.visibility = View.GONE }
            hubBannerVisible = false
            hubContent.removeAllViews()
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(32), dp(32), dp(32), dp(32))
                addView(TextView(context).apply {
                    text = "Forge Hub Update Required"
                    textSize = 22f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(STRONG)
                    gravity = Gravity.CENTER
                })
                addView(TextView(context).apply {
                    text = "Forge Hub requires v$latest (current: v${BuildConfig.VERSION_CODE}). Only this Hub tab is restricted until updated. Agent and Terminal remain usable."
                    textSize = 13f
                    setTextColor(MUTED)
                    gravity = Gravity.CENTER
                    setPadding(0, dp(12), 0, dp(24))
                })
                addView(TextView(context).apply {
                    text = "Download v$latest"
                    textSize = 14f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.parseColor("#FF111111"))
                    setPadding(dp(28), dp(12), dp(28), dp(12))
                    background = GradientDrawable().apply {
                        setColor(ACCENT)
                        cornerRadius = dp(24).toFloat()
                    }
                    setOnClickListener { downloadApk(absoluteRelayUrl(apk)) }
                })
                addView(TextView(context).apply {
                    text = "Dismiss / Ignore this update"
                    textSize = 12f
                    setTextColor(MUTED)
                    setPadding(dp(16), dp(16), dp(16), dp(8))
                    isClickable = true
                    setOnClickListener {
                        hubBlockedByUpdate = false
                        hubContent.removeAllViews()
                        renderHubChips()
                        showHubScreen(hubScreens.firstOrNull()?.id ?: "home")
                    }
                })
            }
            hubContent.addView(box, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            return
        }
        hubBlockedByUpdate = false
        hubBannerText = "Forge Hub update v$latest available. Tap to install."
        hubBannerVisible = true
        hubBannerApkUrl = absoluteRelayUrl(apk)
    }

    internal fun sendPasteToTerminal() {
        var text = terminalPasteText
        if (text.isNotEmpty()) {
            if (!text.endsWith("\n")) text += "\n"
            val escaped = JSONObject.quote(text)
            val js = """
                (function(){
                    if (window.ForgeTerm && window.ForgeTerm.install) {
                        try { window.ForgeTerm.install(window); } catch(e){}
                    }
                    if (window.ForgeTermPaste) { window.ForgeTermPaste($escaped); return; }
                    var f = document.querySelector('iframe');
                    if (f && f.contentWindow) {
                        if (window.ForgeTerm && window.ForgeTerm.install) {
                            try { window.ForgeTerm.install(f.contentWindow); } catch(e){}
                        }
                        if (f.contentWindow.ForgeTermPaste) {
                            f.contentWindow.ForgeTermPaste($escaped);
                            return;
                        }
                    }
                })();
            """.trimIndent()
            terminalWebView?.evaluateJavascript(js, null)
        }
        terminalPasteOpen = false
    }

    internal fun scrollTerminal(where: String) {
        val js = """
            (function(){
                if (window.ForgeTermScroll) { window.ForgeTermScroll('$where'); return; }
                var f = document.querySelector('iframe');
                if (f && f.contentWindow && f.contentWindow.ForgeTermScroll) {
                    f.contentWindow.ForgeTermScroll('$where');
                }
            })();
        """.trimIndent()
        terminalWebView?.evaluateJavascript(js, null)
    }

    // =========================================================================
    // Tab 4: Native Settings Component
    // =========================================================================

    // buildSettingsTab removed (Compose UI)


    // =========================================================================
    // Generic WebView Factory for Forge Hub & Terminal
    // =========================================================================

    private fun newGenericWebView(screen: Screen): WebView = WebView(this).apply {
        setBackgroundColor(BG)
        val term = screen.id == "term" || screen.url.contains("/term")
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.cacheMode = WebSettings.LOAD_NO_CACHE
        settings.mediaPlaybackRequiresUserGesture = false
        settings.allowFileAccess = false
        settings.builtInZoomControls = !term
        settings.displayZoomControls = false
        settings.loadWithOverviewMode = !term
        settings.useWideViewPort = !term
        settings.setSupportZoom(!term)
        overScrollMode = if (term) View.OVER_SCROLL_ALWAYS else View.OVER_SCROLL_IF_CONTENT_SCROLLS
        isNestedScrollingEnabled = term
        if (term) {
            isLongClickable = false
            setOnLongClickListener { true }
        }
        settings.userAgentString = settings.userAgentString + " WhiteDevil/${BuildConfig.VERSION_CODE}"
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                val u = req.url
                if (u.host == Uri.parse(relayBase).host) return false
                if (u.scheme == "whitedevil" || u.scheme == "forgehub") {
                    u.getQueryParameter("screen")?.let { showHubScreen(it) }
                    return true
                }
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, u)) }
                return true
            }

            override fun onReceivedHttpAuthRequest(view: WebView, handler: HttpAuthHandler, host: String, realm: String) {
                val key = if (realm == "laptop") "laptop" else "wan"
                val n = (authTries[key] ?: 0) + 1
                authTries[key] = n
                if (n > 3) {
                    handler.cancel()
                    authTries[key] = 0
                    Toast.makeText(this@MainActivity, "${if (key == "laptop") "Laptop" else "Relay"} login rejected. Check Settings.", Toast.LENGTH_SHORT).show()
                    return
                }
                val u = if (key == "laptop") prefs.getString(SettingsManager.KEY_LAPTOP_USER, SettingsManager.DEFAULT_LAPTOP_USER)!!
                else prefs.getString(SettingsManager.KEY_RELAY_USER, SettingsManager.DEFAULT_RELAY_USER)!!
                val p = if (key == "laptop") prefs.getString(SettingsManager.KEY_LAPTOP_PASS, "")!!
                else prefs.getString(SettingsManager.KEY_RELAY_PASS, "")!!
                handler.proceed(u, p)
            }

            override fun onPageFinished(view: WebView, url: String) {
                authTries.clear()
                if (url.contains("/term") || url.contains("/laptop/term")) {
                    view.evaluateJavascript(TERM_PATCH, null)
                }
            }
        }

        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, p: Int) {
                val progress = p / 100f
                main.post {
                    if (term) {
                        terminalLoadProgress = if (p < 100) progress else -1f
                    } else {
                        hubLoadProgress = if (p < 100) progress else -1f
                    }
                }
            }

            override fun onShowFileChooser(w: WebView, cb: ValueCallback<Array<Uri>>, p: FileChooserParams): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = cb
                return try {
                    startActivityForResult(p.createIntent().putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true), REQ_FILE)
                    true
                } catch (_: Exception) {
                    fileCallback = null
                    false
                }
            }

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                fullscreenView = view
                (window.decorView as FrameLayout).addView(view, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            }

            override fun onHideCustomView() {
                fullscreenView?.let { (window.decorView as FrameLayout).removeView(it) }
                fullscreenView = null
            }
        }

        setDownloadListener { url, _, disposition, mime, _ ->
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                Toast.makeText(this@MainActivity, "Can't download this link in the app", Toast.LENGTH_SHORT).show()
                return@setDownloadListener
            }
            val name = URLUtil.guessFileName(url, disposition, mime)
            val realm = if (Uri.parse(url).path?.startsWith("/laptop") == true) "laptop" else "wan"
            val req = DownloadManager.Request(Uri.parse(url))
                .addRequestHeader("Authorization", basicAuth(realm))
                .addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url) ?: "")
                .setTitle(name)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "WhiteDevil/$name")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
            Toast.makeText(this@MainActivity, "Downloading $name", Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ_FILE) {
            val uris = data?.clipData?.let { c -> Array(c.itemCount) { c.getItemAt(it).uri } }
                ?: WebChromeClient.FileChooserParams.parseResult(resultCode, data)
            fileCallback?.onReceiveValue(if (resultCode == RESULT_OK) uris else null)
            fileCallback = null
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (fullscreenView != null) {
            hubWebViews[currentHubScreenId]?.webChromeClient?.onHideCustomView()
            terminalWebView?.webChromeClient?.onHideCustomView()
            return
        }
        if (activeTab == Tab.FORGE_HUB) {
            val wv = hubWebViews[currentHubScreenId]
            if (wv != null && wv.canGoBack()) {
                wv.goBack()
                return
            }
        }
        if (activeTab == Tab.YOU && youSubScreen != YouSub.HOME) {
            showYouSub(YouSub.HOME)
            return
        }
        if (activeTab != Tab.AGENT) {
            selectTab(Tab.AGENT)
            return
        }
        finish()
    }

    private fun downloadApk(url: String) {
        val req = DownloadManager.Request(Uri.parse(url))
            .addRequestHeader("Authorization", basicAuth("wan"))
            .setTitle("WhiteDevil update")
            .setMimeType("application/vnd.android.package-archive")
            .setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, "whitedevil-${System.currentTimeMillis()}.apk")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        apkDownloadId = (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
        Toast.makeText(this, "Downloading update…", Toast.LENGTH_SHORT).show()
    }

    private val downloadDone = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val id = i.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            if (id != apkDownloadId) return
            val uri = (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).getUriForDownloadedFile(id) ?: return
            startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    // =========================================================================
    // Helpers & Styling
    // =========================================================================

    private fun basicAuth(realm: String): String {
        val u = if (realm == "laptop") prefs.getString(SettingsManager.KEY_LAPTOP_USER, SettingsManager.DEFAULT_LAPTOP_USER)
        else prefs.getString(SettingsManager.KEY_RELAY_USER, SettingsManager.DEFAULT_RELAY_USER)
        val p = if (realm == "laptop") prefs.getString(SettingsManager.KEY_LAPTOP_PASS, "")
        else prefs.getString(SettingsManager.KEY_RELAY_PASS, "")
        return "Basic " + Base64.encodeToString("$u:$p".toByteArray(), Base64.NO_WRAP)
    }

    private fun absoluteRelayUrl(url: String) = if (url.startsWith("http")) url else relayBase + url

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun createGlassDrawable(fillColor: Int, cornerRadiusDp: Int = 0, border: Int? = null): GradientDrawable {
        return GradientDrawable().apply {
            setColor(fillColor)
            if (cornerRadiusDp > 0) cornerRadius = dp(cornerRadiusDp).toFloat()
            if (border != null) setStroke(1, border)
        }
    }

    companion object {
        const val REQ_FILE = 41

        const val ROLE_USER = 1
        const val ROLE_VENICE = 2
        const val ROLE_TOOL_CALL = 3
        const val ROLE_TOOL_OUTPUT = 4
        const val ROLE_ERROR = 5
        const val ROLE_INFO = 6

        val BG = Color.parseColor("#FF0B0B0C")
        val BAR_GLASS = Color.parseColor("#D9121216")
        val CARD_BG = Color.parseColor("#331A1A1E")
        val LINE = Color.parseColor("#1FFFFFFF")
        val FG = Color.parseColor("#FFEDEDEA")
        val MUTED = Color.parseColor("#FF8C8C93")
        val ACCENT = Color.parseColor("#FFCDB88F")
        val STRONG = Color.parseColor("#FFF4F1EA")
        val PILL = Color.parseColor("#26FFFFFF")

        const val TERM_PATCH = """
            (function(){
              if (window.__forgeTermLoader) return;
              window.__forgeTermLoader = 1;
              function go(){ if (window.ForgeTerm) { ForgeTerm.install(window); try { var f=document.querySelector('iframe'); if(f&&f.contentWindow) ForgeTerm.install(f.contentWindow); } catch(e) {} } }
              var s=document.createElement('script');
              s.src='/app/term/patch.js';
              s.onload=go;
              document.documentElement.appendChild(s);
              setTimeout(go, 400);
              setTimeout(go, 1200);
            })();
        """
    }
}
