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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.whitedevil.ui.chat.AgentChatScreen
import com.whitedevil.ui.chat.ChatUiMessage
import com.whitedevil.ui.onboarding.OnboardingScreen
import com.whitedevil.ui.theme.WhiteDevilTheme
import com.whitedevil.ui.you.YouHomeScreen
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

    private lateinit var root: FrameLayout
    private lateinit var contentColumn: LinearLayout
    private lateinit var onboardingOverlay: FrameLayout
    private lateinit var tabContentContainer: FrameLayout
    private lateinit var bottomNavBar: LinearLayout
    private lateinit var bottomNavRow: LinearLayout

    // Containers for the 4 tabs
    private lateinit var agentContainer: FrameLayout
    private lateinit var forgeHubContainer: FrameLayout
    private lateinit var terminalContainer: FrameLayout
    private lateinit var youContainer: FrameLayout
    private lateinit var youHomeCompose: ComposeView
    private lateinit var settingsWrapper: LinearLayout
    private lateinit var settingsContainer: ScrollView
    private lateinit var youSettingsBack: TextView
    private lateinit var youTerminalBack: TextView

    // Forge Hub UI state (isolated)
    private lateinit var hubLoadBar: View
    private lateinit var hubBanner: TextView
    private lateinit var hubScreenChips: LinearLayout
    private lateinit var hubScreenChipsScroll: HorizontalScrollView
    private lateinit var hubContent: FrameLayout
    private val hubWebViews = HashMap<String, WebView>()
    private var hubScreens: List<Screen> = emptyList()
    private var currentHubScreenId: String? = null
    private var hubBlockedByUpdate = false
    private var hubWebRev = 0
    private val authTries = HashMap<String, Int>()

    // Terminal tab state (isolated)
    private var terminalWebView: WebView? = null
    private lateinit var terminalLoadBar: View
    private lateinit var terminalPasteSheet: LinearLayout
    private lateinit var terminalPasteInput: EditText

    // Agent tab state
    private lateinit var agentChatCompose: ComposeView
    private val chatMessages = mutableStateListOf<ChatUiMessage>()
    private var chatScrollTrigger by mutableIntStateOf(0)
    private var agentThinking by mutableStateOf(false)
    private var nextChatId = 1L
    private var youSubScreen: YouSub = YouSub.HOME
    private var youConnectionSummary by mutableStateOf("Tap Test connections on You home or in Settings.")
    private lateinit var agentInput: EditText
    private lateinit var agentSendWrap: FrameLayout
    private lateinit var agentModelChip: TextView
    private lateinit var agentStatusPill: TextView
    private lateinit var agentApiKeyBanner: LinearLayout
    private lateinit var agentOverflowAnchor: View
    private lateinit var agentProgress: ProgressBar
    private lateinit var settingsConnectionSummary: TextView
    private lateinit var hubConnectionPill: TextView
    private val agentModels = listOf(
        "zai-org-glm-5-2",
        "zai-org-glm-5",
        "venice-uncensored",
        "venice-uncensored-1-2",
        "kimi-k2-6",
        "claude-opus-4-8",
    )
    private var agentSelectedModel: String = SettingsManager.DEFAULT_MODEL
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
    private lateinit var agentAttachmentStrip: LinearLayout
    private lateinit var agentAttachmentScroll: HorizontalScrollView
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

    // Current navigation state
    private var activeTab: Tab = Tab.AGENT
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
        root = FrameLayout(this).apply { setBackgroundColor(BG) }
        contentColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        tabContentContainer = FrameLayout(this)

        agentContainer = buildAgentTab()
        tabContentContainer.addView(agentContainer, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        forgeHubContainer = buildForgeHubTab()
        tabContentContainer.addView(forgeHubContainer, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        terminalContainer = buildTerminalTab()
        settingsContainer = buildSettingsTab()
        youContainer = buildYouTab()
        tabContentContainer.addView(youContainer, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        bottomNavRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(10), dp(8), dp(8))
        }

        bottomNavBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = createGlassDrawable(BAR_GLASS, border = LINE)
            addView(bottomNavRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }

        contentColumn.addView(tabContentContainer, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        contentColumn.addView(bottomNavBar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        root.addView(contentColumn, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        onboardingOverlay = FrameLayout(this).apply {
            visibility = View.GONE
            setBackgroundColor(Color.parseColor("#FF080809"))
        }
        root.addView(onboardingOverlay, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        contentColumn.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            val ime = insets.getInsets(WindowInsets.Type.ime())
            val typing = ime.bottom > bars.bottom
            v.setPadding(bars.left, bars.top, bars.right, if (typing) ime.bottom else 0)
            bottomNavBar.visibility = if (typing) View.GONE else View.VISIBLE
            bottomNavBar.setPadding(0, 0, 0, bars.bottom)
            WindowInsets.CONSUMED
        }

        setContentView(root)
        renderBottomNav()
    }

    private fun maybeShowOnboarding() {
        if (prefs.getBoolean(SettingsManager.KEY_ONBOARDING_COMPLETE, false)) return
        onboardingOverlay.visibility = View.VISIBLE
        onboardingOverlay.removeAllViews()
        val compose = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                WhiteDevilTheme {
                    OnboardingScreen(onFinished = { completeOnboarding() })
                }
            }
        }
        onboardingOverlay.addView(compose, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    private fun completeOnboarding() {
        prefs.edit().putBoolean(SettingsManager.KEY_ONBOARDING_COMPLETE, true).apply()
        onboardingOverlay.visibility = View.GONE
        onboardingOverlay.removeAllViews()
    }

    private fun renderBottomNav() {
        bottomNavRow.removeAllViews()
        bottomNavRow.addView(navTabItem(R.drawable.ic_venice, "Agent", activeTab == Tab.AGENT) { selectTab(Tab.AGENT) })
        bottomNavRow.addView(navTabItem(R.drawable.ic_home, "Forge Hub", activeTab == Tab.FORGE_HUB) { selectTab(Tab.FORGE_HUB) })
        bottomNavRow.addView(navTabItem(R.drawable.ic_settings, "You", activeTab == Tab.YOU) { selectTab(Tab.YOU) })
    }

    private fun navTabItem(icon: Int, label: String, active: Boolean, onClick: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        val tint = if (active) ACCENT else MUTED

        val pill = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(if (active) PILL else Color.TRANSPARENT)
                if (active) setStroke(dp(1), Color.parseColor("#33CDB88F"))
            }
            if (active) {
                addView(View(context).apply {
                    background = GradientDrawable().apply {
                        cornerRadius = dp(2).toFloat()
                        setColor(ACCENT)
                    }
                }, FrameLayout.LayoutParams(dp(20), dp(3), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
                    topMargin = dp(4)
                })
            }
            addView(ImageView(context).apply {
                setImageResource(icon)
                imageTintList = ColorStateList.valueOf(tint)
            }, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
        }

        addView(pill, LinearLayout.LayoutParams(dp(58), dp(36)))
        addView(TextView(context).apply {
            text = label
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(if (active) STRONG else MUTED)
            typeface = if (active) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            maxLines = 1
            setPadding(0, dp(4), 0, 0)
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))

        isClickable = true
        background = RippleDrawable(ColorStateList.valueOf(PILL), null, null)
        setOnClickListener { onClick() }
    }

    private fun selectTab(tab: Tab) {
        activeTab = tab
        agentContainer.visibility = if (tab == Tab.AGENT) View.VISIBLE else View.GONE
        forgeHubContainer.visibility = if (tab == Tab.FORGE_HUB) View.VISIBLE else View.GONE
        youContainer.visibility = if (tab == Tab.YOU) View.VISIBLE else View.GONE

        if (tab == Tab.YOU) {
            showYouSub(YouSub.HOME)
            refreshYouHomeSummary()
        }
        if (tab == Tab.FORGE_HUB && hubScreens.isEmpty()) {
            loadHubManifest()
        }
        renderBottomNav()
    }

    private fun showYouSub(sub: YouSub) {
        youSubScreen = sub
        if (sub == YouSub.TERMINAL && terminalWebView == null) {
            setupTerminalWebView()
        }
        if (sub == YouSub.HOME) refreshYouHomeSummary()
        renderYouSubVisibility()
    }

    private fun renderYouSubVisibility() {
        if (!::youHomeCompose.isInitialized) return
        youHomeCompose.visibility = if (youSubScreen == YouSub.HOME) View.VISIBLE else View.GONE
        terminalContainer.visibility = if (youSubScreen == YouSub.TERMINAL) View.VISIBLE else View.GONE
        settingsWrapper.visibility = if (youSubScreen == YouSub.SETTINGS) View.VISIBLE else View.GONE
        if (::youTerminalBack.isInitialized) {
            youTerminalBack.visibility = if (youSubScreen == YouSub.TERMINAL) View.VISIBLE else View.GONE
        }
    }

    private fun buildYouTab(): FrameLayout {
        val shell = FrameLayout(this).apply {
            background = UiPolish.screenGradient(this@MainActivity)
        }

        youHomeCompose = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { YouHomeComposeHost() }
        }
        shell.addView(youHomeCompose, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        settingsWrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        youSettingsBack = TextView(this).apply {
            text = "← You"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ACCENT)
            setPadding(dp(20), dp(40), dp(20), dp(8))
            isClickable = true
            setOnClickListener { showYouSub(YouSub.HOME) }
        }
        settingsWrapper.addView(youSettingsBack)
        settingsWrapper.addView(settingsContainer, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        shell.addView(settingsWrapper, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        shell.addView(terminalContainer, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        terminalContainer.visibility = View.GONE
        return shell
    }

    @androidx.compose.runtime.Composable
    private fun YouHomeComposeHost() {
        WhiteDevilTheme {
            YouHomeScreen(
                connectionSummary = youConnectionSummary,
                veniceReady = veniceKeyConfigured(),
                onTerminal = { showYouSub(YouSub.TERMINAL) },
                onSettings = { showYouSub(YouSub.SETTINGS) },
                onTestConnections = { runQuickConnectionTest(updateYouHome = true) },
                onAddVeniceKey = { showVeniceKeySheet() },
            )
        }
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
            youConnectionSummary = snap.multiline()
        }
    }

    private fun runQuickConnectionTest(updateYouHome: Boolean = false) {
        if (::settingsConnectionSummary.isInitialized) {
            settingsConnectionSummary.text = "Testing…"
        }
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
            val text = snap.multiline()
            if (updateYouHome) youConnectionSummary = text
            if (::settingsConnectionSummary.isInitialized) settingsConnectionSummary.text = text
        }
    }

    // =========================================================================
    // Tab 1: Native Venice Agent Tab
    // =========================================================================

    private fun buildAgentTab(): FrameLayout {
        val shell = FrameLayout(this).apply {
            background = UiPolish.screenGradient(this@MainActivity)
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(36), dp(16), dp(10))
        }

        agentSelectedModel = prefs.getString(SettingsManager.KEY_VENICE_MODEL, SettingsManager.DEFAULT_MODEL)
            ?: SettingsManager.DEFAULT_MODEL

        val appBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), 0, dp(2), dp(10))
        }
        appBar.addView(TextView(this).apply {
            text = "Venice"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(STRONG)
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        agentStatusPill = TextView(this).apply {
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(10), dp(5), dp(10), dp(5))
            background = createGlassDrawable(CARD_BG, dp(12), LINE)
        }
        agentOverflowAnchor = UiPolish.iconCircle(
            this,
            R.drawable.ic_more,
            MUTED,
            "Agent options",
        ) { showAgentOverflowMenu() }
        appBar.addView(agentStatusPill, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { rightMargin = dp(8) })
        appBar.addView(agentOverflowAnchor, LinearLayout.LayoutParams(dp(48), dp(48)))
        layout.addView(appBar)

        agentModelChip = TextView(this).apply {
            text = "${UiPolish.modelLabel(agentSelectedModel)}  ▾"
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(STRONG)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = createGlassDrawable(CARD_BG, dp(14), LINE)
            isClickable = true
            contentDescription = "Choose Venice model"
            setOnClickListener { showModelPicker() }
        }
        layout.addView(agentModelChip, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { bottomMargin = dp(8) })

        agentProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            visibility = View.GONE
            indeterminateTintList = ColorStateList.valueOf(ACCENT)
        }
        layout.addView(agentProgress, LinearLayout.LayoutParams(MATCH_PARENT, dp(3)).apply { topMargin = dp(8) })

        agentChatCompose = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                WhiteDevilTheme {
                    AgentChatScreen(
                        messages = chatMessages,
                        agentThinking = agentThinking,
                        scrollTrigger = chatScrollTrigger,
                        onToggleTool = { id -> toggleToolMessage(id) },
                        onCopy = { copyToClipboard(it) },
                    )
                }
            }
        }
        layout.addView(agentChatCompose, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        // Initial welcome message, or restored conversation.
        val restoredHistory = loadAgentHistory()
        if (restoredHistory.isEmpty()) {
            addMessageBubble(
                "Ready for beta",
                "Chat with Venice on-device, attach photos and files, and run tools against your workspace and relay. Tap 📎 to attach, or share from another app into WhiteDevil.",
                ROLE_INFO
            )
        } else {
            addMessageBubble(
                "History restored",
                "${restoredHistory.size} messages from your last session are loaded. The agent remembers the conversation.",
                ROLE_INFO
            )
            renderHistoryBubbles(restoredHistory)
        }

        // Attachment strip (pending photos / videos / files), hidden until used
        agentAttachmentScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            visibility = View.GONE
        }
        agentAttachmentStrip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(6))
        }
        agentAttachmentScroll.addView(agentAttachmentStrip)

        val composer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, 0)
        }
        agentApiKeyBanner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = createGlassDrawable(Color.parseColor("#33CC5555"), dp(14), Color.parseColor("#66E85D5D"))
            visibility = View.GONE
            addView(TextView(this@MainActivity).apply {
                text = "Venice API key required to chat"
                textSize = 13f
                setTextColor(STRONG)
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(TextView(this@MainActivity).apply {
                text = "Add key"
                textSize = 12.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#FF111111"))
                setPadding(dp(14), dp(8), dp(14), dp(8))
                background = createGlassDrawable(ACCENT, dp(12))
                isClickable = true
                setOnClickListener { showVeniceKeySheet() }
            })
        }
        composer.addView(agentApiKeyBanner, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(6) })
        composer.addView(agentAttachmentScroll, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val inputBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(8), dp(6), dp(8))
            background = createGlassDrawable(BAR_GLASS, dp(22), LINE)
        }
        val attachBtn = UiPolish.iconCircle(this, R.drawable.ic_attach, ACCENT, "Attach file") { showAttachSheet() }.apply {
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply { rightMargin = dp(4) }
        }
        val pasteBtn = UiPolish.iconCircle(this, R.drawable.ic_clipboard, MUTED, "Paste from clipboard") { pasteFromClipboard() }.apply {
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply { rightMargin = dp(6) }
        }
        agentInput = EditText(this).apply {
            hint = "Message Venice…"
            setHintTextColor(MUTED)
            setTextColor(STRONG)
            textSize = 15f
            background = createGlassDrawable(Color.parseColor("#28000000"), dp(16), LINE)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            maxLines = 5
        }
        agentSendWrap = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(ACCENT)
            }
            isClickable = true
            contentDescription = "Send message"
            setOnClickListener { sendAgentMessage() }
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply { leftMargin = dp(6) }
            addView(ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ic_send)
                imageTintList = ColorStateList.valueOf(Color.parseColor("#FF111111"))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
        }

        inputBar.addView(attachBtn)
        inputBar.addView(pasteBtn)
        inputBar.addView(agentInput, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        inputBar.addView(agentSendWrap)
        composer.addView(inputBar)
        layout.addView(composer)

        shell.addView(layout, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        return shell
    }

    private fun veniceKeyConfigured(): Boolean =
        !prefs.getString(SettingsManager.KEY_VENICE_API_KEY, "")?.trim().isNullOrEmpty()

    private fun updateAgentSetupState() {
        if (!::agentStatusPill.isInitialized) return
        val ok = veniceKeyConfigured()
        if (::agentApiKeyBanner.isInitialized) {
            agentApiKeyBanner.visibility = if (ok) View.GONE else View.VISIBLE
        }
        agentStatusPill.text = if (ok) "Ready" else "Setup"
        agentStatusPill.setTextColor(if (ok) ACCENT else Color.parseColor("#FFE85D5D"))
        agentStatusPill.background = createGlassDrawable(
            if (ok) CARD_BG else Color.parseColor("#33CC5555"),
            dp(12),
            if (ok) LINE else Color.parseColor("#66E85D5D"),
        )
    }

    private fun showVeniceKeySheet() {
        val current = prefs.getString(SettingsManager.KEY_VENICE_API_KEY, "") ?: ""
        UiSheets.showSecretFieldSheet(
            this,
            title = "Venice API key",
            hint = "Paste your Venice API key",
            initial = current,
        ) { key ->
            prefs.edit().putString(SettingsManager.KEY_VENICE_API_KEY, key).apply()
            updateAgentSetupState()
            UiFeedback.snackbar(root, "Venice API key saved")
        }
    }

    private fun showAgentOverflowMenu() {
        PopupMenu(this, agentOverflowAnchor).apply {
            menu.add(0, 1, 0, "System prompt")
            menu.add(0, 2, 0, "Clear chat")
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> showSystemPromptDialog()
                    2 -> confirmClearAgentChat()
                }
                true
            }
            show()
        }
    }

    private fun confirmClearAgentChat() {
        AlertDialog.Builder(this)
            .setTitle("Clear chat?")
            .setMessage("This removes the on-screen history and saved conversation file.")
            .setPositiveButton("Clear") { _, _ -> resetAgentChat() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showModelPicker() {
        val labels = agentModels.map { UiPolish.modelLabel(it) }.toTypedArray()
        UiSheets.showListSheet(this, "Venice model", labels) { which ->
            agentSelectedModel = agentModels[which]
            agentModelChip.text = "${UiPolish.modelLabel(agentSelectedModel)}  ▾"
            prefs.edit().putString(SettingsManager.KEY_VENICE_MODEL, agentSelectedModel).apply()
        }
    }

    private fun showSystemPromptDialog() {
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
        agentProgress.visibility = View.GONE
        runCatching { agentHistoryFile().delete() }
        chatMessages.clear()
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

    private fun sendAgentMessage() {
        val text = agentInput.text.toString().trim()
        if (text.isEmpty() && pendingAttachments.isEmpty()) return
        val apiKey = prefs.getString(SettingsManager.KEY_VENICE_API_KEY, "")?.trim() ?: ""
        if (apiKey.isEmpty()) {
            updateAgentSetupState()
            UiFeedback.snackbar(root, "Add a Venice API key to send messages", "Add key") { showVeniceKeySheet() }
            return
        }

        agentInput.setText("")
        val attachmentSnapshot = pendingAttachments.toList()
        pendingAttachments.clear()
        renderAttachmentStrip()
        val selectedModel = agentSelectedModel
        val sysPrompt = prefs.getString(SettingsManager.KEY_VENICE_SYSTEM_PROMPT, SettingsManager.DEFAULT_SYSTEM_PROMPT)
            ?: SettingsManager.DEFAULT_SYSTEM_PROMPT
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

        agentProgress.visibility = View.VISIBLE
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
                        agentProgress.visibility = View.GONE
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
                agentProgress.visibility = View.GONE
                agentThinking = false
                setAgentComposerEnabled(true)
            }
        }
    }

    private fun toggleToolMessage(id: Long) {
        val idx = chatMessages.indexOfFirst { it.id == id }
        if (idx < 0) return
        val cur = chatMessages[idx]
        chatMessages[idx] = cur.copy(toolExpanded = !cur.toolExpanded)
    }

    private fun setAgentComposerEnabled(enabled: Boolean) {
        agentSendWrap.isEnabled = enabled
        agentSendWrap.alpha = if (enabled) 1f else 0.42f
        agentInput.isEnabled = enabled
    }

    // =========================================================================
    // Device integration: attachments, clipboard, share receive, permissions
    // =========================================================================

    private fun showAttachSheet() {
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

    private fun renderAttachmentStrip() {
        agentAttachmentStrip.removeAllViews()
        if (pendingAttachments.isEmpty()) {
            agentAttachmentScroll.visibility = View.GONE
            return
        }
        agentAttachmentScroll.visibility = View.VISIBLE
        pendingAttachments.forEachIndexed { index, attachment ->
            val chip = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = createGlassDrawable(CARD_BG, dp(14), LINE)
                setPadding(dp(8), dp(6), dp(8), dp(6))
            }
            if (attachment.kind == Attachments.Kind.IMAGE) {
                chip.addView(ImageView(this).apply {
                    runCatching { setImageURI(attachment.uri) }
                }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { rightMargin = dp(8) })
            } else {
                val icon = when (attachment.kind) {
                    Attachments.Kind.VIDEO -> "🎬"
                    Attachments.Kind.AUDIO -> "🎵"
                    else -> "📄"
                }
                chip.addView(TextView(this).apply {
                    text = icon
                    textSize = 22f
                    setPadding(0, 0, dp(8), 0)
                })
            }
            val label = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            label.addView(TextView(this).apply {
                text = attachment.name
                textSize = 12f
                setTextColor(STRONG)
                maxLines = 1
            })
            label.addView(TextView(this).apply {
                text = if (attachment.size >= 0) Attachments.formatSize(attachment.size) else attachment.mime
                textSize = 11f
                setTextColor(MUTED)
                maxLines = 1
            })
            chip.addView(label, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { rightMargin = dp(8) })
            chip.addView(TextView(this).apply {
                text = "✕"
                textSize = 14f
                setTextColor(MUTED)
                setPadding(dp(8), dp(8), dp(8), dp(8))
                isClickable = true
                setOnClickListener {
                    pendingAttachments.removeAt(index)
                    renderAttachmentStrip()
                }
            })
            agentAttachmentStrip.addView(chip, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                rightMargin = dp(8)
            })
        }
    }

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

    private fun copyToClipboard(text: String) {
        if (text.isBlank()) {
            Toast.makeText(this, "Nothing to copy", Toast.LENGTH_SHORT).show()
            return
        }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("WhiteDevil", text))
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }

    private fun pasteFromClipboard() {
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
        val pos = agentInput.selectionStart.coerceAtLeast(0).coerceAtMost(agentInput.text.length)
        agentInput.text.insert(pos, text)
    }

    @Suppress("DEPRECATION")
    private fun handleSharedIntent(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            Intent.ACTION_SEND -> {
                var added = false
                intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }?.let { shared ->
                    agentInput.append(if (agentInput.text.isEmpty()) shared else "\n$shared")
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

    private fun buildForgeHubTab(): FrameLayout {
        val outer = FrameLayout(this).apply {
            background = UiPolish.screenGradient(this@MainActivity)
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val hubTopBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(36), dp(16), dp(8))
        }
        hubTopBar.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = "Forge Hub"
                textSize = 20f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(STRONG)
            })
            addView(TextView(context).apply {
                text = "Relay tools & dashboards"
                textSize = 12f
                setTextColor(MUTED)
            })
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        hubConnectionPill = TextView(this).apply {
            text = "Checking…"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(MUTED)
            setPadding(dp(10), dp(5), dp(10), dp(5))
            background = createGlassDrawable(CARD_BG, dp(12), LINE)
        }
        val hubReload = UiPolish.iconCircle(this, R.drawable.ic_refresh, ACCENT, "Reload current screen") {
            reloadCurrentHubScreen()
        }
        hubTopBar.addView(hubConnectionPill, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { rightMargin = dp(8) })
        hubTopBar.addView(hubReload, LinearLayout.LayoutParams(dp(48), dp(48)))
        container.addView(hubTopBar)

        // Isolated Update / Offline Banner inside Forge Hub only
        hubBanner = TextView(this).apply {
            background = GradientDrawable().apply { setColor(STRONG) }
            setTextColor(Color.parseColor("#FF111111"))
            typeface = Typeface.DEFAULT_BOLD
            textSize = 13f
            setPadding(dp(16), dp(10), dp(16), dp(10))
            visibility = View.GONE
        }
        container.addView(hubBanner, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        // Screen selection chips row (Home, Renders, Colab, Thunder, etc.)
        hubScreenChips = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(4), dp(8), dp(8))
        }
        hubScreenChipsScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(hubScreenChips)
        }
        container.addView(hubScreenChipsScroll, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        // Content area for WebViews
        hubContent = FrameLayout(this)
        hubLoadBar = View(this).apply {
            setBackgroundColor(ACCENT)
            pivotX = 0f
            scaleX = 0f
            alpha = 0f
        }
        hubContent.addView(hubLoadBar, FrameLayout.LayoutParams(MATCH_PARENT, dp(2), Gravity.TOP))

        container.addView(hubContent, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        outer.addView(container, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        return outer
    }

    private fun renderHubChips() {
        hubScreenChips.removeAllViews()
        val preferred = listOf("home", "renders", "colab", "thunder", "shotwriter", "hypno", "files")
        val sortedScreens = hubScreens.sortedBy { s ->
            val idx = preferred.indexOf(s.id)
            if (idx >= 0) idx else 99
        }

        for (s in sortedScreens) {
            val active = s.id == currentHubScreenId
            val chip = TextView(this).apply {
                text = s.title
                textSize = 13f
                typeface = if (active) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                setTextColor(if (active) STRONG else MUTED)
                background = if (active) {
                    createGlassDrawable(PILL, dp(16), ACCENT)
                } else {
                    createGlassDrawable(CARD_BG, dp(16), LINE)
                }
                setPadding(dp(16), dp(8), dp(16), dp(8))
                isClickable = true
                setOnClickListener { showHubScreen(s.id) }
            }
            val p = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                rightMargin = dp(8)
            }
            hubScreenChips.addView(chip, p)
        }
    }

    private fun reloadCurrentHubScreen() {
        val id = currentHubScreenId
        if (id.isNullOrEmpty()) {
            loadHubManifest()
            return
        }
        hubWebViews[id]?.reload()
        UiFeedback.snackbar(root, "Reloading ${hubScreens.firstOrNull { it.id == id }?.title ?: "screen"}")
    }

    private fun updateHubConnectionPill(online: Boolean, detail: String? = null) {
        if (!::hubConnectionPill.isInitialized) return
        hubConnectionPill.text = when {
            online -> "Online"
            detail != null -> detail
            else -> "Offline"
        }
        hubConnectionPill.setTextColor(if (online) ACCENT else MUTED)
    }

    private fun showHubScreen(id: String) {
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
                        hubBanner.text = "Relay authentication rejected. Check password in Settings."
                        hubBanner.visibility = View.VISIBLE
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
                        hubBanner.text = "Relay offline - showing cached screens"
                        hubBanner.visibility = View.VISIBLE
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
        hubContent.addView(hubLoadBar, FrameLayout.LayoutParams(MATCH_PARENT, dp(2), Gravity.TOP))
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
            hubBanner.visibility = View.GONE
            return
        }
        val apk = json.optString("apk_url", "/app/forgehub.apk")
        if (force) {
            hubBlockedByUpdate = true
            hubWebViews.values.forEach { it.visibility = View.GONE }
            hubBanner.visibility = View.GONE
            hubContent.removeAllViews()
            hubContent.addView(hubLoadBar, FrameLayout.LayoutParams(MATCH_PARENT, dp(2), Gravity.TOP))
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
                        hubContent.addView(hubLoadBar, FrameLayout.LayoutParams(MATCH_PARENT, dp(2), Gravity.TOP))
                        renderHubChips()
                        showHubScreen(hubScreens.firstOrNull()?.id ?: "home")
                    }
                })
            }
            hubContent.addView(box, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            return
        }
        hubBlockedByUpdate = false
        hubBanner.text = "Forge Hub update v$latest available. Tap to install."
        hubBanner.visibility = View.VISIBLE
        hubBanner.setOnClickListener { downloadApk(absoluteRelayUrl(apk)) }
    }

    // =========================================================================
    // Tab 3: Isolated Terminal Component
    // =========================================================================

    private fun buildTerminalTab(): FrameLayout {
        val outer = FrameLayout(this).apply {
            background = UiPolish.screenGradient(this@MainActivity)
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(36), dp(16), dp(8))
        }

        youTerminalBack = TextView(this).apply {
            text = "← You"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ACCENT)
            setPadding(0, 0, dp(12), 0)
            visibility = View.GONE
            isClickable = true
            setOnClickListener { showYouSub(YouSub.HOME) }
        }
        topBar.addView(youTerminalBack)
        topBar.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = "Terminal"
                textSize = 20f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(STRONG)
            })
            addView(TextView(context).apply {
                text = "Relay SSH / WSL"
                textSize = 12f
                setTextColor(MUTED)
            })
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

        val pasteBtn = TextView(this).apply {
            text = "Paste"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#FF111111"))
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = createGlassDrawable(ACCENT, dp(14))
            isClickable = true
            setOnClickListener { openTerminalPasteSheet() }
        }
        var termMenuAnchor: View? = null
        termMenuAnchor = UiPolish.iconCircle(this, R.drawable.ic_more, MUTED, "Terminal options") {
            val anchor = termMenuAnchor ?: return@iconCircle
            PopupMenu(this@MainActivity, anchor).apply {
                menu.add("Scroll to top")
                menu.add("Scroll to bottom")
                menu.add("Reload")
                setOnMenuItemClickListener { item ->
                    when (item.title.toString()) {
                        "Scroll to top" -> scrollTerminal("top")
                        "Scroll to bottom" -> scrollTerminal("bottom")
                        "Reload" -> terminalWebView?.reload()
                    }
                    true
                }
                show()
            }
        }
        topBar.addView(pasteBtn, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { rightMargin = dp(8) })
        topBar.addView(termMenuAnchor, LinearLayout.LayoutParams(dp(48), dp(48)))

        container.addView(topBar)

        // Webview container
        val frame = FrameLayout(this)
        terminalLoadBar = View(this).apply {
            setBackgroundColor(ACCENT)
            pivotX = 0f
            scaleX = 0f
            alpha = 0f
        }
        frame.addView(terminalLoadBar, FrameLayout.LayoutParams(MATCH_PARENT, dp(2), Gravity.TOP))

        // Safe Paste Sheet (Overlay)
        terminalPasteSheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = createGlassDrawable(Color.parseColor("#E6121216"), dp(16), ACCENT)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            visibility = View.GONE

            addView(TextView(context).apply {
                text = "Terminal Paste Safety"
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(STRONG)
            })
            addView(TextView(context).apply {
                text = "Pasting lands here before execution to prevent accidental execution."
                textSize = 12f
                setTextColor(MUTED)
                setPadding(0, dp(4), 0, dp(8))
            })

            terminalPasteInput = EditText(context).apply {
                hint = "Review or edit snippet here..."
                setHintTextColor(MUTED)
                setTextColor(STRONG)
                textSize = 13f
                minLines = 3
                background = createGlassDrawable(Color.parseColor("#FF0B0B0C"), dp(8), LINE)
                setPadding(dp(10), dp(10), dp(10), dp(10))
            }
            addView(terminalPasteInput, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

            val buttons = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(12), 0, 0)
                addView(TextView(context).apply {
                    text = "Send to Shell"
                    textSize = 14f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.parseColor("#FF111111"))
                    background = GradientDrawable().apply { setColor(ACCENT); cornerRadius = dp(16).toFloat() }
                    setPadding(dp(16), dp(10), dp(16), dp(10))
                    gravity = Gravity.CENTER
                    setOnClickListener { sendPasteToTerminal() }
                }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { rightMargin = dp(8) })

                addView(TextView(context).apply {
                    text = "Cancel"
                    textSize = 14f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(STRONG)
                    background = createGlassDrawable(CARD_BG, dp(16), LINE)
                    setPadding(dp(16), dp(10), dp(16), dp(10))
                    gravity = Gravity.CENTER
                    setOnClickListener { terminalPasteSheet.visibility = View.GONE }
                }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            }
            addView(buttons)
        }

        val sheetParams = FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM).apply {
            leftMargin = dp(16)
            rightMargin = dp(16)
            bottomMargin = dp(16)
        }

        frame.addView(terminalPasteSheet, sheetParams)
        container.addView(frame, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        outer.addView(container, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        return outer
    }

    private fun setupTerminalWebView() {
        val wv = newGenericWebView(Screen("term", "Terminal", "terminal", "/app/term/"))
        terminalWebView = wv
        (terminalContainer.getChildAt(0) as LinearLayout).getChildAt(1).let { frame ->
            (frame as FrameLayout).addView(wv, 0)
        }
        wv.loadUrl(absoluteRelayUrl("/app/term/"))
    }

    private fun openTerminalPasteSheet() {
        terminalPasteSheet.visibility = View.VISIBLE
        terminalPasteInput.setText("")
        terminalPasteInput.requestFocus()
    }

    private fun sendPasteToTerminal() {
        var text = terminalPasteInput.text.toString()
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
        terminalPasteSheet.visibility = View.GONE
    }

    private fun scrollTerminal(where: String) {
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

    private fun buildSettingsTab(): ScrollView {
        val scroll = ScrollView(this).apply {
            background = UiPolish.screenGradient(this@MainActivity)
            isFillViewport = true
        }

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(40), dp(20), dp(32))
        }

        box.addView(TextView(this).apply {
            text = "WHITEDEVIL"
            textSize = 10f
            letterSpacing = 0.14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ACCENT)
        })
        box.addView(TextView(this).apply {
            text = "Settings"
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(STRONG)
            setPadding(0, dp(4), 0, dp(6))
        })
        box.addView(TextView(this).apply {
            text = "Credentials are encrypted on-device via Android Jetpack Security."
            textSize = 12.5f
            setTextColor(MUTED)
            setPadding(0, 0, 0, dp(16))
        })

        val connectionCard = UiPolish.sectionCard(this)
        connectionCard.addView(TextView(this).apply {
            text = "Connection health"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(STRONG)
            setPadding(0, 0, 0, dp(8))
        })
        settingsConnectionSummary = TextView(this).apply {
            text = "Run a quick check after saving credentials."
            textSize = 13f
            setTextColor(MUTED)
            setLineSpacing(0f, 1.2f)
        }
        connectionCard.addView(settingsConnectionSummary)
        box.addView(connectionCard, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(14) })

        fun sectionHeader(txt: String) = TextView(this).apply {
            text = txt
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(STRONG)
            setPadding(0, 0, 0, dp(10))
        }

        fun fieldIn(parent: LinearLayout, label: String, key: String, def: String = "", secret: Boolean = false): EditText {
            parent.addView(TextView(this).apply {
                text = label
                textSize = 11.5f
                setTextColor(MUTED)
                setPadding(0, dp(4), 0, dp(4))
            })
            val ed = EditText(this).apply {
                setText(prefs.getString(key, def))
                setTextColor(STRONG)
                textSize = 14f
                background = createGlassDrawable(Color.parseColor("#28000000"), dp(12), LINE)
                setPadding(dp(14), dp(12), dp(14), dp(12))
                inputType = if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            }
            parent.addView(ed)
            return ed
        }

        fun sectionCard(title: String, block: LinearLayout.() -> Unit): LinearLayout {
            val card = UiPolish.sectionCard(this)
            card.addView(sectionHeader(title))
            card.block()
            box.addView(card, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(14) })
            return card
        }

        val veniceCard = sectionCard("Venice AI Agent") {
            val fVeniceKey = fieldIn(this, "Venice API key", SettingsManager.KEY_VENICE_API_KEY, secret = true)
            val fPrompt = fieldIn(this, "System prompt", SettingsManager.KEY_VENICE_SYSTEM_PROMPT, SettingsManager.DEFAULT_SYSTEM_PROMPT)
            val webSearchRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(12), 0, 0)
                addView(TextView(context).apply {
                    text = "Venice web search"
                    textSize = 13.5f
                    setTextColor(STRONG)
                }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            }
            val webSearchSwitch = Switch(this@MainActivity).apply {
                isChecked = prefs.getBoolean(SettingsManager.KEY_VENICE_WEB_SEARCH, false)
            }
            webSearchRow.addView(webSearchSwitch)
            addView(webSearchRow)
            tag = listOf(fVeniceKey, fPrompt, webSearchSwitch)
        }
        @Suppress("UNCHECKED_CAST")
        val veniceTags = veniceCard.tag as List<Any>
        val fVeniceKey = veniceTags[0] as EditText
        val fPrompt = veniceTags[1] as EditText
        val webSearchSwitch = veniceTags[2] as Switch

        val relayCard = sectionCard("Relay & Forge Hub") {
            tag = listOf(
                fieldIn(this, "Relay base URL", SettingsManager.KEY_RELAY_URL, SettingsManager.DEFAULT_RELAY_URL),
                fieldIn(this, "Relay user", SettingsManager.KEY_RELAY_USER, SettingsManager.DEFAULT_RELAY_USER),
                fieldIn(this, "Relay password", SettingsManager.KEY_RELAY_PASS, secret = true),
            )
        }
        @Suppress("UNCHECKED_CAST")
        val relayTags = relayCard.tag as List<EditText>
        val fRelayUrl = relayTags[0]
        val fRelayUser = relayTags[1]
        val fRelayPass = relayTags[2]

        val laptopCard = sectionCard("Laptop SSH tunnel") {
            tag = listOf(
                fieldIn(this, "Laptop user", SettingsManager.KEY_LAPTOP_USER, SettingsManager.DEFAULT_LAPTOP_USER),
                fieldIn(this, "Laptop password", SettingsManager.KEY_LAPTOP_PASS, secret = true),
            )
        }
        @Suppress("UNCHECKED_CAST")
        val laptopTags = laptopCard.tag as List<EditText>
        val fLaptopUser = laptopTags[0]
        val fLaptopPass = laptopTags[1]

        val testBtn = TextView(this).apply {
            text = "Test connections"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(STRONG)
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(12), dp(20), dp(12))
            background = createGlassDrawable(CARD_BG, dp(16), LINE)
            isClickable = true
            setOnClickListener {
                settingsConnectionSummary.text = "Testing…"
                scope.launch {
                    val snap = withContext(Dispatchers.IO) {
                        ConnectionHealth.evaluate(
                            veniceKey = fVeniceKey.text.toString().trim(),
                            relayUrl = fRelayUrl.text.toString().trim(),
                            relayUser = fRelayUser.text.toString().trim(),
                            relayPass = fRelayPass.text.toString(),
                            laptopUser = fLaptopUser.text.toString().trim(),
                            laptopPass = fLaptopPass.text.toString(),
                        )
                    }
                    settingsConnectionSummary.text = snap.multiline()
                }
            }
        }
        box.addView(testBtn, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(12) })

        val saveBtn = TextView(this).apply {
            text = "Save Settings"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#FF111111"))
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(14), dp(20), dp(14))
            background = GradientDrawable().apply {
                setColor(ACCENT)
                cornerRadius = dp(24).toFloat()
            }
            isClickable = true
            setOnClickListener {
                prefs.edit()
                    .putString(SettingsManager.KEY_VENICE_API_KEY, fVeniceKey.text.toString().trim())
                    .putString(SettingsManager.KEY_VENICE_SYSTEM_PROMPT, fPrompt.text.toString().trim())
                    .putBoolean(SettingsManager.KEY_VENICE_WEB_SEARCH, webSearchSwitch.isChecked)
                    .putString(SettingsManager.KEY_RELAY_URL, fRelayUrl.text.toString().trim().trimEnd('/'))
                    .putString(SettingsManager.KEY_RELAY_USER, fRelayUser.text.toString().trim())
                    .putString(SettingsManager.KEY_RELAY_PASS, fRelayPass.text.toString())
                    .putString(SettingsManager.KEY_LAPTOP_USER, fLaptopUser.text.toString().trim())
                    .putString(SettingsManager.KEY_LAPTOP_PASS, fLaptopPass.text.toString())
                    .apply()

                updateAgentSetupState()
                UiFeedback.snackbar(root, "Settings saved")

                hubWebViews.values.forEach { hubContent.removeView(it); it.destroy() }
                hubWebViews.clear()
                authTries.clear()
                terminalWebView?.reload()
                loadHubManifest()
            }
        }

        val btnParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            topMargin = dp(24)
            bottomMargin = dp(16)
        }
        box.addView(saveBtn, btnParams)

        scroll.addView(box)
        return scroll
    }

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
                val bar = if (term) terminalLoadBar else hubLoadBar
                bar.animate().cancel()
                if (p < 100) {
                    bar.alpha = 1f
                    bar.animate().scaleX(p / 100f).setDuration(150).start()
                } else {
                    bar.animate().scaleX(1f).alpha(0f).setDuration(250).withEndAction { bar.scaleX = 0f }.start()
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
