package com.kyssta.hermey.bridge

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persisted Hermey state on Android: the remote-gateway connection
 * registry (the desktop's `hermes:connections:*` state), the active
 * profile, quick-entry / HUD / screenshot preferences, zoom, and the
 * default project directory.
 *
 * Backed by SharedPreferences — the same persistence layer the desktop's
 * localStorage mirrors, so saved connections survive restarts identically.
 */
class HermeySettings(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("hermey_settings", Context.MODE_PRIVATE)

    // ── Gateway connection (primary/legacy single connection) ─────────────

    var gatewayUrl: String?
        get() = prefs.getString(KEY_GATEWAY_URL, null)
        set(value) = prefs.edit().putString(KEY_GATEWAY_URL, value).apply()

    var gatewayToken: String?
        get() = prefs.getString(KEY_GATEWAY_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_GATEWAY_TOKEN, value).apply()

    var gatewayAuthMode: String
        get() = prefs.getString(KEY_GATEWAY_AUTH_MODE, "token") ?: "token"
        set(value) = prefs.edit().putString(KEY_GATEWAY_AUTH_MODE, value).apply()

    var cloudPortalUrl: String
        get() = prefs.getString(KEY_CLOUD_PORTAL_URL, "https://hermes.nousresearch.com") ?: ""
        set(value) = prefs.edit().putString(KEY_CLOUD_PORTAL_URL, value).apply()

    var secureTokenStorage: Boolean
        get() = prefs.getBoolean(KEY_SECURE_TOKEN_STORAGE, false)
        set(value) = prefs.edit().putBoolean(KEY_SECURE_TOKEN_STORAGE, value).apply()

    // ── Session cookies (username/password + OAuth login) ─────────────────
    // The gateway's password-login / OAuth callback set HttpOnly session
    // cookies; on Android we capture the Set-Cookie values and replay them
    // on ws-ticket + API calls (there is no Electron cookie jar). Stored as
    // a JSON object {name: value}. Empty when signed out / token auth.
    var sessionCookies: String
        get() = prefs.getString(KEY_SESSION_COOKIES, "{}") ?: "{}"
        set(value) = prefs.edit().putString(KEY_SESSION_COOKIES, value).apply()

    fun sessionCookie(name: String): String? {
        val obj = runCatching { JSONObject(sessionCookies) }.getOrNull() ?: return null
        return obj.optString(name).ifEmpty { null }
    }

    fun cookieHeader(): String {
        val obj = runCatching { JSONObject(sessionCookies) }.getOrNull() ?: return ""
        return obj.keys().asSequence().joinToString("; ") { k -> "$k=${obj.optString(k)}" }
    }

    fun hasSession(): Boolean {
        val obj = runCatching { JSONObject(sessionCookies) }.getOrNull() ?: return false
        return obj.length() > 0
    }

    // ── Active profile ────────────────────────────────────────────────────

    var activeProfile: String?
        get() = prefs.getString(KEY_ACTIVE_PROFILE, null)
        set(value) = prefs.edit().putString(KEY_ACTIVE_PROFILE, value).apply()

    // ── Connections registry (v2) ─────────────────────────────────────────

    var primaryConnectionId: String
        get() = prefs.getString(KEY_PRIMARY_CONNECTION, "") ?: ""
        set(value) = prefs.edit().putString(KEY_PRIMARY_CONNECTION, value).apply()

    var launchMode: String
        get() = prefs.getString(KEY_LAUNCH_MODE, "primary") ?: "primary"
        set(value) = prefs.edit().putString(KEY_LAUNCH_MODE, value).apply()

    var lastUsedConnectionId: String?
        get() = prefs.getString(KEY_LAST_USED_CONNECTION, null)
        set(value) = prefs.edit().putString(KEY_LAST_USED_CONNECTION, value).apply()

    fun connections(): JSONArray {
        val raw = prefs.getString(KEY_CONNECTIONS, null) ?: return JSONArray()

        return runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
    }

    fun upsertConnection(connection: JSONObject) {
        val list = connections()
        val id = connection.optString("id")

        for (i in 0 until list.length()) {
            val existing = list.optJSONObject(i) ?: continue
            if (existing.optString("id") == id) {
                // Merge: preserve the stored token when the update omits it.
                if (connection.isNull("token") && existing.optString("token").isNotEmpty()) {
                    connection.put("token", existing.optString("token"))
                }
                list.put(i, connection)

                return commitConnections(list)
            }
        }

        list.put(connection)
        commitConnections(list)
    }

    fun removeConnection(id: String) {
        val list = connections()
        val out = JSONArray()
        for (i in 0 until list.length()) {
            val entry = list.optJSONObject(i) ?: continue
            if (entry.optString("id") != id) {
                out.put(entry)
            }
        }
        commitConnections(out)
        if (primaryConnectionId == id) {
            primaryConnectionId = if (out.length() > 0) out.optJSONObject(0)?.optString("id") ?: "" else ""
        }
    }

    private fun commitConnections(list: JSONArray) {
        prefs.edit().putString(KEY_CONNECTIONS, list.toString()).apply()
    }

    // ── Default profile route ─────────────────────────────────────────────

    fun defaultProfileRoute(): JSONObject? {
        val connectionId = prefs.getString(KEY_DEFAULT_ROUTE_CONNECTION, null) ?: return null
        val profile = prefs.getString(KEY_DEFAULT_ROUTE_PROFILE, null) ?: return null

        return JSONObject().apply {
            put("connectionId", connectionId)
            put("profile", profile)
        }
    }

    fun setDefaultProfileRoute(connectionId: String?, profile: String) {
        prefs.edit()
            .putString(KEY_DEFAULT_ROUTE_CONNECTION, connectionId)
            .putString(KEY_DEFAULT_ROUTE_PROFILE, profile)
            .apply()
    }

    // ── Preferences the preload persisted in localStorage ─────────────────

    var defaultProjectDir: String?
        get() = prefs.getString(KEY_DEFAULT_PROJECT_DIR, null)
        set(value) = prefs.edit().putString(KEY_DEFAULT_PROJECT_DIR, value).apply()

    var zoomPercent: Int
        get() = prefs.getInt(KEY_ZOOM_PERCENT, 100)
        set(value) = prefs.edit().putInt(KEY_ZOOM_PERCENT, value).apply()

    var zoomLevel: Double
        get() = zoomPercent / 100.0
        set(value) {
            zoomPercent = (value * 100).toInt()
        }

    var screenshotEnabled: Boolean
        get() = prefs.getBoolean(KEY_SCREENSHOT_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_SCREENSHOT_ENABLED, value).apply()

    // ── Wake indicator state ──────────────────────────────────────────────

    fun wakeIndicatorState(): JSONObject = JSONObject(prefs.getString(KEY_WAKE_STATE, "{}") ?: "{}")

    fun setWakeIndicatorState(state: JSONObject) {
        prefs.edit().putString(KEY_WAKE_STATE, state.toString()).apply()
    }

    private companion object {
        const val KEY_GATEWAY_URL = "gateway_url"
        const val KEY_GATEWAY_TOKEN = "gateway_token"
        const val KEY_GATEWAY_AUTH_MODE = "gateway_auth_mode"
        const val KEY_CLOUD_PORTAL_URL = "cloud_portal_url"
        const val KEY_SECURE_TOKEN_STORAGE = "secure_token_storage"
        const val KEY_SESSION_COOKIES = "session_cookies"
        const val KEY_ACTIVE_PROFILE = "active_profile"
        const val KEY_CONNECTIONS = "connections_registry"
        const val KEY_PRIMARY_CONNECTION = "primary_connection_id"
        const val KEY_LAUNCH_MODE = "launch_mode"
        const val KEY_LAST_USED_CONNECTION = "last_used_connection"
        const val KEY_DEFAULT_ROUTE_CONNECTION = "default_route_connection"
        const val KEY_DEFAULT_ROUTE_PROFILE = "default_route_profile"
        const val KEY_DEFAULT_PROJECT_DIR = "default_project_dir"
        const val KEY_ZOOM_PERCENT = "zoom_percent"
        const val KEY_SCREENSHOT_ENABLED = "screenshot_enabled"
        const val KEY_WAKE_STATE = "wake_indicator_state"
    }
}
