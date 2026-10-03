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
import android.provider.Settings
import android.text.InputType
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
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
import androidx.fragment.app.FragmentActivity
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
import com.whitedevil.relay.RelayHttp
import com.whitedevil.relay.RelayHttpException
import com.whitedevil.ui.chat.ChatUiMessage
import com.whitedevil.agent.AccessConfig
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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class MainActivity : FragmentActivity() {

    enum class Tab { AGENT, FORGE_HUB, YOU }

    enum class YouSub { HOME, TERMINAL, SETTINGS, FILES, CHATS, MEMORY, SKILLS, CONNECTORS }

    private data class Screen(val id: String, val title: String, val icon: String, val url: String)

    /** Conversations, memory, skills and MCP servers (see [AgentWorkspace]). */
    internal val workspace by lazy {
        AgentWorkspace(filesDir).also { ws ->
            // Bundled starter skills (assets/skills/*.md): written once each, never over your edits.
            runCatching {
                val names = assets.list("skills").orEmpty().filter { it.endsWith(".md") }
                ws.skills.seedDefaults(names.associate { it.removeSuffix(".md") to assets.open("skills/$it").bufferedReader().use { r -> r.readText() } })
            }
        }
    }

    private val prefs by lazy { SettingsManager.getPrefs(this) }
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    internal var uiTab by mutableStateOf(Tab.AGENT)
    internal var uiYouSub by mutableStateOf(YouSub.HOME)
    internal var showOnboarding by mutableStateOf(false)
    /** False until biometric (or fallback) unlocks the session when biometric unlock is enabled. */
    internal var appUnlocked by mutableStateOf(false)
    private var lastBackgroundAt = 0L

    /** Device-bound auth status for Settings. Holds no token and no key material. */
    internal var deviceAuthState by mutableStateOf(com.whitedevil.security.DeviceAuth.State())
    internal var deviceAuthBusy by mutableStateOf(false)
    internal var deviceAuthMessage by mutableStateOf("")

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
    internal var terminalPasteOpen by mutableStateOf(false)
    internal var terminalPasteText by mutableStateOf("")
    internal var hubScreenJson by mutableStateOf("")
    internal var hubScreenLoading by mutableStateOf(false)
    internal var hubScreenError by mutableStateOf<String?>(null)
    internal var hubBlockedByUpdate by mutableStateOf(false)
    internal var hubForceUpdateVersion by mutableIntStateOf(0)
    internal var hubForceUpdateApkUrl: String? = null
    internal val terminalLog = mutableStateListOf<String>()
    internal var terminalRunning by mutableStateOf(false)

    private lateinit var snackbarAnchor: View

    // Forge Hub (native Compose — relay JSON APIs)
    private var hubScreens: List<Screen> = emptyList()
    private var currentHubScreenId: String? = null
    private var hubWebRev = 0

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
        "venice-uncensored-role-play",
        "gemma-4-uncensored",
        "qwen-3-6-plus",
        "olafangensan-glm-4.7-flash-heretic",
        "abliteration-abliterated-model-large-v2",
        "e2ee-gemma-4-26b-a4b-uncensored-p",
        "qwen3-vl-235b-a22b",
        "mistral-31-24b",
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

    private val pickPhoneFolderLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) persistPhoneFolder(uri)
        }

    private val pickPhoneMediaLauncher =
        registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
            uris.forEach { addContentAttachment(it) }
            syncPendingAttachmentsUi()
            if (uris.isNotEmpty()) {
                selectTab(Tab.AGENT)
                Toast.makeText(this, "Added ${uris.size} item(s) to Agent", Toast.LENGTH_SHORT).show()
            }
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

    private var webFileCallback: android.webkit.ValueCallback<Array<Uri>>? = null
    private val webFileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val uris = result.data?.clipData?.let { c -> Array(c.itemCount) { c.getItemAt(it).uri } }
                ?: android.webkit.WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            webFileCallback?.onReceiveValue(if (result.resultCode == RESULT_OK) uris else null)
            webFileCallback = null
        }

    /** Used by HubRelayWebBody (LTX planner image picker). */
    fun openWebFileChooser(
        cb: android.webkit.ValueCallback<Array<Uri>>?,
        params: android.webkit.WebChromeClient.FileChooserParams?,
    ) {
        webFileCallback?.onReceiveValue(null)
        webFileCallback = cb
        try {
            val intent = params?.createIntent()?.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "image/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                }
            webFileChooserLauncher.launch(intent)
        } catch (_: Exception) {
            webFileCallback = null
            cb?.onReceiveValue(null)
        }
    }

    // Current navigation state (mirrors uiTab for legacy call sites)
    private val activeTab: Tab get() = uiTab
    private val youSubScreen: YouSub get() = uiYouSub
    private var apkDownloadId = -1L

    private val relayBase get() = prefs.getString(SettingsManager.KEY_RELAY_URL, SettingsManager.DEFAULT_RELAY_URL)!!.trimEnd('/')

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Resolve lock state before first Compose frame to avoid a content flash.
        if (!prefs.getBoolean(SettingsManager.KEY_BIOMETRIC_UNLOCK, false) ||
            !com.whitedevil.security.BiometricAuth.available(this)
        ) {
            appUnlocked = true
        }
        buildUi()
        refreshDeviceAuthState()

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
        evaluateAppLock(forcePrompt = true)

        registerReceiver(downloadDone, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), RECEIVER_EXPORTED)
    }

    override fun onResume() {
        super.onResume()
        updateAgentSetupState()
        evaluateAppLock(forcePrompt = false)
        if (activeTab == Tab.FORGE_HUB && !hubBlockedByUpdate && currentHubScreenId != null && appUnlocked) {
            refreshHubNativeScreen()
        }
    }

    override fun onPause() {
        lastBackgroundAt = System.currentTimeMillis()
        super.onPause()
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
        biometricUnlock = prefs.getBoolean(SettingsManager.KEY_BIOMETRIC_UNLOCK, false),
        deviceName = prefs.getString(SettingsManager.KEY_DEVICE_AUTH_NAME, "").orEmpty()
            .ifBlank { com.whitedevil.security.DeviceAuth.defaultDeviceName() },
    )

    internal fun saveSettingsFromCompose(form: SettingsFormState) {
        val wantBio = form.biometricUnlock
        if (wantBio && !com.whitedevil.security.BiometricAuth.available(this)) {
            UiFeedback.snackbar(snackbarAnchor, com.whitedevil.security.BiometricAuth.statusLabel(this))
        }
        val enableBio = wantBio && com.whitedevil.security.BiometricAuth.available(this)
        if (enableBio && !prefs.getBoolean(SettingsManager.KEY_BIOMETRIC_UNLOCK, false)) {
            // Confirm enrollment with a live prompt before turning the lock on.
            com.whitedevil.security.BiometricAuth.prompt(
                this,
                title = "Enable biometric unlock",
                subtitle = "Confirm fingerprint / face / PIN to protect WhiteDevil",
                onSuccess = {
                    prefs.edit()
                        .putString(SettingsManager.KEY_VENICE_API_KEY, form.veniceKey.trim())
                        .putString(SettingsManager.KEY_VENICE_SYSTEM_PROMPT, form.systemPrompt.trim())
                        .putBoolean(SettingsManager.KEY_VENICE_WEB_SEARCH, form.webSearch)
                        .putString(SettingsManager.KEY_RELAY_URL, form.relayUrl.trim().trimEnd('/'))
                        .putString(SettingsManager.KEY_RELAY_USER, form.relayUser.trim())
                        .putString(SettingsManager.KEY_RELAY_PASS, form.relayPass)
                        .putString(SettingsManager.KEY_LAPTOP_USER, form.laptopUser.trim())
                        .putString(SettingsManager.KEY_LAPTOP_PASS, form.laptopPass)
                        .putString(SettingsManager.KEY_DEVICE_AUTH_NAME, form.deviceName.trim().take(80))
                        .putBoolean(SettingsManager.KEY_BIOMETRIC_UNLOCK, true)
                        .apply()
                    appUnlocked = true
                    updateAgentSetupState()
                    offerPasswordsToSystemManager(form)
                    UiFeedback.snackbar(snackbarAnchor, "Biometric unlock enabled")
                    loadHubManifest()
                },
                onError = { UiFeedback.snackbar(snackbarAnchor, it) },
                onCancel = { UiFeedback.snackbar(snackbarAnchor, "Biometric unlock not enabled") },
            )
            return
        }
        prefs.edit()
            .putString(SettingsManager.KEY_VENICE_API_KEY, form.veniceKey.trim())
            .putString(SettingsManager.KEY_VENICE_SYSTEM_PROMPT, form.systemPrompt.trim())
            .putBoolean(SettingsManager.KEY_VENICE_WEB_SEARCH, form.webSearch)
            .putString(SettingsManager.KEY_RELAY_URL, form.relayUrl.trim().trimEnd('/'))
            .putString(SettingsManager.KEY_RELAY_USER, form.relayUser.trim())
            .putString(SettingsManager.KEY_RELAY_PASS, form.relayPass)
            .putString(SettingsManager.KEY_LAPTOP_USER, form.laptopUser.trim())
            .putString(SettingsManager.KEY_LAPTOP_PASS, form.laptopPass)
            .putString(SettingsManager.KEY_DEVICE_AUTH_NAME, form.deviceName.trim().take(80))
            .putBoolean(SettingsManager.KEY_BIOMETRIC_UNLOCK, enableBio)
            .apply()
        if (!enableBio) appUnlocked = true
        updateAgentSetupState()
        offerPasswordsToSystemManager(form)
        UiFeedback.snackbar(snackbarAnchor, "Settings saved")
        loadHubManifest()
    }

    private fun offerPasswordsToSystemManager(form: SettingsFormState) {
        CoroutineScope(Dispatchers.Main).launch {
            val n = com.whitedevil.security.PasswordManagerBridge.offerAll(
                activity = this@MainActivity,
                relayUrl = form.relayUrl.trim().trimEnd('/'),
                relayUser = form.relayUser.trim(),
                relayPass = form.relayPass,
                laptopUser = form.laptopUser.trim(),
                laptopPass = form.laptopPass,
            )
            if (n > 0) {
                UiFeedback.snackbar(snackbarAnchor, "Offered $n password(s) to Google / Samsung Pass")
            }
        }
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

    /**
     * Asks the user Allow/Deny from a worker thread and blocks for the answer. Denies on timeout
     * (e.g. the app is in the background and nobody can see the dialog): the safe default.
     */
    private fun askUserBlocking(title: String, detail: String): Boolean {
        val latch = CountDownLatch(1)
        val allowed = AtomicBoolean(false)
        main.post {
            AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(detail)
                .setCancelable(false)
                .setPositiveButton("Allow") { _, _ -> allowed.set(true); latch.countDown() }
                .setNegativeButton("Deny") { _, _ -> latch.countDown() }
                .show()
        }
        return latch.await(120, TimeUnit.SECONDS) && allowed.get()
    }

    private fun openAllFilesAccessSettings() {
        main.post {
            try {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }

    internal fun showModelPicker() {
        val labels = agentModels.map { UiPolish.modelLabel(it) }.toTypedArray()
        // Prefer a real AlertDialog when the bottom sheet theme is invisible / behind Compose.
        AlertDialog.Builder(this)
            .setTitle("Venice model")
            .setItems(labels) { _, which ->
                if (which !in agentModels.indices) return@setItems
                agentSelectedModel = agentModels[which]
                prefs.edit().putString(SettingsManager.KEY_VENICE_MODEL, agentSelectedModel).apply()
                Toast.makeText(this, "Model: ${UiPolish.modelLabel(agentSelectedModel)}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
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
        runCatching {
            // New chat first, then drop the old one: deleting the open chat alone would reopen another.
            val old = workspace.ensureCurrent()
            workspace.conversations.create()
            workspace.conversations.delete(old)
        }
        chatMessages.clear()
        agentStatusSubtitle = "On-device agent"
        addMessageBubble("Agent Reset", "Chat cleared. Ready for next task.", ROLE_VENICE)
    }

    /** Starts an empty chat. The previous one stays in Chats. */
    internal fun startNewChat() {
        currentAgentJob?.cancel()
        agentShowProgress = false
        agentThinking = false
        setAgentComposerEnabled(true)
        workspace.conversations.create()
        chatMessages.clear()
        agentStatusSubtitle = "New chat"
        selectTab(Tab.AGENT)
    }

    /** Opens a saved conversation in the Agent tab. */
    internal fun openConversation(id: String) {
        currentAgentJob?.cancel()
        agentShowProgress = false
        agentThinking = false
        setAgentComposerEnabled(true)
        workspace.conversations.setCurrent(id)
        chatMessages.clear()
        val history = loadAgentHistory()
        agentStatusSubtitle = if (history.isEmpty()) "New chat" else "${history.size} messages restored"
        renderHistoryBubbles(history)
        showYouSub(YouSub.HOME)
        selectTab(Tab.AGENT)
    }

    internal fun openChats() {
        selectTab(Tab.YOU)
        showYouSub(YouSub.CHATS)
    }

    /** Saves into the open conversation (image blobs are stripped by the store). */
    private fun persistAgentHistory(messages: List<ChatMessage>, id: String = workspace.ensureCurrent()) {
        runCatching {
            val lean = messages
                .filter { it.role in setOf("user", "assistant", "tool") }
                .takeLast(Agent.MAX_HISTORY_MESSAGES)
            workspace.conversations.save(id, lean)
        }
    }

    private fun loadAgentHistory(id: String = workspace.ensureCurrent()): List<ChatMessage> {
        return runCatching {
            workspace.conversations.load(id)
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
        val baseSysPrompt = buildString {
            append(savedPrompt.trim())
            if (!savedPrompt.contains("hub_overview") || !savedPrompt.contains("hub_request") ||
                !savedPrompt.contains("run_laptop_command")
            ) {
                append("\n\n")
                append(SettingsManager.AGENT_INTEGRATION_PROMPT)
            }
        }
        val webSearch = prefs.getBoolean(SettingsManager.KEY_VENICE_WEB_SEARCH, false)

        val workspaceDir = File(filesDir, "workspace")
        val relayUser = prefs.getString(SettingsManager.KEY_RELAY_USER, SettingsManager.DEFAULT_RELAY_USER) ?: SettingsManager.DEFAULT_RELAY_USER
        val relayPass = prefs.getString(SettingsManager.KEY_RELAY_PASS, "") ?: ""

        val accessConfig = AccessConfig(
            confirm = { title, detail -> askUserBlocking(title, detail) },
            phoneRoot = Environment.getExternalStorageDirectory(),
            phoneAccessGranted = { Environment.isExternalStorageManager() },
            requestPhoneAccess = { openAllFilesAccessSettings() },
        )
        // Fixed now: a chat switch mid-run must not redirect this run's save into another conversation.
        val convId = workspace.ensureCurrent()

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
                // Memory, skills and MCP servers. Connecting to MCP servers is network work (cached 5 min).
                val extensions = withContext(Dispatchers.IO) {
                    workspace.extensions { title, detail -> askUserBlocking(title, detail) }
                }
                val toolBox = ToolBox(
                    workspaceDir = workspaceDir,
                    relayBaseUrl = relayBase,
                    relayUser = relayUser,
                    relayPass = relayPass,
                    access = accessConfig,
                    extensions = extensions,
                )
                val addendum = toolBox.promptAddendum()
                val sysPrompt = if (addendum.isBlank()) baseSysPrompt else baseSysPrompt + "\n\n" + addendum
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
                        agent.restore(loadAgentHistory(convId))
                        finishedAgent = agent
                        agent.send(fullText, imageDataUrls)
                    }
                }
            } catch (e: Exception) {
                addMessageBubble("Error", e.message ?: "Unknown error running Venice agent", ROLE_ERROR)
            } finally {
                finishedAgent?.let { persistAgentHistory(it.snapshot(), convId) }
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
        refreshHubNativeScreen()
        UiFeedback.snackbar(snackbarAnchor, "Reloading ${hubScreens.firstOrNull { it.id == id }?.title ?: "screen"}")
    }

    private var hubBannerApkUrl: String? = null

    internal fun downloadHubUpdate() {
        hubForceUpdateApkUrl?.let { downloadApk(it) }
            ?: hubBannerApkUrl?.let { downloadApk(it) }
    }

    internal fun refreshHubNativeScreen() {
        if (hubBlockedByUpdate) return
        val id = currentHubScreenId ?: return
        hubScreenLoading = true
        hubScreenError = null
        scope.launch {
            try {
                val json = withContext(Dispatchers.IO) { fetchHubScreenJson(id) }
                hubScreenJson = json
                hubScreenLoading = false
                updateHubConnectionPill(online = true)
            } catch (e: Exception) {
                hubScreenLoading = false
                hubScreenError = when (e) {
                    is RelayHttpException -> e.message?.take(500) ?: "HTTP ${e.code}"
                    else -> e.message ?: "Could not load screen"
                }
            }
        }
    }

    private fun fetchHubScreenJson(screenId: String): String {
        val auth = basicAuth("wan")
        return when (screenId) {
            "home" -> {
                val status = RelayHttp.get(relayBase, auth, "/api/status")
                val colab = runCatching { RelayHttp.get(relayBase, auth, "/api/colab/state") }.getOrDefault("{}")
                val library = runCatching { RelayHttp.get(relayBase, auth, "/api/media/library") }.getOrDefault("[]")
                JSONObject().apply {
                    put("status", JSONObject(status))
                    put("colab", JSONObject(colab))
                    put("library", JSONArray(library))
                }.toString()
            }
            "renders", "gallery" -> RelayHttp.get(relayBase, auth, "/api/media/library")
            "setup" -> RelayHttp.get(relayBase, auth, "/api/setup")
            "thunder" -> {
                val state = RelayHttp.get(relayBase, auth, "/api/thunder/state")
                val library = runCatching { RelayHttp.get(relayBase, auth, "/api/media/library") }.getOrDefault("[]")
                JSONObject().apply {
                    put("state", JSONObject(state))
                    put("library", JSONArray(library))
                }.toString()
            }
            "colab" -> RelayHttp.get(relayBase, auth, "/api/colab/state")
            "hypno" -> {
                val overview = RelayHttp.get(relayBase, auth, "/api/laptop/hypno/overview")
                val jobs = runCatching { RelayHttp.get(relayBase, auth, "/api/laptop/hypno/jobs") }.getOrDefault("[]")
                JSONObject().apply {
                    put("overview", JSONObject(overview))
                    put("jobs", JSONArray(jobs))
                }.toString()
            }
            "ltx", "shotwriter" -> {
                val setup = runCatching { RelayHttp.get(relayBase, auth, "/api/setup") }.getOrDefault("{}")
                val jobs = runCatching { RelayHttp.get(relayBase, auth, "/api/gen/jobs") }.getOrDefault("[]")
                val library = runCatching { RelayHttp.get(relayBase, auth, "/api/media/library") }.getOrDefault("[]")
                JSONObject().apply {
                    put("setup", JSONObject(setup))
                    put("jobs", JSONArray(jobs))
                    put("library", JSONArray(library))
                }.toString()
            }
            "vast" -> {
                val queue = RelayHttp.get(relayBase, auth, "/api/thunder/queue")
                val library = runCatching { RelayHttp.get(relayBase, auth, "/api/media/library") }.getOrDefault("[]")
                JSONObject().apply {
                    put("queue", JSONObject(queue))
                    put("library", JSONArray(library))
                }.toString()
            }
            "files" -> {
                val ping = runCatching { RelayHttp.get(relayBase, auth, "/api/laptop/ping") }.getOrDefault("{}")
                val home = runCatching {
                    RelayHttp.post(
                        relayBase,
                        auth,
                        "/api/laptop/run",
                        """{"lang":"bash","code":"ls -lah ~ | head -50\n"}""",
                    )
                }.getOrDefault("{}")
                val paths = runCatching {
                    RelayHttp.post(
                        relayBase,
                        auth,
                        "/api/laptop/run",
                        """{"lang":"bash","code":"ls -lah ~/venice_run ~/civitai_dl 2>/dev/null | head -40\n"}""",
                    )
                }.getOrDefault("{}")
                JSONObject().apply {
                    put("ping", JSONObject(ping))
                    put("home", JSONObject(home))
                    put("paths", JSONObject(paths))
                }.toString()
            }
            else -> if (screenId.startsWith("bot-")) {
                val bid = screenId.removePrefix("bot-")
                val title = hubScreens.firstOrNull { it.id == screenId }?.title ?: bid
                val botJson = runCatching { RelayHttp.get(relayBase, auth, "/app/bots/$bid/bot.json") }.getOrDefault("{}")
                JSONObject().apply {
                    put("id", screenId)
                    put("title", title)
                    put("bot", JSONObject(botJson))
                }.toString()
            } else {
                val screen = hubScreens.firstOrNull { it.id == screenId }
                JSONObject().apply {
                    put("id", screenId)
                    put("title", screen?.title ?: screenId)
                    put("manifest_url", screen?.url ?: "")
                }.toString()
            }
        }
    }

    internal fun relayBaseUrl(): String = relayBase

    internal fun relayAuthorization(): String = basicAuth("wan")

    internal fun hubRelayPost(path: String, jsonBody: String, refreshAfter: Boolean = true) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    RelayHttp.post(relayBase, basicAuth("wan"), path, jsonBody)
                }
                if (refreshAfter) refreshHubNativeScreen()
                UiFeedback.snackbar(snackbarAnchor, "Done")
            } catch (e: Exception) {
                UiFeedback.snackbar(snackbarAnchor, e.message?.take(120) ?: "Request failed")
            }
        }
    }

    internal fun hubRelayPostWithResponse(path: String, jsonBody: String, onResult: (String) -> Unit) {
        scope.launch {
            val text = try {
                withContext(Dispatchers.IO) {
                    RelayHttp.post(relayBase, basicAuth("wan"), path, jsonBody)
                }
            } catch (e: Exception) {
                e.message ?: "Request failed"
            }
            main.post { onResult(text) }
        }
    }

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
        if (hubScreens.none { it.id == id }) return
        currentHubScreenId = id
        prefs.edit().putString("last_hub_screen", id).apply()
        renderHubChips()
        refreshHubNativeScreen()
    }

    internal fun loadHubManifest() {
        if (hubScreens.isEmpty()) hubScreenLoading = true
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
        val revChanged = !fromCache && hubWebRev != 0 && rev != hubWebRev
        if (!fromCache) hubWebRev = rev
        hubScreens = next
        hubScreenLoading = false
        if (next.isNotEmpty()) hubScreenError = null

        if (!fromCache) checkHubUpdate(json)

        val want = currentHubScreenId ?: prefs.getString("last_hub_screen", null)
        if (!hubBlockedByUpdate) {
            showHubScreen(hubScreens.firstOrNull { it.id == want }?.id ?: hubScreens.firstOrNull()?.id ?: "home")
        } else {
            renderHubChips()
        }
        if (revChanged && !hubBlockedByUpdate) refreshHubNativeScreen()
        updateHubConnectionPill(online = !fromCache || hubScreens.isNotEmpty(), detail = if (fromCache) "Cached" else null)
    }

    private fun showHubOffline(msg: String?) {
        updateHubConnectionPill(online = false, detail = "Offline")
        hubScreenLoading = false
        hubScreenError =
            "Forge Hub relay offline\n$relayBase\n${msg ?: "Could not reach relay"}\n\nAgent and Terminal still work on-device."
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
            hubForceUpdateVersion = latest
            hubForceUpdateApkUrl = absoluteRelayUrl(apk)
            hubBannerVisible = false
            hubScreenLoading = false
            return
        }
        hubBlockedByUpdate = false
        hubBannerText = "Forge Hub update v$latest available. Tap to install."
        hubBannerVisible = true
        hubBannerApkUrl = absoluteRelayUrl(apk)
    }

    internal fun sendPasteToTerminal() {
        val text = terminalPasteText.trim()
        terminalPasteOpen = false
        if (text.isNotEmpty()) runTerminalCommand(text)
    }

    internal fun runTerminalCommand(cmd: String) {
        val trimmed = cmd.trim()
        if (trimmed.isEmpty() || terminalRunning) return
        terminalLog.add("$ $trimmed")
        terminalRunning = true
        scope.launch {
            try {
                val body = JSONObject().apply {
                    put("lang", "bash")
                    put("code", trimmed)
                }.toString()
                val resp = withContext(Dispatchers.IO) {
                    RelayHttp.post(relayBase, basicAuth("wan"), "/api/laptop/run", body)
                }
                val j = JSONObject(resp)
                val out = j.optString("output").ifBlank { resp }
                out.lines().forEach { line ->
                    if (line.isNotBlank()) terminalLog.add(line)
                }
                if (!j.optBoolean("ok", true)) {
                    terminalLog.add("[exit ${j.optInt("exit")}]")
                }
            } catch (e: Exception) {
                terminalLog.add(
                    when (e) {
                        is RelayHttpException -> "Error: ${e.message?.take(400) ?: "HTTP ${e.code}"}"
                        else -> "Error: ${e.message ?: "run failed"}"
                    },
                )
            } finally {
                terminalRunning = false
            }
        }
    }

    // =========================================================================
    // Tab 4: Native Settings Component
    // =========================================================================

    // buildSettingsTab removed (Compose UI)


    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
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

    }

    // =========================================================================
    // Biometric unlock + phone files
    // =========================================================================

    internal fun biometricEnabled(): Boolean =
        prefs.getBoolean(SettingsManager.KEY_BIOMETRIC_UNLOCK, false)

    internal fun biometricStatusLabel(): String =
        com.whitedevil.security.BiometricAuth.statusLabel(this)

    private fun evaluateAppLock(forcePrompt: Boolean) {
        if (!biometricEnabled()) {
            appUnlocked = true
            return
        }
        if (!com.whitedevil.security.BiometricAuth.available(this)) {
            // Can't lock the user out if hardware disappears — leave unlocked.
            appUnlocked = true
            return
        }
        val awayMs = if (lastBackgroundAt == 0L) Long.MAX_VALUE else System.currentTimeMillis() - lastBackgroundAt
        val shouldLock = !appUnlocked || forcePrompt || awayMs > 30_000L
        if (!shouldLock) return
        appUnlocked = false
        promptBiometricUnlock()
    }

    internal fun promptBiometricUnlock() {
        com.whitedevil.security.BiometricAuth.prompt(
            this,
            onSuccess = { appUnlocked = true },
            onError = { msg ->
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            },
            onCancel = { /* stay locked */ },
        )
    }

    // -------------------------------------------------------------------------
    // Device-bound auth (hub/auth.py). Strictly additive: relay basic auth is
    // still sent on every request, and every failure path here is a message, not
    // a blocked app.
    // -------------------------------------------------------------------------

    internal fun refreshDeviceAuthState() {
        deviceAuthState = com.whitedevil.security.DeviceAuth.state(this)
    }

    /** Enrol (or re-enrol) this phone, then sign in. Prompts for fingerprint / PIN. */
    internal fun enrolDeviceAuth(deviceName: String, enrolCode: String = "") {
        if (deviceAuthBusy) return
        deviceAuthBusy = true
        deviceAuthMessage = "Enrolling this phone…"
        scope.launch {
            val outcome = com.whitedevil.security.DeviceAuth.enrolAndSignIn(
                activity = this@MainActivity,
                relayBase = relayBase,
                basicAuth = basicAuth("wan"),
                deviceName = deviceName,
                enrolCode = enrolCode,
            )
            deviceAuthBusy = false
            deviceAuthMessage = outcome.message
            refreshDeviceAuthState()
            UiFeedback.snackbar(snackbarAnchor, outcome.message)
        }
    }

    /** Refresh the device token if it has expired. No-op while one is still live. */
    internal fun signInDeviceAuth() {
        if (deviceAuthBusy) return
        deviceAuthBusy = true
        deviceAuthMessage = "Signing in…"
        scope.launch {
            val outcome = com.whitedevil.security.DeviceAuth.ensureToken(
                activity = this@MainActivity,
                relayBase = relayBase,
                basicAuth = basicAuth("wan"),
            )
            deviceAuthBusy = false
            deviceAuthMessage = outcome.message
            refreshDeviceAuthState()
            UiFeedback.snackbar(snackbarAnchor, outcome.message)
        }
    }

    /** Drop the local enrolment and key. The hub-side record is revoked separately. */
    internal fun forgetDeviceAuth() {
        com.whitedevil.security.DeviceAuth.forget(this)
        deviceAuthMessage =
            "This phone's device key was deleted locally. Revoke it on the hub too if it is still listed."
        refreshDeviceAuthState()
        UiFeedback.snackbar(snackbarAnchor, "Device key removed from this phone")
    }

    internal fun unlockViaSettingsFallback() {
        // Let the user reach Settings to disable biometrics or fix credentials.
        appUnlocked = true
        selectTab(Tab.YOU)
        showYouSub(YouSub.SETTINGS)
    }

    internal fun phoneFolderUri(): Uri? {
        val raw = prefs.getString(SettingsManager.KEY_PHONE_FOLDER_URI, null) ?: return null
        return runCatching { Uri.parse(raw) }.getOrNull()
    }

    private fun persistPhoneFolder(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            try {
                contentResolver.takePersistableUriPermission(uri, flags)
            } catch (e: SecurityException) {
                Toast.makeText(this, "Couldn't keep folder access: ${e.message}", Toast.LENGTH_LONG).show()
                return
            }
        }
        prefs.edit().putString(SettingsManager.KEY_PHONE_FOLDER_URI, uri.toString()).apply()
        Toast.makeText(this, "Folder linked", Toast.LENGTH_SHORT).show()
        showYouSub(YouSub.FILES)
    }

    internal fun pickPhoneFolder() {
        pickPhoneFolderLauncher.launch(null)
    }

    internal fun pickPhoneFiles() {
        pickFilesLauncher.launch(arrayOf("*/*"))
        selectTab(Tab.AGENT)
    }

    internal fun pickPhoneMedia() {
        pickPhoneMediaLauncher.launch("image/*")
    }

    internal fun requestPhoneMediaPermission() {
        val needed = mutableListOf(
            android.Manifest.permission.READ_MEDIA_IMAGES,
            android.Manifest.permission.READ_MEDIA_VIDEO,
            android.Manifest.permission.READ_MEDIA_AUDIO,
        )
        if (Build.VERSION.SDK_INT <= 32) {
            needed += android.Manifest.permission.READ_EXTERNAL_STORAGE
        }
        val missing = needed.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            Toast.makeText(this, "Media access already granted", Toast.LENGTH_SHORT).show()
            return
        }
        pendingPermissionAction = {
            Toast.makeText(this, "Media access granted", Toast.LENGTH_SHORT).show()
        }
        permissionLauncher.launch(missing.toTypedArray())
    }

    internal fun attachPhoneUri(uri: Uri) {
        addContentAttachment(uri)
        syncPendingAttachmentsUi()
        selectTab(Tab.AGENT)
        Toast.makeText(this, "Attached to Agent", Toast.LENGTH_SHORT).show()
    }


}
