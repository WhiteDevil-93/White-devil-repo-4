package com.whitedevil

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
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
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.whitedevil.agent.Agent
import com.whitedevil.agent.AgentEvent
import com.whitedevil.agent.ToolBox
import com.whitedevil.agent.VeniceClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class MainActivity : Activity() {

    enum class Tab { AGENT, FORGE_HUB, TERMINAL, SETTINGS }

    private data class Screen(val id: String, val title: String, val icon: String, val url: String)

    private val prefs by lazy { SettingsManager.getPrefs(this) }
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    private lateinit var root: LinearLayout
    private lateinit var tabContentContainer: FrameLayout
    private lateinit var bottomNavBar: LinearLayout
    private lateinit var bottomNavRow: LinearLayout

    // Containers for the 4 tabs
    private lateinit var agentContainer: LinearLayout
    private lateinit var forgeHubContainer: FrameLayout
    private lateinit var terminalContainer: FrameLayout
    private lateinit var settingsContainer: ScrollView

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
    private lateinit var agentMessagesLayout: LinearLayout
    private lateinit var agentScrollView: ScrollView
    private lateinit var agentInput: EditText
    private lateinit var agentSendBtn: TextView
    private lateinit var agentModelSpinner: Spinner
    private lateinit var agentProgress: ProgressBar
    private var currentAgentJob: Job? = null

    // Current navigation state
    private var activeTab: Tab = Tab.AGENT
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var fullscreenView: View? = null
    private var apkDownloadId = -1L

    private val relayBase get() = prefs.getString(SettingsManager.KEY_RELAY_URL, SettingsManager.DEFAULT_RELAY_URL)!!.trimEnd('/')

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()

        val initTab = when (intent?.data?.getQueryParameter("tab")) {
            "forge", "hub" -> Tab.FORGE_HUB
            "terminal", "term" -> Tab.TERMINAL
            "settings" -> Tab.SETTINGS
            else -> Tab.AGENT
        }
        selectTab(initTab)

        if (prefs.getString(SettingsManager.KEY_RELAY_PASS, "").isNullOrEmpty()) {
            // First run hint or open settings
        } else {
            loadHubManifest()
        }

        registerReceiver(downloadDone, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), RECEIVER_EXPORTED)
    }

    override fun onResume() {
        super.onResume()
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
        when (intent.data?.getQueryParameter("tab")) {
            "agent" -> selectTab(Tab.AGENT)
            "forge", "hub" -> {
                selectTab(Tab.FORGE_HUB)
                intent.data?.getQueryParameter("screen")?.let { showHubScreen(it) }
            }
            "terminal", "term" -> selectTab(Tab.TERMINAL)
            "settings" -> selectTab(Tab.SETTINGS)
        }
    }

    // =========================================================================
    // UI Layout Construction
    // =========================================================================

    private fun buildUi() {
        @Suppress("DEPRECATION")
        window.setDecorFitsSystemWindows(false)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
        }

        tabContentContainer = FrameLayout(this)

        // 1. Build Agent UI
        agentContainer = buildAgentTab()
        tabContentContainer.addView(agentContainer, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        // 2. Build Forge Hub UI
        forgeHubContainer = buildForgeHubTab()
        tabContentContainer.addView(forgeHubContainer, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        // 3. Build Terminal UI
        terminalContainer = buildTerminalTab()
        tabContentContainer.addView(terminalContainer, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        // 4. Build Settings UI
        settingsContainer = buildSettingsTab()
        tabContentContainer.addView(settingsContainer, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        // Bottom Navigation Bar with dark glass style
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

        root.addView(tabContentContainer, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        root.addView(bottomNavBar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        root.setOnApplyWindowInsetsListener { v, insets ->
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

    private fun renderBottomNav() {
        bottomNavRow.removeAllViews()
        bottomNavRow.addView(navTabItem(R.drawable.ic_venice, "Agent", activeTab == Tab.AGENT) { selectTab(Tab.AGENT) })
        bottomNavRow.addView(navTabItem(R.drawable.ic_home, "Forge Hub", activeTab == Tab.FORGE_HUB) { selectTab(Tab.FORGE_HUB) })
        bottomNavRow.addView(navTabItem(R.drawable.ic_terminal, "Terminal", activeTab == Tab.TERMINAL) { selectTab(Tab.TERMINAL) })
        bottomNavRow.addView(navTabItem(R.drawable.ic_settings, "Settings", activeTab == Tab.SETTINGS) { selectTab(Tab.SETTINGS) })
    }

    private fun navTabItem(icon: Int, label: String, active: Boolean, onClick: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        val tint = if (active) ACCENT else MUTED

        val pill = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(if (active) PILL else Color.TRANSPARENT)
            }
            addView(ImageView(context).apply {
                setImageResource(icon)
                imageTintList = ColorStateList.valueOf(tint)
            }, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
        }

        addView(pill, LinearLayout.LayoutParams(dp(56), dp(32)))
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
        terminalContainer.visibility = if (tab == Tab.TERMINAL) View.VISIBLE else View.GONE
        settingsContainer.visibility = if (tab == Tab.SETTINGS) View.VISIBLE else View.GONE

        if (tab == Tab.TERMINAL && terminalWebView == null) {
            setupTerminalWebView()
        }
        if (tab == Tab.FORGE_HUB && hubScreens.isEmpty()) {
            loadHubManifest()
        }
        renderBottomNav()
    }

    // =========================================================================
    // Tab 1: Native Venice Agent Tab
    // =========================================================================

    private fun buildAgentTab(): LinearLayout {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(dp(12), dp(40), dp(12), dp(8))
        }

        // Header bar with Title, Model Spinner, and Reset button
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(10))
        }

        val title = TextView(this).apply {
            text = "Venice Agent"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(STRONG)
        }

        val models = listOf(
            "zai-org-glm-5-2",
            "zai-org-glm-5",
            "venice-uncensored",
            "venice-uncensored-1-2",
            "kimi-k2-6",
            "claude-opus-4-8"
        )
        agentModelSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, models)
            val savedModel = prefs.getString(SettingsManager.KEY_VENICE_MODEL, SettingsManager.DEFAULT_MODEL)
            val idx = models.indexOf(savedModel).coerceAtLeast(0)
            setSelection(idx)
            background = createGlassDrawable(CARD_BG, dp(8), LINE)
        }

        val resetBtn = TextView(this).apply {
            text = "Clear"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ACCENT)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = createGlassDrawable(PILL, dp(14), LINE)
            isClickable = true
            setOnClickListener { resetAgentChat() }
        }

        val promptBtn = TextView(this).apply {
            text = "Prompt"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(STRONG)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = createGlassDrawable(CARD_BG, dp(14), LINE)
            isClickable = true
            setOnClickListener { showSystemPromptDialog() }
        }

        header.addView(title, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        header.addView(agentModelSpinner, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { rightMargin = dp(6) })
        header.addView(promptBtn, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { rightMargin = dp(6) })
        header.addView(resetBtn, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))

        layout.addView(header)

        // Progress indicator
        agentProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            visibility = View.GONE
        }
        layout.addView(agentProgress, LinearLayout.LayoutParams(MATCH_PARENT, dp(4)))

        // Scrollable Chat Messages
        agentScrollView = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        agentMessagesLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(8))
        }
        agentScrollView.addView(agentMessagesLayout, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        layout.addView(agentScrollView, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        // Initial welcome message
        addMessageBubble(
            "Agent Ready",
            "Venice Agent is running natively on device. It has access to local workspace files and can control Forge Hub, check renders, and run laptop tasks via your relay.",
            ROLE_VENICE
        )

        // Bottom Input Row
        val inputBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(8), dp(4), dp(4))
            background = createGlassDrawable(BAR_GLASS, dp(24), LINE)
        }

        agentInput = EditText(this).apply {
            hint = "Ask Venice or give a task…"
            setHintTextColor(MUTED)
            setTextColor(STRONG)
            textSize = 14f
            background = null
            setPadding(dp(16), dp(10), dp(12), dp(10))
            maxLines = 4
        }

        agentSendBtn = TextView(this).apply {
            text = "Send"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#FF111111"))
            setPadding(dp(18), dp(10), dp(18), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(ACCENT)
            }
            isClickable = true
            setOnClickListener { sendAgentMessage() }
        }

        inputBar.addView(agentInput, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        inputBar.addView(agentSendBtn, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { rightMargin = dp(4) })
        layout.addView(inputBar)

        return layout
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
        agentMessagesLayout.removeAllViews()
        addMessageBubble("Agent Reset", "Chat context cleared. Ready for next task.", ROLE_VENICE)
    }

    private fun sendAgentMessage() {
        val text = agentInput.text.toString().trim()
        if (text.isEmpty()) return
        val apiKey = prefs.getString(SettingsManager.KEY_VENICE_API_KEY, "")?.trim() ?: ""
        if (apiKey.isEmpty()) {
            Toast.makeText(this, "Venice API key not set! Please configure it in Settings tab.", Toast.LENGTH_LONG).show()
            selectTab(Tab.SETTINGS)
            return
        }

        agentInput.setText("")
        val selectedModel = agentModelSpinner.selectedItem?.toString() ?: SettingsManager.DEFAULT_MODEL
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
        agentSendBtn.isEnabled = false

        val currentClient = VeniceClient(apiKey = apiKey)
        currentAgentJob = scope.launch {
            try {
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
                        agent.send(text)
                    }
                }
            } catch (e: Exception) {
                addMessageBubble("Error", e.message ?: "Unknown error running Venice agent", ROLE_ERROR)
            } finally {
                agentProgress.visibility = View.GONE
                agentSendBtn.isEnabled = true
            }
        }
    }

    private fun addMessageBubble(sender: String, message: String, role: Int) {
        val bubble = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val bg = when (role) {
                ROLE_USER -> createGlassDrawable(Color.parseColor("#334C3D28"), dp(16), ACCENT)
                ROLE_VENICE -> createGlassDrawable(CARD_BG, dp(16), LINE)
                ROLE_TOOL_CALL -> createGlassDrawable(Color.parseColor("#331F2E3D"), dp(12), Color.parseColor("#FF5C8BB5"))
                ROLE_TOOL_OUTPUT -> createGlassDrawable(Color.parseColor("#291D1D24"), dp(12), LINE)
                else -> createGlassDrawable(Color.parseColor("#44331111"), dp(12), Color.parseColor("#FFCC5555"))
            }
            background = bg
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }

        val senderView = TextView(this).apply {
            this.text = sender
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(
                when (role) {
                    ROLE_USER -> ACCENT
                    ROLE_VENICE -> STRONG
                    ROLE_TOOL_CALL -> Color.parseColor("#FF82B6E8")
                    ROLE_TOOL_OUTPUT -> MUTED
                    else -> Color.parseColor("#FFFF6B6B")
                }
            )
            setPadding(0, 0, 0, dp(4))
        }

        val contentView = TextView(this).apply {
            this.text = message
            textSize = 13.5f
            setTextColor(if (role == ROLE_TOOL_OUTPUT) MUTED else FG)
            setTextIsSelectable(true)
            if (role == ROLE_TOOL_CALL || role == ROLE_TOOL_OUTPUT) {
                typeface = Typeface.MONOSPACE
                textSize = 11.5f
            }
        }

        bubble.addView(senderView)
        bubble.addView(contentView)

        val params = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            topMargin = dp(6)
            bottomMargin = dp(6)
            if (role == ROLE_USER) {
                leftMargin = dp(32)
            } else if (role == ROLE_TOOL_CALL || role == ROLE_TOOL_OUTPUT) {
                leftMargin = dp(16)
                rightMargin = dp(16)
            }
        }

        agentMessagesLayout.addView(bubble, params)
        agentScrollView.post { agentScrollView.fullScroll(View.FOCUS_DOWN) }
    }

    // =========================================================================
    // Tab 2: Isolated Forge Hub Component
    // =========================================================================

    private fun buildForgeHubTab(): FrameLayout {
        val outer = FrameLayout(this).apply {
            setBackgroundColor(BG)
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
        }

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
            setPadding(dp(8), dp(40), dp(8), dp(8))
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
                setOnLongClickListener {
                    hubWebViews[s.id]?.reload()
                    Toast.makeText(this@MainActivity, "Reloading ${s.title}", Toast.LENGTH_SHORT).show()
                    true
                }
            }
            val p = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                rightMargin = dp(8)
            }
            hubScreenChips.addView(chip, p)
        }
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
                main.post { applyHubManifest(body, fromCache = false) }
            } catch (e: Exception) {
                main.post {
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
    }

    private fun showHubOffline(msg: String?) {
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
            setBackgroundColor(BG)
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
        }

        // Top bar with controls: Paste Safety, Top, Bottom, Reload
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(38), dp(12), dp(8))
            background = createGlassDrawable(BAR_GLASS, border = LINE)
        }

        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = "Relay SSH / WSL"
                textSize = 11f
                setTextColor(MUTED)
            })
            addView(TextView(context).apply {
                text = "Terminal"
                textSize = 17f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(STRONG)
            })
        }
        topBar.addView(titleCol, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

        fun termBtn(text: String, onClick: () -> Unit) = TextView(this).apply {
            this.text = text
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(STRONG)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = createGlassDrawable(CARD_BG, dp(12), LINE)
            isClickable = true
            setOnClickListener { onClick() }
        }

        topBar.addView(termBtn("Paste") { openTerminalPasteSheet() }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { rightMargin = dp(6) })
        topBar.addView(termBtn("Top") { scrollTerminal("top") }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { rightMargin = dp(6) })
        topBar.addView(termBtn("Bottom") { scrollTerminal("bottom") }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { rightMargin = dp(6) })
        topBar.addView(termBtn("Reload") { terminalWebView?.reload() }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))

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
            setBackgroundColor(BG)
            isFillViewport = true
        }

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(40), dp(20), dp(32))
        }

        val title = TextView(this).apply {
            text = "WhiteDevil Settings"
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(STRONG)
            setPadding(0, 0, 0, dp(4))
        }
        val sub = TextView(this).apply {
            text = "Credentials are encrypted on-device via Android Jetpack Security."
            textSize = 12f
            setTextColor(MUTED)
            setPadding(0, 0, 0, dp(24))
        }
        box.addView(title)
        box.addView(sub)

        fun sectionHeader(txt: String) = TextView(this).apply {
            text = txt
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ACCENT)
            setPadding(0, dp(16), 0, dp(8))
        }

        fun field(label: String, key: String, def: String = "", secret: Boolean = false): EditText {
            val lbl = TextView(this).apply {
                text = label
                textSize = 12f
                setTextColor(MUTED)
                setPadding(0, dp(6), 0, dp(4))
            }
            val ed = EditText(this).apply {
                setText(prefs.getString(key, def))
                setTextColor(STRONG)
                textSize = 14f
                background = createGlassDrawable(CARD_BG, dp(8), LINE)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                inputType = if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            }
            box.addView(lbl)
            box.addView(ed)
            return ed
        }

        box.addView(sectionHeader("Venice AI Agent Config"))
        val fVeniceKey = field("VENICE_API_KEY", SettingsManager.KEY_VENICE_API_KEY, secret = true)
        val fPrompt = field("System Prompt", SettingsManager.KEY_VENICE_SYSTEM_PROMPT, SettingsManager.DEFAULT_SYSTEM_PROMPT)

        val webSearchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(12))
            addView(TextView(context).apply {
                text = "Enable Venice Web Search"
                textSize = 13.5f
                setTextColor(STRONG)
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
        val webSearchSwitch = Switch(this).apply {
            isChecked = prefs.getBoolean(SettingsManager.KEY_VENICE_WEB_SEARCH, false)
        }
        webSearchRow.addView(webSearchSwitch)
        box.addView(webSearchRow)

        box.addView(sectionHeader("Remote Relay & Forge Hub Config"))
        val fRelayUrl = field("Relay Base URL", SettingsManager.KEY_RELAY_URL, SettingsManager.DEFAULT_RELAY_URL)
        val fRelayUser = field("Relay User", SettingsManager.KEY_RELAY_USER, SettingsManager.DEFAULT_RELAY_USER)
        val fRelayPass = field("Relay Password", SettingsManager.KEY_RELAY_PASS, secret = true)

        box.addView(sectionHeader("Laptop SSH Tunnel Config"))
        val fLaptopUser = field("Laptop User", SettingsManager.KEY_LAPTOP_USER, SettingsManager.DEFAULT_LAPTOP_USER)
        val fLaptopPass = field("Laptop Password", SettingsManager.KEY_LAPTOP_PASS, secret = true)

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

                Toast.makeText(this@MainActivity, "Settings saved securely.", Toast.LENGTH_SHORT).show()

                // Invalidate hub caches & reload
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
