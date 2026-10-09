package com.kyssta.hermey.bridge

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
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

        // Cookie name fragments the gateway uses for its session (see
        // hermes_cli/dashboard_auth/cookies.py — AT_/RT_/session variants).
        private val SESSION_COOKIE_HINTS = listOf("hermes_session", "hermes_at", "hermes_rt", "session")

        private fun hasSessionCookie(rawCookie: String?): Boolean {
            if (rawCookie.isNullOrEmpty()) return false
            return SESSION_COOKIE_HINTS.any { hint -> rawCookie.contains(hint, ignoreCase = true) }
        }

        fun buildIntent(activity: Activity, loginUrl: String, baseUrl: String): Intent =
            Intent(activity, LoginActivity::class.java).apply {
                putExtra(EXTRA_LOGIN_URL, loginUrl)
                putExtra(EXTRA_BASE_URL, baseUrl)
            }
    }

    private lateinit var webView: WebView
    private var baseUrl: String = ""
    private var finished = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val loginUrl = intent.getStringExtra(EXTRA_LOGIN_URL) ?: run { cancel(); return }
        baseUrl = intent.getStringExtra(EXTRA_BASE_URL) ?: loginUrl

        CookieManager.getInstance().setAcceptCookie(true)

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    // Follow in-app so the cookie jar stays ours; only escape
                    // to the system browser for genuinely external hosts.
                    val host = Uri.parse(request.url.toString()).host ?: return false
                    val baseHost = Uri.parse(baseUrl).host
                    if (host != baseHost) {
                        // External identity provider: open externally, don't
                        // follow inside the login window.
                        return false
                    }
                    return false
                }

                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)
                    checkForSession()
                }
            }

            loadUrl(loginUrl)
        }
        setContentView(webView)
    }

    /** Poll the cookie jar for a session cookie; finish when one appears. */
    private fun checkForSession() {
        if (finished) return
        val cookie = CookieManager.getInstance().getCookie(baseUrl)
        if (hasSessionCookie(cookie)) {
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
        loginCompletion?.invoke(cookies)
        finish()
    }

    private fun cancel() {
        if (finished) return
        finished = true
        loginCompletion?.invoke(null)
        finish()
    }

    override fun onDestroy() {
        loginCompletion = null
        super.onDestroy()
    }
}
