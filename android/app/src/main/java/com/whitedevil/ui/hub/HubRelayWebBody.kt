package com.whitedevil.ui.hub

import android.annotation.SuppressLint
import android.graphics.Color
import android.net.Uri
import android.view.ViewGroup
import android.webkit.HttpAuthHandler
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.whitedevil.MainActivity
import com.whitedevil.SettingsManager
import com.whitedevil.relayBasePublic

/** Full Hub page in a WebView — LTX planner, AI review, uploads. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun HubRelayWebBody(host: MainActivity, path: String) {
    val prefs = remember { SettingsManager.getPrefs(host) }
    val relay = host.relayBasePublic().trimEnd('/')
    val url = remember(path, relay) {
        if (path.startsWith("http")) path else relay + path
    }
    val user = remember {
        prefs.getString(SettingsManager.KEY_RELAY_USER, SettingsManager.DEFAULT_RELAY_USER).orEmpty()
    }
    val pass = remember {
        prefs.getString(SettingsManager.KEY_RELAY_PASS, "").orEmpty()
    }
    val hostName = remember(relay) { Uri.parse(relay).host.orEmpty() }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                setBackgroundColor(Color.parseColor("#0B0B0C"))
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = true
                settings.mediaPlaybackRequiresUserGesture = false
                settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                webChromeClient = object : WebChromeClient() {
                    override fun onShowFileChooser(
                        webView: WebView?,
                        filePathCallback: android.webkit.ValueCallback<Array<Uri>>?,
                        fileChooserParams: FileChooserParams?,
                    ): Boolean {
                        host.openWebFileChooser(filePathCallback, fileChooserParams)
                        return true
                    }
                }
                webViewClient = object : WebViewClient() {
                    override fun onReceivedHttpAuthRequest(
                        view: WebView,
                        handler: HttpAuthHandler,
                        hostName: String,
                        realm: String,
                    ) {
                        handler.proceed(user, pass)
                    }
                }
                if (hostName.isNotBlank()) {
                    @Suppress("DEPRECATION")
                    setHttpAuthUsernamePassword(hostName, "wan", user, pass)
                }
                loadUrl(url)
            }
        },
    )
}
