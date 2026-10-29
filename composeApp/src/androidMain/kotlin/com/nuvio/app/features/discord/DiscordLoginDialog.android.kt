package com.nuvio.app.features.discord

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json

private const val LOGIN_URL = "https://discord.com/login"

// Discord removes `localStorage` from its own window once the app loads; a fresh iframe
// still exposes the origin's storage, where the session token is kept as a JSON string.
private const val READ_TOKEN_JS =
    "(function(){try{var f=document.createElement('iframe');f.style.display='none';" +
        "document.body.appendChild(f);var t=f.contentWindow.localStorage.getItem('token');" +
        "f.remove();return t;}catch(e){return null;}})()"

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal actual fun DiscordLoginDialog(
    onToken: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val currentOnToken by rememberUpdatedState(onToken)
    var webView by remember { mutableStateOf<WebView?>(null) }
    var delivered by remember { mutableStateOf(false) }

    fun tryReadToken(view: WebView) {
        val url = view.url.orEmpty()
        if (delivered || !(url.contains("/channels") || url.contains("/app"))) return
        view.evaluateJavascript(READ_TOKEN_JS) { result ->
            val token = decodeToken(result) ?: return@evaluateJavascript
            if (delivered) return@evaluateJavascript
            delivered = true
            currentOnToken(token)
        }
    }

    // SPA navigation after login doesn't always fire a page load; poll as a fallback.
    LaunchedEffect(webView) {
        val view = webView ?: return@LaunchedEffect
        while (!delivered) {
            tryReadToken(view)
            delay(1_500L)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            // Leave no Discord web session behind in Nuvio's WebView.
            CookieManager.getInstance().removeAllCookies(null)
            WebStorage.getInstance().deleteAllData()
            webView?.destroy()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Rounded.Close, contentDescription = "Close")
                    }
                    Text(
                        text = "Log in to Discord",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        WebView(context).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            // Without the WebView marker Discord serves its normal web login (with captcha support).
                            settings.userAgentString = settings.userAgentString.replace("; wv", "")
                            webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView, url: String?) = tryReadToken(view)

                                override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) =
                                    tryReadToken(view)
                            }
                            loadUrl(LOGIN_URL)
                            webView = this
                        }
                    },
                )
            }
        }
    }
}

private fun decodeToken(result: String?): String? {
    // evaluateJavascript returns a JSON value; the stored token is itself a JSON string.
    val stored = result?.let { runCatching { Json.decodeFromString<String?>(it) }.getOrNull() } ?: return null
    val token = runCatching { Json.decodeFromString<String>(stored) }.getOrDefault(stored)
    return token.takeIf { it.length > 20 && !it.contains(' ') }
}
