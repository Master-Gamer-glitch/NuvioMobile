package com.nuvio.app.features.discord

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.serialization.json.Json

private const val LOGIN_URL = "https://discord.com/login"

// Discord removes `localStorage` from its own window once the app loads; a fresh iframe
// still exposes the origin's storage, where the session token is kept as a JSON string.
private const val READ_TOKEN_JS =
    "(function(){try{var f=document.createElement('iframe');f.style.display='none';" +
        "document.body.appendChild(f);var t=f.contentWindow.localStorage.getItem('token');" +
        "f.remove();return t;}catch(e){return null;}})()"

/** Hands the login result from [DiscordLoginActivity] back to the composable that opened it. */
internal object DiscordLoginBridge {
    var listener: ((String?) -> Unit)? = null
}

// The login runs in its own activity: a WebView inside a Compose Dialog window never gets
// composited on some devices (the page loads but the screen stays blank).
@Composable
internal actual fun DiscordLoginDialog(
    onToken: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val currentOnToken by rememberUpdatedState(onToken)
    val currentOnDismiss by rememberUpdatedState(onDismiss)

    DisposableEffect(Unit) {
        DiscordLoginBridge.listener = { token ->
            if (token != null) currentOnToken(token) else currentOnDismiss()
        }
        context.startActivity(Intent(context, DiscordLoginActivity::class.java))
        onDispose { DiscordLoginBridge.listener = null }
    }
}

class DiscordLoginActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private var delivered = false
    private val poll = object : Runnable {
        override fun run() {
            tryReadToken()
            if (!delivered) webView.postDelayed(this, 1_500L)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val background = Color.rgb(0x11, 0x12, 0x14)
        window.decorView.setBackgroundColor(background)

        val title = TextView(this).apply {
            text = "Log in to Discord"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        }
        val close = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setBackgroundColor(Color.TRANSPARENT)
            contentDescription = "Close"
            setOnClickListener { finish() }
        }
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(background)
            addView(close, LinearLayout.LayoutParams(dp(48), dp(48)))
            addView(title, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(8) })
        }

        webView = WebView(this).apply {
            setBackgroundColor(background)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // Without the WebView marker Discord serves its normal web login (with captcha support).
            settings.userAgentString = settings.userAgentString.replace("; wv", "")
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) = tryReadToken()

                override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) =
                    tryReadToken()
            }
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(background)
            addView(toolbar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(webView, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        setContentView(root)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        })

        webView.loadUrl(LOGIN_URL)
        // SPA navigation after login doesn't always fire a page load; poll as a fallback.
        webView.postDelayed(poll, 1_500L)
    }

    private fun tryReadToken() {
        val url = webView.url.orEmpty()
        if (delivered || !(url.contains("/channels") || url.contains("/app"))) return
        webView.evaluateJavascript(READ_TOKEN_JS) { result ->
            val token = decodeToken(result) ?: return@evaluateJavascript
            if (delivered) return@evaluateJavascript
            delivered = true
            DiscordLoginBridge.listener?.invoke(token)
            finish()
        }
    }

    override fun onDestroy() {
        webView.removeCallbacks(poll)
        if (isFinishing) {
            if (!delivered) DiscordLoginBridge.listener?.invoke(null)
            // Leave no Discord web session behind in Nuvio's WebView.
            CookieManager.getInstance().removeAllCookies(null)
            WebStorage.getInstance().deleteAllData()
        }
        webView.destroy()
        super.onDestroy()
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()
}

private fun decodeToken(result: String?): String? {
    // evaluateJavascript returns a JSON value; the stored token is itself a JSON string.
    val stored = result?.let { runCatching { Json.decodeFromString<String?>(it) }.getOrNull() } ?: return null
    val token = runCatching { Json.decodeFromString<String>(stored) }.getOrDefault(stored)
    return token.takeIf { it.length > 20 && !it.contains(' ') }
}
