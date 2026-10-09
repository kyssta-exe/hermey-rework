package com.kyssta.hermey.bridge

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject

/**
 * In-app login window — the Android mirror of the desktop's dedicated
 * BrowserWindow that loads `{gateway}/login`.
 *
 * The gateway's `/login` page renders the right surface for the gateway's
 * providers: an OAuth redirect, or a username/password form. Either way the
 * gateway sets HttpOnly `hermes_session` cookies on success. This WebView
 * accepts those cookies (same cookie jar the WebView uses) and watches for
 * the session cookie to appear; once it does we hand every cookie back to the
 * bridge, which persists them and uses them for ws-ticket + REST auth.
 *
 * A [loginCompletion] callback receives the collected cookies as a
 * `{name: value}` JSON object, or null if the user cancels / it times out.
 */
class LoginActivity : Activity() {

    companion object {
        const val EXTRA_LOGIN_URL = "login_url"
        const val EXTRA_BASE_URL = "base_url"

        @Volatile
        var loginCompletion: ((JSONObject?) -> Unit)? = null

        private const val POLL_INTERVAL_MS = 750L

        // The access-token cookie is the authoritative "signed in" signal
        // (matches desktop hasOauthSessionCookie). The provider/pkce hint
        // cookies appear early in the flow and must NOT trigger completion.
        private const val ACCESS_TOKEN_COOKIE = "hermes_session_at"

        fun buildIntent(activity: Activity, loginUrl: String, baseUrl: String): Intent =
            Intent(activity, LoginActivity::class.java).apply {
                putExtra(EXTRA_LOGIN_URL, loginUrl)
                putExtra(EXTRA_BASE_URL, baseUrl)
            }
    }

    private lateinit var webView: WebView
    private var baseUrl: String = ""
    private var finished = false
    private val handler = Handler(Looper.getMainLooper())

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (finished) return
            checkForSession()
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val loginUrl = intent.getStringExtra(EXTRA_LOGIN_URL) ?: run { cancel(); return }
        baseUrl = intent.getStringExtra(EXTRA_BASE_URL) ?: loginUrl

        webView = WebView(this)

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        // Third-party cookies: the OAuth/SSO redirect chain crosses hosts
        // (gateway → IdP → gateway); without this the callback cookies are
        // dropped. Safe here — this window exists solely for the login flow.
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        webView.apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest
                ): Boolean {
                    // Follow everything in-app so the cookie jar stays ours and
                    // the OAuth redirect chain completes inside this window.
                    return false
                }

                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)
                    // Persist any cookies the callback just set, then check.
                    CookieManager.getInstance().flush()
                    checkForSession()
                }
            }

            loadUrl(loginUrl)
        }
        setContentView(webView)

        // Belt-and-braces poll (desktop uses the same 750ms fallback) for IdPs
        // that finish via in-page JS with no navigation event.
        handler.postDelayed(pollRunnable, POLL_INTERVAL_MS)
    }

    /** True only when the real access-token cookie is present with a value. */
    private fun hasAccessToken(cookie: String?): Boolean {
        if (cookie.isNullOrEmpty()) return false
        return cookie.split(';').any { pair ->
            val kv = pair.trim().split('=', limit = 2)
            if (kv.size != 2) {
                false
            } else {
                val name = kv[0].trim().removePrefix("__Host-").removePrefix("__Secure-")
                name == ACCESS_TOKEN_COOKIE && kv[1].trim().isNotEmpty()
            }
        }
    }

    /** Poll the cookie jar for the access-token cookie; finish when it appears. */
    private fun checkForSession() {
        if (finished) return
        val cookie = CookieManager.getInstance().getCookie(baseUrl)
        if (hasAccessToken(cookie)) {
            CookieManager.getInstance().flush()
            finish(collectCookies(cookie))
        }
    }

    private fun collectCookies(rawCookie: String?): JSONObject {
        val cookies = JSONObject()
        // getCookie returns "a=1; b=2" for the domain.
        rawCookie?.split(';')?.forEach { pair ->
            val kv = pair.trim().split('=', limit = 2)
            if (kv.size == 2) cookies.put(kv[0].trim(), kv[1].trim())
        }
        return cookies
    }

    override fun onBackPressed() {
        // Let the login page handle its own back (OAuth / form nav) before we
        // treat back as cancel.
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            cancel()
        }
    }

    private fun finish(cookies: JSONObject) {
        if (finished) return
        finished = true
        handler.removeCallbacks(pollRunnable)
        loginCompletion?.invoke(cookies)
        finish()
    }

    private fun cancel() {
        if (finished) return
        finished = true
        handler.removeCallbacks(pollRunnable)
        loginCompletion?.invoke(null)
        finish()
    }

    override fun onDestroy() {
        handler.removeCallbacks(pollRunnable)
        loginCompletion = null
        super.onDestroy()
    }
}
