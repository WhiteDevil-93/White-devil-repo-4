package com.anon3.forgehub

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.content.res.ColorStateList
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
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private data class Screen(val id: String, val title: String, val icon: String, val url: String)

    private val prefs by lazy { getSharedPreferences("forgehub", MODE_PRIVATE) }
    private val main = Handler(Looper.getMainLooper())

    private lateinit var root: LinearLayout
    private lateinit var content: FrameLayout
    private lateinit var tabRow: LinearLayout
    private lateinit var nav: LinearLayout
    private lateinit var banner: TextView
    private lateinit var loadBar: View

    private var screens: List<Screen> = emptyList()
    private val webViews = HashMap<String, WebView>()
    private val authTries = HashMap<String, Int>()
    private var current: String? = null
    private var pendingScreen: String? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var fullscreenView: View? = null
    private var apkDownloadId = -1L
    private var webRev = 0
    private var blockedByUpdate = false

    private val base get() = prefs.getString("base", DEFAULT_BASE)!!.trimEnd('/')

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        pendingScreen = screenFromIntent(intent)
        if (prefs.getString("wan_pass", "").isNullOrEmpty()) showSettings(firstRun = true) else loadManifest()
        registerReceiver(downloadDone, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), RECEIVER_EXPORTED)
    }

    override fun onResume() {
        super.onResume()
        if (blockedByUpdate) return
        if (!prefs.getString("wan_pass", "").isNullOrEmpty()) {
            loadManifest()
            val wv = webViews[current]
            if (wv != null && current != "term") wv.reload()
        }
    }

    override fun onDestroy() {
        unregisterReceiver(downloadDone)
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        screenFromIntent(intent)?.let { id -> if (screens.any { it.id == id }) show(id) else pendingScreen = id }
    }

    private fun screenFromIntent(i: Intent?): String? =
        i?.data?.takeIf { it.scheme == "forgehub" }?.let { it.getQueryParameter("screen") ?: it.host }

    // ---------- UI ----------

    private fun buildUi() {
        window.setDecorFitsSystemWindows(false)
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BG) }
        banner = TextView(this).apply {
            background = GradientDrawable().apply { setColor(STRONG) }
            setTextColor(Color.parseColor("#FF111111")); typeface = Typeface.DEFAULT_BOLD; textSize = 14f
            setPadding(dp(18), dp(12), dp(18), dp(12)); visibility = View.GONE
        }
        content = FrameLayout(this)
        loadBar = View(this).apply { setBackgroundColor(ACCENT); pivotX = 0f; scaleX = 0f; alpha = 0f }
        content.addView(loadBar, FrameLayout.LayoutParams(MATCH_PARENT, dp(2), Gravity.TOP))
        tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(10), dp(4), dp(8))
        }
        nav = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(BAR)
            addView(View(context).apply { setBackgroundColor(LINE) }, LinearLayout.LayoutParams(MATCH_PARENT, 1))
            addView(tabRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        root.addView(banner, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(content, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        root.addView(nav, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            val ime = insets.getInsets(WindowInsets.Type.ime())
            val typing = ime.bottom > bars.bottom
            v.setPadding(bars.left, bars.top, bars.right, if (typing) ime.bottom else 0)
            nav.visibility = if (typing) View.GONE else View.VISIBLE
            nav.setPadding(0, 0, 0, bars.bottom)
            WindowInsets.CONSUMED
        }
        setContentView(root)
    }

    private fun renderTabs() {
        tabRow.removeAllViews()
        val (inBar, extra) = screens.withIndex().partition { it.index < MAX_TABS }
        for (s in inBar.map { it.value }) {
            tabRow.addView(navItem(iconRes(s.icon), s.title, s.id == current) { show(s.id) }.apply {
                setOnLongClickListener { webViews[s.id]?.reload(); toast("Reloading ${s.title}"); true }
            })
        }
        val extraActive = extra.any { it.value.id == current }
        tabRow.addView(navItem(R.drawable.ic_more, "More", extraActive) { showMenu() })
    }

    private fun navItem(icon: Int, label: String, active: Boolean, onClick: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        val tint = if (active) STRONG else MUTED
        val pill = FrameLayout(context).apply {
            background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(if (active) PILL else Color.TRANSPARENT) }
            addView(ImageView(context).apply {
                setImageResource(icon); imageTintList = ColorStateList.valueOf(tint)
            }, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
        }
        addView(pill, LinearLayout.LayoutParams(dp(60), dp(32)))
        addView(TextView(context).apply {
            text = label; textSize = 12f; gravity = Gravity.CENTER; setTextColor(if (active) FG else MUTED)
            typeface = if (active) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            maxLines = 1; setPadding(0, dp(4), 0, 0)
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        isClickable = true
        background = RippleDrawable(ColorStateList.valueOf(PILL), null, null)
        setOnClickListener { onClick() }
    }

    private fun iconRes(icon: String) = when (icon) {
        "home", "status" -> R.drawable.ic_home; "movie" -> R.drawable.ic_movie; "cloud" -> R.drawable.ic_cloud
        "folder" -> R.drawable.ic_folder; "terminal" -> R.drawable.ic_terminal; "bot", "ai" -> R.drawable.ic_bot
        "hypno", "spiral" -> R.drawable.ic_spiral; "bolt", "thunder" -> R.drawable.ic_bolt
        "pen", "write", "prompt" -> R.drawable.ic_pen
        "venice", "sparkle", "chat" -> R.drawable.ic_venice
        else -> R.drawable.ic_dot
    }

    private fun show(id: String) {
        if (blockedByUpdate) return
        val s = screens.firstOrNull { it.id == id } ?: return
        if (id == current && webViews[id]?.visibility == View.VISIBLE) {
            if (id != "term") webViews[id]?.reload()
            return
        }
        current = id
        prefs.edit().putString("last_screen", id).apply()
        val wv = webViews.getOrPut(id) { newWebView(s).also { it.loadUrl(absolute(s.url)); content.addView(it, 0) } }
        webViews.values.forEach { if (it !== wv) it.visibility = View.GONE }
        wv.alpha = 0f; wv.visibility = View.VISIBLE
        wv.animate().alpha(1f).setDuration(160).start()
        renderTabs()
    }

    private fun absolute(url: String) = if (url.startsWith("http")) url else base + url

    // ---------- Manifest ----------

    private fun loadManifest() {
        prefs.getString("manifest", null)?.let { applyManifest(it, fromCache = true) }
        thread {
            try {
                val conn = URL("$base/api/manifest").openConnection() as HttpURLConnection
                conn.setRequestProperty("Authorization", basic("wan"))
                conn.connectTimeout = 15000; conn.readTimeout = 20000
                val code = conn.responseCode
                if (code == 401) {
                    main.post { toast("Login rejected. Check the password."); showSettings(firstRun = false) }
                    return@thread
                }
                val body = conn.inputStream.bufferedReader().readText()
                prefs.edit().putString("manifest", body).apply()
                main.post { applyManifest(body, fromCache = false) }
            } catch (e: Exception) {
                main.post { if (screens.isEmpty()) showOffline(e.message) else toast("Relay unreachable, showing cached tabs") }
            }
        }
    }

    private fun applyManifest(body: String, fromCache: Boolean) {
        val json = JSONObject(body)
        val arr = json.getJSONArray("screens")
        val next = (0 until arr.length()).map { arr.getJSONObject(it) }.map {
            Screen(it.getString("id"), it.getString("title"), it.optString("icon"), it.getString("url"))
        }
        val rev = json.optInt("web_rev", json.optInt("apk_version", 0))
        val changedUrls = next.filter { n -> screens.any { it.id == n.id && it.url != n.url } }.map { it.id }
        val drop = (webViews.keys - next.map { it.id }.toSet() + changedUrls).toMutableSet()
        if (!fromCache && webRev != 0 && rev != webRev) drop.addAll(webViews.keys)
        drop.forEach { id ->
            webViews.remove(id)?.let { content.removeView(it); it.destroy() }
        }
        if (!fromCache) webRev = rev
        screens = next
        val want = pendingScreen ?: current ?: prefs.getString("last_screen", null)
        pendingScreen = null
        if (!fromCache) checkUpdate(json)
        if (!blockedByUpdate) show(screens.firstOrNull { it.id == want }?.id ?: screens.first().id)
    }

    private fun showOffline(msg: String?) {
        content.removeAllViews()
        content.addView(loadBar, FrameLayout.LayoutParams(MATCH_PARENT, dp(2), Gravity.TOP))
        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(32), dp(32), dp(32), dp(32))
            addView(TextView(context).apply { text = "Can't reach the relay"; textSize = 22f; typeface = Typeface.DEFAULT_BOLD; setTextColor(FG); gravity = Gravity.CENTER })
            addView(TextView(context).apply { text = "$base\n${msg ?: ""}"; textSize = 13f; setTextColor(MUTED); gravity = Gravity.CENTER; setPadding(0, dp(10), 0, dp(24)) })
            addView(TextView(context).apply {
                text = "Try again"; textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.parseColor("#FF111111"))
                setPadding(dp(28), dp(12), dp(28), dp(12))
                background = GradientDrawable().apply { setColor(STRONG); cornerRadius = dp(24).toFloat() }
                setOnClickListener { loadManifest() }
            })
        }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    // ---------- Update ----------

    private fun checkUpdate(json: JSONObject) {
        val latest = json.optInt("apk_version", 0)
        val force = json.optBoolean("force_update", false)
        if (latest <= BuildConfig.VERSION_CODE) {
            blockedByUpdate = false
            banner.visibility = View.GONE
            nav.visibility = View.VISIBLE
            return
        }
        val apk = json.optString("apk_url", "/app/forgehub.apk")
        if (force) {
            showForceUpdate(latest, apk)
            return
        }
        blockedByUpdate = false
        nav.visibility = View.VISIBLE
        banner.text = "App update available (v$latest). Tap to install."
        banner.visibility = View.VISIBLE
        banner.setOnClickListener { downloadApk(absolute(apk)) }
    }

    private fun showForceUpdate(latest: Int, apkUrl: String) {
        blockedByUpdate = true
        banner.visibility = View.GONE
        nav.visibility = View.GONE
        webViews.values.forEach { it.visibility = View.GONE }
        content.removeAllViews()
        content.addView(loadBar, FrameLayout.LayoutParams(MATCH_PARENT, dp(2), Gravity.TOP))
        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(dp(32), dp(32), dp(32), dp(32))
            addView(TextView(context).apply {
                text = "Update required"
                textSize = 26f; typeface = Typeface.DEFAULT_BOLD; setTextColor(FG); gravity = Gravity.CENTER
            })
            addView(TextView(context).apply {
                text = "This phone is on v${BuildConfig.VERSION_CODE}. Install v$latest to keep using Forge Hub. Venice Run, saved chats, and the new Terminal controls are in this build."
                textSize = 15f; setTextColor(MUTED); gravity = Gravity.CENTER
                setPadding(0, dp(12), 0, dp(28))
            })
            addView(TextView(context).apply {
                text = "Download and install v$latest"
                textSize = 16f; typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#FF111111"))
                setPadding(dp(28), dp(14), dp(28), dp(14))
                background = GradientDrawable().apply { setColor(STRONG); cornerRadius = dp(24).toFloat() }
                setOnClickListener { downloadApk(absolute(apkUrl)) }
            })
        }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    private fun downloadApk(url: String) {
        val req = DownloadManager.Request(Uri.parse(url))
            .addRequestHeader("Authorization", basic("wan"))
            .setTitle("Forge Hub update")
            .setMimeType("application/vnd.android.package-archive")
            .setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, "forgehub-${System.currentTimeMillis()}.apk")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        apkDownloadId = (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
        toast("Downloading update…")
    }

    private val downloadDone = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val id = i.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            if (id != apkDownloadId) return
            val uri = (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).getUriForDownloadedFile(id) ?: return
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    // ---------- WebView ----------

    private fun isTerm(s: Screen) = s.id == "term" || s.url.contains("/term")

    private fun newWebView(screen: Screen): WebView = WebView(this).apply {
        setBackgroundColor(BG)
        val term = isTerm(screen)
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
        settings.userAgentString = settings.userAgentString + " ForgeHubApp/${BuildConfig.VERSION_CODE}"
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                val u = req.url
                if (u.host == Uri.parse(base).host) return false
                if (u.scheme == "forgehub") { screenFromIntent(Intent(Intent.ACTION_VIEW, u))?.let { show(it) }; return true }
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, u)) }
                return true
            }

            override fun onReceivedHttpAuthRequest(view: WebView, handler: HttpAuthHandler, host: String, realm: String) {
                val key = if (realm == "laptop") "laptop" else "wan"
                val n = (authTries[key] ?: 0) + 1
                authTries[key] = n
                if (n > 3) {
                    handler.cancel(); authTries[key] = 0
                    val have = !prefs.getString("${key}_pass", "").isNullOrEmpty()
                    if (have && (current == "venice" || current == "term" || current == "shotwriter")) {
                        toast("A page request was rejected. Your ${if (key == "laptop") "laptop" else "relay"} password was not changed.")
                        return
                    }
                    toast("${if (key == "laptop") "Laptop" else "Relay"} login rejected"); showSettings(firstRun = false)
                    return
                }
                handler.proceed(prefs.getString("${key}_user", "")!!, prefs.getString("${key}_pass", "")!!)
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
                if (view !== webViews[current]) return
                loadBar.animate().cancel()
                if (p < 100) { loadBar.alpha = 1f; loadBar.animate().scaleX(p / 100f).setDuration(150).start() }
                else loadBar.animate().scaleX(1f).alpha(0f).setDuration(250).withEndAction { loadBar.scaleX = 0f }.start()
            }

            override fun onShowFileChooser(w: WebView, cb: ValueCallback<Array<Uri>>, p: FileChooserParams): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = cb
                return try {
                    startActivityForResult(p.createIntent().putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true), REQ_FILE); true
                } catch (e: Exception) { fileCallback = null; false }
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
                toast("Can't download this link in the app")
                return@setDownloadListener
            }
            val name = URLUtil.guessFileName(url, disposition, mime)
            val realm = if (Uri.parse(url).path?.startsWith("/laptop") == true) "laptop" else "wan"
            val req = DownloadManager.Request(Uri.parse(url))
                .addRequestHeader("Authorization", basic(realm))
                .addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url) ?: "")
                .setTitle(name)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "ForgeHub/$name")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
            toast("Downloading $name")
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
        if (fullscreenView != null) { webViews[current]?.webChromeClient?.onHideCustomView(); return }
        val wv = webViews[current]
        if (wv != null && wv.canGoBack()) wv.goBack() else super.onBackPressed()
    }

    // ---------- Settings / menu ----------

    private fun showMenu() {
        val extra = screens.drop(MAX_TABS)
        val items = (extra.map { it.title } + listOf("Reload this screen", "Refresh tabs from relay", "Settings", "About")).toTypedArray()
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setItems(items) { _, pick ->
            if (pick < extra.size) { show(extra[pick].id); return@setItems }
            when (pick - extra.size) {
                0 -> webViews[current]?.reload()
                1 -> loadManifest()
                2 -> showSettings(firstRun = false)
                3 -> AlertDialog.Builder(this).setTitle("Forge Hub")
                    .setMessage("App v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\nRelay: $base\n\nScreens come from the relay, so new features appear without reinstalling.")
                    .setPositiveButton("OK", null).show()
            }
        }.show()
    }

    private fun showSettings(firstRun: Boolean) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        fun field(hint: String, key: String, def: String = "", secret: Boolean = false) = EditText(this).apply {
            this.hint = hint
            setText(prefs.getString(key, def))
            inputType = if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            box.addView(this)
        }
        val fBase = field("Relay URL", "base", DEFAULT_BASE)
        val fWu = field("Relay user", "wan_user", "anon3")
        val fWp = field("Relay password", "wan_pass", secret = true)
        val fLu = field("Laptop user", "laptop_user", "laptop")
        val fLp = field("Laptop password", "laptop_pass", secret = true)
        AlertDialog.Builder(this)
            .setTitle(if (firstRun) "Connect to your relay" else "Settings")
            .setMessage(
                if (firstRun) "Passwords are in relay_access.txt on your PC."
                else "Only change these if the app actually cannot load Home/Renders. A Venice API 401 is not a new relay password — tap Back and leave these as they are."
            )
            .setView(box)
            .setCancelable(!firstRun)
            .setPositiveButton("Save") { _, _ ->
                prefs.edit()
                    .putString("base", fBase.text.toString().trim().trimEnd('/'))
                    .putString("wan_user", fWu.text.toString().trim()).putString("wan_pass", fWp.text.toString())
                    .putString("laptop_user", fLu.text.toString().trim()).putString("laptop_pass", fLp.text.toString())
                    .apply()
                webViews.values.forEach { content.removeView(it); it.destroy() }
                webViews.clear(); authTries.clear()
                WebView(this).clearCache(true)
                android.webkit.WebViewDatabase.getInstance(this).clearHttpAuthUsernamePassword()
                loadManifest()
            }
            .show()
    }

    // ---------- helpers ----------

    private fun basic(realm: String): String {
        val u = prefs.getString("${realm}_user", "")
        val p = prefs.getString("${realm}_pass", "")
        return "Basic " + Base64.encodeToString("$u:$p".toByteArray(), Base64.NO_WRAP)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val DEFAULT_BASE = "https://84-12-112-249.sslip.io"
        const val REQ_FILE = 41
        const val MAX_TABS = 6
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
        val BG = Color.parseColor("#FF0B0B0C")
        val BAR = Color.parseColor("#FF121214")
        val LINE = Color.parseColor("#14FFFFFF")
        val FG = Color.parseColor("#FFEDEDEA")
        val MUTED = Color.parseColor("#FF8C8C93")
        val ACCENT = Color.parseColor("#FFCDB88F")
        val ACCENT2 = Color.parseColor("#FFA8916A")
        val STRONG = Color.parseColor("#FFF4F1EA")
        val PILL = Color.parseColor("#1AFFFFFF")
    }
}
