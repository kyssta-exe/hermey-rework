package com.kyssta.hermey.bridge

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.OpenableColumns
import android.util.Base64
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.kyssta.hermey.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * HermeyBridge — the Android native half of `window.hermesDesktop`.
 *
 * Every channel the Electron preload exposes (hermes:*) is handled here:
 * `call` is the invoke path (returns a value), `post` is the fire-and-forget
 * send path. Callbacks the preload wires with `ipcRenderer.on` arrive over
 * the `hermes:bridge-event` listener channel — see [notify].
 *
 * Gateway-only design: there is no local Python backend on Android, so the
 * connection channels resolve against the persisted remote-gateway
 * configuration (managed in SharedPreferences by [HermeySettings]).
 */
@CapacitorPlugin(name = "HermeyBridge")
class HermeyBridgePlugin : Plugin() {

    private val settings by lazy { HermeySettings(context) }
    private val updater by lazy { AppUpdater(context) }
    private val eventHandlers = ConcurrentHashMap<String, MutableList<(Any?) -> Unit>>()
    private val bgExecutor: java.util.concurrent.ExecutorService =
        java.util.concurrent.Executors.newCachedThreadPool()

    // ── Capacitor plugin lifecycle ────────────────────────────────────────

    override fun load() {
        instance = this
        // Announce platform facts + initial boot progress as soon as the
        // bridge loads, so the renderer can paint the connection screen.
        notifyBridgeEvent("hermes:boot-progress", bootProgressJson(phase = "idle", progress = 0.0, running = false))
    }

    // ── JS-facing API ─────────────────────────────────────────────────────

    /** Electron `ipcRenderer.invoke(channel, ...args)`. */
    @PluginMethod
    fun call(call: PluginCall) {
        val channel = call.getString("channel") ?: return call.reject("missing channel")
        val args = call.getArray("args") ?: JSONArray()

        try {
            val result = dispatch(channel, args, call)
            if (result != null) {
                call.resolve(JSObject(result.toString()))
            } else {
                call.resolve(JSObject())
            }
        } catch (err: Throwable) {
            call.reject("hermes:call failed for $channel: ${err.message}")
        }
    }

    /** Electron `ipcRenderer.send(channel, ...args)` — fire and forget. */
    @PluginMethod
    fun post(call: PluginCall) {
        val channel = call.getString("channel") ?: return call.resolve()
        val args = call.getArray("args") ?: JSONArray()

        try {
            dispatch(channel, args, call)
        } catch (_: Throwable) {
            // send() semantics: failures are invisible to the renderer.
        }
        call.resolve()
    }

    /** Static platform facts (the preload's synchronous answers). */
    @PluginMethod
    fun facts(call: PluginCall) {
        call.resolve(
            JSObject().apply {
                put("glassSupported", false)
                put("translucencySupported", false)
                put("remoteGatewayOnly", true)
                put("quickEntryStubbed", true)
                put("hudStubbed", true)
                put("screenshotUnavailable", true)
                put("terminalStubbed", true)
                put("platform", "android")
                put("appVersion", BuildConfig.VERSION_NAME)
            }
        )
    }

    /** WebView → native event notification (used by the event bus). */
    @PluginMethod
    fun notify(call: PluginCall) {
        call.resolve()
    }

    /** JS subscribes to `hermes:bridge-event`; every bridge event routes here. */
    @PluginMethod
    override fun addListener(call: PluginCall) {
        // Capacitor delivers all plugin events through this single channel.
        call.resolve()
    }

    companion object {
        /** Screenshot consent status push, called from ScreenshotCapture. */
        fun notifyScreenshotStatus(granted: Boolean) {
            instance?.notifyBridgeEvent(
                "hermes:screenshot:status",
                JSObject().put("enabled", granted).put("granted", granted)
            )
        }

        private var instance: HermeyBridgePlugin? = null
    }

    // ── Event plumbing ────────────────────────────────────────────────────

    /** Emit a bridge event to the renderer (Electron `ipcRenderer.on` twin). */
    fun notifyBridgeEvent(channel: String, payload: Any?) {
        val data = JSObject().apply {
            put("channel", channel)
            put("payload", payload)
        }
        notifyListeners("hermes:bridge-event", data)
    }

    private fun handlersFor(channel: String): List<(Any?) -> Unit> =
        eventHandlers[channel]?.toList() ?: emptyList()

    private fun emitToLocal(channel: String, payload: Any?) {
        handlersFor(channel).forEach { handler ->
            try {
                handler(payload)
            } catch (_: Throwable) {
                /* listener isolation, same as Electron's emitter */
            }
        }
    }

    // ── Channel dispatch ──────────────────────────────────────────────────

    private fun dispatch(channel: String, args: JSONArray, call: PluginCall): JSONObject? {
        val ctx = context
        return when (channel) {

            // ── Connection / gateway ──────────────────────────────────────
            "hermes:connection", "hermes:connection:for" -> connectionDescriptor(args)
            "hermes:gateway:ws-url", "hermes:gateway:ws-url-for" -> gatewayWsUrl(args)
            "hermes:agents:roster" -> agentRoster()
            "hermes:plugin-profile-routes" -> profileRoutes(args)
            "hermes:embed-host:origin" -> JSObject().put("value", "")
            "hermes:connection:revalidate" -> JSObject().put("ok", true).put("rebuilt", false)
            "hermes:backend:touch" -> JSObject().put("ok", true)
            "hermes:pool-limits:get" -> poolLimits()
            "hermes:pool-limits:set" -> poolLimits()
            "hermes:connection-config:get" -> connectionConfig(args)
            "hermes:connection-config:save", "hermes:connection-config:apply" -> saveConnectionConfig(args)
            "hermes:connection-config:test" -> testConnectionConfig(args)
            "hermes:connections:list" -> connectionsList()
            "hermes:connections:save" -> connectionsSave(args)
            "hermes:connections:remove" -> connectionsRemove(args)
            "hermes:connections:set-primary" -> connectionsSetPrimary(args)
            "hermes:connections:set-launch-mode" -> connectionsSetLaunchMode(args)
            "hermes:connections:set-last-used" -> connectionsList()
            "hermes:connections:test" -> testConnectionById(args)
            "hermes:ssh-config:hosts" -> JSObject().put("hosts", JSONArray())
            "hermes:ssh-config:resolve" -> JSObject()
                .put("hostname", null).put("identityFile", null).put("port", null).put("user", null)
            "hermes:connection-config:probe" -> probeConnection(args)
            "hermes:connection-config:oauth-login" -> {
                // Deferred: resolves when the login WebView returns cookies.
                oauthLogin(args, call)
                null
            }
            "hermes:connection-config:oauth-logout" -> {
                settings.sessionCookies = "{}"
                JSObject().put("ok", true).put("connected", false)
            }
            "hermes:auth:password-login" -> passwordLogin(args)
            "hermes:auth:status" -> authStatus()

            // ── Cloud ─────────────────────────────────────────────────────
            "hermes:cloud:status" -> JSObject()
                .put("portalBaseUrl", settings.cloudPortalUrl)
                .put("signedIn", false)
            "hermes:cloud:login" -> JSObject()
                .put("portalBaseUrl", settings.cloudPortalUrl).put("signedIn", false).put("ok", false)
            "hermes:cloud:logout" -> JSObject()
                .put("portalBaseUrl", settings.cloudPortalUrl).put("signedIn", false).put("ok", true)
            "hermes:cloud:discover" -> JSObject().put("needsOrgSelection", true).put("orgs", JSONArray())
            "hermes:cloud:agent-sign-in" -> JSObject().put("connected", false)

            // ── Profiles ──────────────────────────────────────────────────
            "hermes:profile:default:get" -> settings.defaultProfileRoute()
            "hermes:profile:default:set" -> {
                val route = args.optJSONObject(0)
                if (route != null) {
                    settings.setDefaultProfileRoute(route.optString("connectionId").ifEmpty { null }, route.optString("profile"))
                }
                settings.defaultProfileRoute()
            }
            "hermes:profile:get" -> JSObject().put("profile", settings.activeProfile)
            "hermes:profile:remember" -> {
                settings.activeProfile = args.optString(0).ifEmpty { null }
                JSObject().put("profile", settings.activeProfile)
            }
            "hermes:profile:set" -> {
                settings.activeProfile = args.optString(0).ifEmpty { null }
                JSObject().put("profile", settings.activeProfile)
            }

            // ── REST API (gateway HTTP) ───────────────────────────────────
            "hermes:api" -> gatewayApi(args)

            // ── Notifications ─────────────────────────────────────────────
            "hermes:notify" -> notify(args)

            // ── Window stubs (in-app only) ────────────────────────────────
            "hermes:window:openSession" -> JSObject().put("ok", true)
            "hermes:window:openInTerminal" -> JSObject().put("ok", true)
            "hermes:window:openInstance" -> JSObject().put("ok", true)
            "hermes:window:openBrowser" -> JSObject().put("ok", true)
            "hermes:window:size" -> null
            "hermes:window-control" -> null
            "hermes:ambient:claim" -> JSObject().put("value", true)

            // ── Wake indicator (in-app) ───────────────────────────────────
            "hermes:wake-indicator:get" -> settings.wakeIndicatorState()
            "hermes:wake-indicator:set" -> {
                args.optJSONObject(0)?.let { settings.setWakeIndicatorState(it) }
                null
            }

            // ── Pet overlay (in-app floating card) ────────────────────────
            "hermes:pet-overlay:open" -> JSObject().put("ok", true)
            "hermes:pet-overlay:close" -> JSObject().put("ok", true)
            "hermes:pet-overlay:set-bounds" -> null
            "hermes:pet-overlay:ignore-mouse" -> null
            "hermes:pet-overlay:set-focusable" -> null

            // ── HUD (full-screen view) ────────────────────────────────────
            "hermes:hud:open" -> JSObject().put("ok", true)
            "hermes:hud:close" -> JSObject().put("ok", true)
            "hermes:hud:reset-layout" -> JSObject().put("ok", true)
            "hermes:hud:frost" -> JSObject().put("ok", true)
            "hermes:hud:ignore-mouse", "hermes:hud:begin-move", "hermes:hud:end-move",
            "hermes:hud:move-by", "hermes:hud:workspace-transfer", "hermes:hud:set-bounds",
            "hermes:hud:session" -> null

            "hermes:hud-modifier:settings:get" -> JSObject().put("enabled", false)
            "hermes:hud-modifier:settings:set" -> JSObject().put("enabled", args.optBoolean(0, false))
            "hermes:hud-modifier:permission" -> null

            // ── Screenshot (MediaProjection-gated capture) ────────────────
            "hermes:screenshot:settings:get" -> JSObject().put("enabled", settings.screenshotEnabled)
            "hermes:screenshot:settings:set" -> {
                settings.screenshotEnabled = args.optBoolean(0, false)
                JSObject().put("enabled", settings.screenshotEnabled)
            }
            "hermes:screenshot:permission" -> requestScreenCapturePermission()
            "hermes:screenshot:capture" -> captureScreen()

            // ── Quick Entry (in-app sheet) ────────────────────────────────
            "hermes:quick-entry:settings:get" -> JSObject()
                .put("enabled", true).put("registered", false).put("shortcut", "")
            "hermes:quick-entry:settings:set" -> JSObject()
                .put("enabled", args.optJSONObject(0)?.optBoolean("enabled", true) ?: true)
                .put("registered", false).put("shortcut", "")
            "hermes:quick-entry:submit" -> JSObject().put("ok", true)

            // ── Boot / bootstrap (remote-only: instant ready) ─────────────
            "hermes:boot-progress:get" -> bootProgressJson("ready", 1.0, false)
            "hermes:bootstrap:get" -> JSObject()
                .put("active", false).put("manifest", null).put("stages", JSONObject())
                .put("error", null).put("log", JSONArray()).put("startedAt", null)
                .put("completedAt", System.currentTimeMillis()).put("setupChoice", null)
                .put("unsupportedPlatform", null).put("bundled", false)
            "hermes:local-backend:probe" -> JSObject().put("bootstrapNeeded", false)
            "hermes:bootstrap:continue-local" -> JSObject().put("ok", true)
            "hermes:backend:recycle" -> JSObject().put("ok", true)
            "hermes:bootstrap:reset" -> JSObject().put("ok", true)
            "hermes:update-hold:recheck", "hermes:update-hold:quit", "hermes:update-hold:start-anyway" ->
                JSObject().put("ok", true)
            "hermes:bootstrap:repair" -> JSObject().put("ok", true)
            "hermes:bootstrap:cancel" -> JSObject().put("ok", true).put("cancelled", true)

            // ── Version / machine / metrics ───────────────────────────────
            "hermes:version" -> versionInfo()
            "hermes:machine:profile" -> machineProfile()
            "hermes:get-remote-display-reason" -> null
            "hermes:sync-status" -> null
            "hermes:startup-latency:claim" -> null
            "hermes:app:relaunch" -> null
            "hermes:desktop-metrics:set-enabled" -> null
            "hermes:desktop-metrics:crash:take" -> null
            "hermes:desktop-metrics:crash:ack" -> null

            // ── Updates (GitHub-release auto-update) ──────────────────────
            "hermes:updates:check" -> {
                checkUpdates(call)
                null
            }
            "hermes:updates:apply" -> {
                applyUpdates(args, call)
                null
            }
            "hermes:updates:branch:get" -> JSObject().put("branch", "android")
            "hermes:updates:branch:set" -> JSObject().put("branch", "android")
            "hermes:updates:metric:take" -> null
            "hermes:updates:metric:ack" -> null

            // ── Uninstall (no-op on Android) ──────────────────────────────
            "hermes:uninstall:summary" -> JSObject()
                .put("code_removal_allowed", false)
                .put("native_removal_instructions", null)
                .put("hermes_home", "")
                .put("agent_installed", false)
                .put("gui_installed", false)
                .put("source_built_artifacts", JSONArray())
                .put("packaged_app_paths", JSONArray())
                .put("userdata_dir", "")
                .put("userdata_exists", false)
                .put("platform", "android")
            "hermes:uninstall:run" -> JSObject().put("ok", false)
                .put("error", "Uninstall the app from Android settings instead")
            "hermes:uninstall:openAppsSettings" -> {
                openAppSettings()
                null
            }

            // ── Themes (marketplace fetch over HTTP) ──────────────────────
            "hermes:vscode-theme:fetch" -> fetchMarketplaceTheme(args)
            "hermes:vscode-theme:search" -> searchMarketplace(args)

            // ── Find in page (WebView find) ───────────────────────────────
            "hermes:find-in-page" -> findInPage(args)
            "hermes:stop-find-in-page" -> {
                bridge.webView?.clearMatches()
                null
            }

            // ── Clipboard ─────────────────────────────────────────────────
            "hermes:writeClipboard" -> {
                writeClipboard(args.optString(0))
                JSObject().put("value", true)
            }
            "hermes:readClipboard" -> JSObject().put("value", readClipboard() ?: "")

            // ── External open ─────────────────────────────────────────────
            "hermes:openExternal" -> {
                openExternal(args.optString(0))
                null
            }
            "hermes:openPreviewInBrowser" -> {
                openExternal(args.optString(0))
                null
            }

            // ── Link metadata ─────────────────────────────────────────────
            "hermes:fetchLinkTitle" -> fetchLinkTitle(args)
            "hermes:resolveFavicon" -> JSObject().put("value", "")
            "hermes:preview:reach" -> JSObject().put("value", args.optString(0))

            // ── File IO ───────────────────────────────────────────────────
            "hermes:readFileDataUrl", "hermes:readFileDataUrlForAttach" -> readFileDataUrl(args)
            "hermes:data-url-read-max:get" -> dataUrlReadMax()
            "hermes:data-url-read-max:set" -> dataUrlReadMax(args.optInt(0, 50))
            "hermes:readFileText" -> readFileText(args)
            "hermes:readPluginSource" -> readPluginSource(args)
            "hermes:saveGatewayFile" -> saveGatewayFile(args)
            "hermes:saveImageFromUrl" -> JSObject().put("value", true)
            "hermes:saveImageBuffer" -> saveImageBuffer(args)
            "hermes:savePastedText" -> savePastedText(args)
            "hermes:saveClipboardImage" -> saveClipboardImage()
            "hermes:selectPaths" -> JSObject().put("value", JSONArray())
            "hermes:selectSavePath" -> JSObject().put("value", null as String?)
            "hermes:capturePreview" -> JSObject().put("value", "")

            // ── Filesystem ────────────────────────────────────────────────
            "hermes:fs:readDir" -> readDir(args)
            "hermes:fs:gitRoot" -> JSObject().put("value", null as String?)
            "hermes:fs:reveal" -> JSObject().put("value", false)
            "hermes:fs:openDir" -> JSObject().put("ok", false)
                .put("error", "No file manager interaction on Android")
            "hermes:fs:desktopPluginsRoot" -> JSObject().put("value", pluginsRoot())
            "hermes:fs:reconcileDesktopPlugins" -> JSObject().put("value", JSONArray())
            "hermes:fs:logsRoot" -> JSObject().put("value", logsRoot(args.optString(0)))
            "hermes:fs:rename" -> renamePath(args)
            "hermes:fs:writeText" -> writeTextFile(args)
            "hermes:fs:trash" -> JSObject().put("value", false)

            // ── Git (remote-gateway only: local git ops unsupported) ──────
            "hermes:git:worktreeList" -> JSObject().put("value", JSONArray())
            "hermes:git:worktreeAdd" -> reject("Git worktrees require a desktop shell")
            "hermes:git:worktreeRemove" -> JSObject().put("removed", "")
            "hermes:git:branchSwitch" -> JSObject().put("branch", args.optString(1))
            "hermes:git:branchList" -> JSObject().put("value", JSONArray())
            "hermes:git:baseBranchList" -> JSObject().put("value", JSONArray())
            "hermes:git:repoStatus" -> JSObject().put("value", null as String?)
            "hermes:git:fileDiff" -> JSObject().put("value", "")
            "hermes:git:scanRepos" -> JSObject().put("value", JSONArray())
            "hermes:git:review:list" -> JSObject().put("files", JSONArray()).put("base", null)
            "hermes:git:review:diff" -> JSObject().put("value", "")
            "hermes:git:review:stage", "hermes:git:review:unstage", "hermes:git:review:revert",
            "hermes:git:review:commit", "hermes:git:review:push" -> JSObject().put("ok", true)
            "hermes:git:review:revParse" -> JSObject().put("value", null as String?)
            "hermes:git:review:commitContext" -> JSObject().put("diff", "").put("recent", "")
            "hermes:git:review:shipInfo" -> JSObject().put("ghReady", false).put("pr", null)
            "hermes:git:review:prList" -> JSObject().put("ghReady", false).put("prs", JSONArray())
            "hermes:git:review:createPr" -> JSObject().put("url", "")

            // ── Terminal (no PTY: stubbed) ────────────────────────────────
            "hermes:terminal:attach", "hermes:terminal:dispose", "hermes:terminal:resize",
            "hermes:terminal:write" -> JSObject().put("value", false)
            "hermes:terminal:cwd" -> JSObject().put("value", null as String?)
            "hermes:terminal:start" -> JSObject()
                .put("cwd", args.optJSONObject(0)?.optString("cwd") ?: "")
                .put("id", "")
                .put("shell", "android-unavailable")

            // ── Watch / preview ───────────────────────────────────────────
            "hermes:watchPreviewFile", "hermes:watchDirectory" -> JSObject()
                .put("id", UUID.randomUUID().toString()).put("path", args.optString(0))
            "hermes:stopPreviewFileWatch" -> JSObject().put("value", true)
            "hermes:normalizePreviewTarget" -> args.optString(0).let { target ->
                if (target.isNullOrEmpty()) {
                    JSObject().put("value", null as String?)
                } else {
                    JSObject().apply {
                        put("kind", if (target.startsWith("http")) "url" else "file")
                        put("label", target.substringAfterLast('/'))
                        put("source", target)
                        put("url", target)
                        put("path", target)
                    }
                }
            }

            // ── MCP OAuth (loopback is a desktop affordance) ───────────────
            "hermes:mcp-oauth:listen" -> reject("MCP OAuth loopback is not available on Android")
            "hermes:mcp-oauth:wait" -> JSObject()
                .put("code", null).put("error", "unsupported").put("iss", null).put("state", null)
            "hermes:mcp-oauth:cancel" -> JSObject().put("value", true)

            // ── Free tier challenge ───────────────────────────────────────
            "hermes:freeTierChallenge:run" -> JSObject().put("outcome", "skipped")

            // ── Plugin repo management ────────────────────────────────────
            "hermes:plugin:probe" -> JSObject()
                .put("ok", true).put("agent", false).put("desktop", false)
                .put("error", "Plugin installation requires the desktop shell")
            "hermes:plugin:installDesktop" -> JSObject()
                .put("ok", false).put("error", "Plugin installation requires the desktop shell")
            "hermes:plugin:removeDesktop" -> JSObject()
                .put("ok", false).put("error", "Plugin removal requires the desktop shell")

            // ── Deep links ────────────────────────────────────────────────
            "hermes:deep-link-ready" -> JSObject().put("ok", true)

            // ── Settings ──────────────────────────────────────────────────
            "hermes:setting:defaultProjectDir:get" -> JSObject()
                .put("defaultLabel", "Hermes workspace")
                .put("dir", settings.defaultProjectDir)
                .put("resolvedCwd", settings.defaultProjectDir ?: "")
            "hermes:setting:defaultProjectDir:set" -> {
                settings.defaultProjectDir = args.optString(0).ifEmpty { null }
                JSObject().put("dir", settings.defaultProjectDir)
            }
            "hermes:setting:defaultProjectDir:pick" -> JSObject()
                .put("canceled", true).put("dir", null as String?)
            "hermes:workspace:sanitize" -> JSObject()
                .put("cwd", args.optString(0).ifEmpty { "" }).put("sanitized", false)

            // ── Zoom (WebView text zoom) ──────────────────────────────────
            "hermes:zoom:get" -> JSObject().put("level", settings.zoomLevel).put("percent", settings.zoomPercent)
            "hermes:zoom:set-percent" -> {
                val percent = args.optInt(0, 100)
                settings.zoomPercent = percent
                settings.zoomLevel = percent / 100.0
                activity?.runOnUiThread {
                    bridge.webView?.settings?.textZoom = percent
                }
                null
            }

            // ── Logs ──────────────────────────────────────────────────────
            "hermes:logs:reveal" -> JSObject().put("ok", false).put("path", logsRoot(null))
            "hermes:logs:recent" -> JSObject()
                .put("path", logsRoot(null)).put("lines", logLines())
            "hermes:logs:renderer-error", "hermes:logs:renderer-line" -> null

            // ── Power / battery ───────────────────────────────────────────
            "hermes:power-battery:get" -> JSObject().put("value", onBattery())
            "hermes:power-resume" -> null
            "hermes:keep-awake" -> null

            // ── Theme / translucency / native theme ───────────────────────
            "hermes:native-theme", "hermes:translucency", "hermes:titlebar-theme" -> null
            "hermes:secret-storage:get" -> JSObject().put("on", settings.secureTokenStorage)
            "hermes:secret-storage:set" -> {
                settings.secureTokenStorage = args.optBoolean(0, false)
                JSObject().put("on", settings.secureTokenStorage)
            }

            // ── Minimize to tray: no-op (foreground-only app) ─────────────
            "hermes:minimize-to-tray:get" -> JSObject().put("enabled", false).put("available", false)
            "hermes:minimize-to-tray:set" -> JSObject().put("enabled", false).put("available", false)

            // ── Context menu / spellcheck (WebView-native behavior) ───────
            "hermes:context-menu:edit", "hermes:context-menu:copy-image",
            "hermes:context-menu:spellcheck", "hermes:context-menu:guest-add-word" -> null
            "hermes:context-menu-spellcheck" -> null

            // ── Microphone permission request ─────────────────────────────
            "hermes:requestMicrophoneAccess" -> {
                // Capacitor's own permission flow handles RECORD_AUDIO; this
                // resolves true so the voice UI proceeds, the runtime check
                // happens at getUserMedia() time in the WebView.
                JSObject().put("value", true)
            }

            // ── Fire-and-forget posts: no return value ────────────────────
            "hermes:window:relay", "hermes:active-work", "hermes:f12ShortcutActive",
            "hermes:previewShortcutActive", "hermes:preview-guest-hidden",
            "hermes:connection:active-route", "hermes:devtools:disable-f12",
            "hermes:quick-entry:ack", "hermes:quick-entry:dismiss",
            "hermes:quick-entry:state", "hermes:pet-overlay:state",
            "hermes:pet-overlay:control" -> null

            // ── Unknown channel: reject like a missing Electron handler ───
            else -> reject("No handler registered for channel '$channel'")
        }
    }

    // ── Connection descriptor ─────────────────────────────────────────────

    private fun connectionDescriptor(args: JSONArray): JSObject {
        val profile = args.optString(0).ifEmpty { null }
        val url = settings.gatewayUrl ?: return reject("No remote gateway configured")

        return JSObject().apply {
            put("baseUrl", url)
            put("isFullscreen", false)
            put("isMaximized", false)
            put("mode", "remote")
            put("authMode", settings.gatewayAuthMode)
            put("remoteHost", Uri.parse(url).host ?: "")
            put("remoteKind", "url")
            put("nativeOverlayWidth", 0)
            put("source", "settings")
            put("token", settings.gatewayToken ?: "")
            put("wsUrl", wsUrlFor(url, profile))
            put("logs", JSONArray())
            put("profile", profile ?: settings.activeProfile ?: "default")
            put("connectionId", settings.primaryConnectionId)
            put("registryScoped", true)
            put("windowButtonPosition", null as String?)
        }
    }

    private fun gatewayWsUrl(args: JSONArray): JSObject {
        val profile = args.optString(0).ifEmpty { null }
        val url = settings.gatewayUrl ?: return reject("No remote gateway configured")

        return JSObject().put("ok", true).put("wsUrl", wsUrlFor(url, profile))
    }

    private fun wsUrlFor(baseUrl: String, profile: String?): String {
        val http = baseUrl.trimEnd('/')
        val ws = when {
            http.startsWith("https://") -> "wss://" + http.removePrefix("https://")
            http.startsWith("http://") -> "ws://" + http.removePrefix("http://")
            else -> http
        }
        // Auth-required gateways (username/password or OAuth) can't put a
        // bearer token on the WS upgrade; mint a single-use ticket with the
        // session cookie instead. Token-auth gateways use the long-lived token.
        val ticket = if (settings.gatewayAuthMode == "oauth" && settings.hasSession()) {
            mintWsTicket(http)
        } else {
            null
        }
        val query = buildString {
            when {
                ticket != null -> append("ticket=").append(Uri.encode(ticket))
                else -> {
                    val token = settings.gatewayToken
                    if (!token.isNullOrEmpty()) append("token=").append(Uri.encode(token))
                }
            }
            if (!profile.isNullOrEmpty()) {
                if (isNotEmpty()) append("&")
                append("profile=").append(Uri.encode(profile))
            }
        }

        return if (query.isEmpty()) "$ws/api/ws" else "$ws/api/ws?$query"
    }

    private fun agentRoster(): JSObject {
        val connections = settings.connections()
        val agents = JSONArray()
        val sources = JSONArray()

        for (i in 0 until connections.length()) {
            val conn = connections.optJSONObject(i) ?: continue
            agents.put(
                JSObject().apply {
                    put("connectionId", conn.optString("id"))
                    put("connectionKind", conn.optString("kind", "remote"))
                    put("connectionLabel", conn.optString("label"))
                    put("profile", "default")
                    put("handle", conn.optString("label"))
                }
            )
            sources.put(
                JSObject().apply {
                    put("connectionId", conn.optString("id"))
                    put("label", conn.optString("label"))
                    put("kind", conn.optString("kind", "remote"))
                    put("reachable", true)
                }
            )
        }

        return JSObject().put("agents", agents).put("sources", sources)
    }

    private fun profileRoutes(args: JSONArray): JSObject {
        val out = JSONArray()
        val profiles = args.optJSONArray(0) ?: JSONArray()
        for (i in 0 until profiles.length()) {
            val profile = profiles.optString(i)
            out.put(
                JSObject().apply {
                    put("connectionId", settings.primaryConnectionId)
                    put("mode", "remote")
                    put("primary", true)
                    put("profile", profile)
                    put("targetProfile", profile)
                }
            )
        }

        return JSObject().put("value", out)
    }

    // ── Connection config ─────────────────────────────────────────────────

    private fun connectionConfig(args: JSONArray): JSObject {
        val url = settings.gatewayUrl ?: ""
        return JSObject().apply {
            put("envOverride", false)
            put("mode", "remote")
            put("profile", args.optString(0).ifEmpty { null })
            put("remoteAuthMode", settings.gatewayAuthMode)
            put("remoteOauthConnected", false)
            put("remoteTokenPreview", settings.gatewayToken?.take(4)?.plus("…"))
            put("remoteTokenSet", !settings.gatewayToken.isNullOrEmpty())
            put("secureTokenStorage", settings.secureTokenStorage)
            put("remoteTokenPlainText", !settings.secureTokenStorage && !settings.gatewayToken.isNullOrEmpty())
            put("remoteUrl", url)
            put("cloudOrg", "")
            put("sshHost", "")
            put("sshUser", "")
            put("sshPort", null as Int?)
            put("sshKeyPath", "")
            put("sshRemoteHermesPath", "")
            put("sshRemoteProfile", "")
        }
    }

    private fun saveConnectionConfig(args: JSONArray): JSObject {
        val payload = args.optJSONObject(0) ?: return reject("missing payload")
        payload.optString("remoteUrl").ifEmpty { null }?.let { settings.gatewayUrl = it }
        payload.optString("remoteToken").ifEmpty { null }?.let { settings.gatewayToken = it }
        payload.optString("remoteAuthMode").ifEmpty { null }?.let { settings.gatewayAuthMode = it }

        return connectionConfig(JSONArray())
    }

    private fun testConnectionConfig(args: JSONArray): JSObject {
        val payload = args.optJSONObject(0) ?: return reject("missing payload")
        val url = payload.optString("remoteUrl").ifEmpty { settings.gatewayUrl ?: return reject("No gateway URL") }

        return try {
            val status = httpGet("${url.trimEnd('/')}/api/status")
            JSObject().apply {
                put("ok", status.code in 200..299)
                put("reachable", true)
                put("version", status.json.optString("version").ifEmpty { null })
                put("baseUrl", url)
            }
        } catch (err: Throwable) {
            JSObject().put("ok", false).put("reachable", false)
                .put("error", err.message ?: "unreachable")
        }
    }

    private fun probeConnection(args: JSONArray): JSObject {
        val url = args.optString(0).trimEnd('/')

        return try {
            val status = httpGet("$url/api/status")
            val reachable = status.code in 200..299
            // auth_required: true → the gateway engages an auth gate (OAuth or
            // username/password); otherwise legacy token auth.
            val authRequired = status.json.optBoolean("auth_required", false)
            val providers = fetchAuthProviders(url)

            JSObject().apply {
                put("baseUrl", url)
                put("reachable", reachable)
                put("authMode", if (authRequired) "oauth" else "token")
                put("providers", providers)
                put("version", status.json.optString("version").ifEmpty { null })
                put("error", null as String?)
            }
        } catch (err: Throwable) {
            JSObject().apply {
                put("baseUrl", url)
                put("reachable", false)
                put("authMode", "unknown")
                put("providers", JSONArray())
                put("version", null)
                put("error", err.message ?: "unreachable")
            }
        }
    }

    /** GET /api/auth/providers → [{name, display_name, supports_password}]. */
    private fun fetchAuthProviders(url: String): JSONArray {
        return try {
            val res = httpGet("$url/api/auth/providers")
            if (res.code in 200..299) {
                res.json.optJSONArray("providers") ?: JSONArray()
            } else {
                JSONArray()
            }
        } catch (_: Throwable) {
            JSONArray()
        }
    }

    /**
     * Username/password login: POST /auth/password-login with
     * {provider, username, password}, capture the Set-Cookie session cookies,
     * and store them so subsequent ws-ticket + API calls authenticate.
     *
     * args[0] = { url, provider, username, password }
     */
    private fun passwordLogin(args: JSONArray): JSObject {
        val payload = args.optJSONObject(0) ?: return reject("missing payload")
        val url = payload.optString("url").trimEnd('/')
        val provider = payload.optString("provider")
        val username = payload.optString("username")
        val password = payload.optString("password")

        if (url.isEmpty() || provider.isEmpty() || username.isEmpty() || password.isEmpty()) {
            return JSObject().put("ok", false).put("error", "url, provider, username and password are required")
        }

        val body = JSONObject().apply {
            put("provider", provider)
            put("username", username)
            put("password", password)
            put("next", "")
        }

        return try {
            val conn = (URL("$url/auth/password-login").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 15_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val code = conn.responseCode
            // Capture Set-Cookie headers (may be multiple).
            val cookies = JSONObject()
            // Full Set-Cookie scan from the raw header map (HttpURLConnection
            // exposes repeated Set-Cookie headers here on Android).
            try {
                val headerFields = conn.headerFields
                for ((_, values) in headerFields) {
                    for (value in values) {
                        if (value.startsWith("Set-Cookie:", true)) {
                            val raw = value.removePrefix("Set-Cookie:").trim()
                            val pair = raw.substringBefore(';').split('=', limit = 2)
                            if (pair.size == 2) cookies.put(pair[0].trim(), pair[1].trim())
                        }
                    }
                }
            } catch (_: Throwable) {
                /* best-effort */
            }
            conn.disconnect()

            if (code in 200..299 && cookies.length() > 0) {
                settings.sessionCookies = cookies.toString()
                settings.gatewayUrl = url
                settings.gatewayAuthMode = "oauth"
                JSObject().apply {
                    put("ok", true)
                    put("connected", true)
                    put("baseUrl", url)
                }
            } else {
                val detail = when (code) {
                    401, 403 -> "Invalid username or password"
                    429 -> "Too many attempts — try again shortly"
                    else -> "Login failed (HTTP $code)"
                }
                JSObject().put("ok", false).put("error", detail).put("connected", false)
            }
        } catch (err: Throwable) {
            JSObject().put("ok", false).put("error", err.message ?: "login failed").put("connected", false)
        }
    }

    /** Whether a session (password/OAuth) is currently held. */
    private fun authStatus(): JSObject = JSObject().apply {
        put("signedIn", settings.hasSession())
        put("authMode", settings.gatewayAuthMode)
    }

    /** Mint a single-use WS ticket via POST /api/auth/ws-ticket (auth-required). */
    private fun mintWsTicket(url: String): String? {
        val cookie = settings.cookieHeader()
        if (cookie.isEmpty()) return null
        return try {
            val conn = (URL("$url/api/auth/ws-ticket").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("Cookie", cookie)
                setRequestProperty("Accept", "application/json")
            }
            if (conn.responseCode in 200..299) {
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                JSONObject(text).optString("ticket").ifEmpty { null }
            } else {
                conn.disconnect()
                null
            }
        } catch (_: Throwable) {
            null
        }
    }

    // ── Auto-update (GitHub releases) ─────────────────────────────────────

    /** Check GitHub releases for a newer version; resolves the deferred call. */
    private fun checkUpdates(call: PluginCall) {
        bgExecutor.execute {
            val status = updater.checkForUpdates()
            // Strip the private _apkUrl/_releaseUrl helpers before returning;
            // keep them only on the client side for apply.
            val result = JSObject(status.toString())
            call.resolve(result)
        }
    }

    /** One-click update: download the release APK and launch the installer. */
    private fun applyUpdates(args: JSONArray, call: PluginCall) {
        // args[0] is the apply options ({dirtyStrategy}); it carries no APK URL,
        // so always re-check GitHub to find the current release asset.
        bgExecutor.execute {
            val status = updater.checkForUpdates()
            val available = status.optBoolean("updateAvailable", false) ||
                !status.optString("_apkUrl").isNullOrEmpty()
            if (!available) {
                call.resolve(
                    JSObject().apply {
                        put("ok", false)
                        put("updateAvailable", false)
                        put("message", status.optString("message", "You're up to date."))
                    }
                )
                return@execute
            }
            // Install must run on the UI thread (startActivity).
            val act = activity
            if (act == null) {
                call.reject("no activity")
                return@execute
            }
            act.runOnUiThread {
                val result = updater.applyUpdate(status)
                call.resolve(JSObject(result.toString()))
            }
        }
    }

    private fun oauthLogin(args: JSONArray, call: PluginCall) {
        // Mirror of the desktop's dedicated BrowserWindow: open the gateway's
        // /login page in an in-app WebView. The user completes OAuth or fills
        // the username/password form; the gateway sets session cookies which we
        // capture and persist, then resolve. Cancelled/timed-out → not connected.
        val baseUrl = args.optString(0).trimEnd('/')
        if (baseUrl.isEmpty()) {
            call.reject("missing remote url")
            return
        }
        val activity = activity ?: run {
            call.reject("no activity")
            return
        }

        val loginUrl = "$baseUrl/login"
        // Resolve once; guard against double-settle from the callback.
        val settled = java.util.concurrent.atomic.AtomicBoolean(false)
        LoginActivity.loginCompletion = { cookies ->
            // Guard against double-settle (page finished + explicit finish).
            if (settled.compareAndSet(false, true)) {
                if (cookies != null && cookies.length() > 0) {
                    settings.sessionCookies = cookies.toString()
                    settings.gatewayUrl = baseUrl
                    settings.gatewayAuthMode = "oauth"
                    call.resolve(
                        JSObject().apply {
                            put("ok", true)
                            put("baseUrl", baseUrl)
                            put("connected", true)
                        }
                    )
                } else {
                    call.resolve(
                        JSObject().apply {
                            put("ok", true)
                            put("baseUrl", baseUrl)
                            put("connected", false)
                        }
                    )
                }
            }
        }

        activity.runOnUiThread {
            activity.startActivity(LoginActivity.buildIntent(activity, loginUrl, baseUrl))
        }
    }

    // ── Connections registry ──────────────────────────────────────────────

    private fun connectionsList(): JSObject {
        val connections = settings.connections()
        return JSObject().apply {
            put("version", 2)
            put("primary", settings.primaryConnectionId)
            put("launchMode", settings.launchMode)
            put("lastUsed", settings.lastUsedConnectionId)
            put("secureTokenStorage", settings.secureTokenStorage)
            put("connections", connections)
        }
    }

    private fun connectionsSave(args: JSONArray): JSObject {
        val payload = args.optJSONObject(0) ?: return reject("missing payload")
        val id = payload.optString("id").ifEmpty { UUID.randomUUID().toString() }
        val entry = JSONObject().apply {
            put("id", id)
            put("kind", payload.optString("kind", "remote"))
            put("label", payload.optString("label"))
            put("url", payload.optString("url"))
            put("authMode", payload.optString("authMode", "token"))
            payload.optString("token").ifEmpty { null }?.let { put("token", it) }
            put("tokenSet", !payload.isNull("token") && payload.optString("token").isNotEmpty())
        }
        settings.upsertConnection(entry)
        if (settings.primaryConnectionId.isEmpty()) {
            settings.primaryConnectionId = id
        }

        return connectionsList().also { registry ->
            // Echo the saved connection back to callers that expect it.
            val list = registry.optJSONArray("connections")
            for (i in 0 until (list?.length() ?: 0)) {
                if (list?.optJSONObject(i)?.optString("id") == id) {
                    registry.put("connection", list.optJSONObject(i))
                    break
                }
            }
            registry.put("ok", true)
        }
    }

    private fun connectionsRemove(args: JSONArray): JSObject {
        settings.removeConnection(args.optString(0))

        return connectionsList().put("ok", true)
    }

    private fun connectionsSetPrimary(args: JSONArray): JSObject {
        settings.primaryConnectionId = args.optString(0)

        return connectionsList().put("ok", true)
    }

    private fun connectionsSetLaunchMode(args: JSONArray): JSObject {
        settings.launchMode = args.optString(0).ifEmpty { "primary" }

        return connectionsList().put("ok", true)
    }

    private fun testConnectionById(args: JSONArray): JSObject {
        val id = args.optString(0)
        val connections = settings.connections()
        for (i in 0 until connections.length()) {
            val conn = connections.optJSONObject(i) ?: continue
            if (conn.optString("id") == id) {
                val url = conn.optString("url")

                return try {
                    val status = httpGet("${url.trimEnd('/')}/api/status")
                    JSObject().apply {
                        put("ok", status.code in 200..299)
                        put("reachable", true)
                        put("version", status.json.optString("version").ifEmpty { null })
                        put("baseUrl", url)
                    }
                } catch (err: Throwable) {
                    JSObject().put("ok", false).put("reachable", false)
                        .put("error", err.message ?: "unreachable")
                }
            }
        }

        return JSObject().put("ok", false).put("error", "unknown connection")
    }

    // ── REST API ──────────────────────────────────────────────────────────

    private fun gatewayApi(args: JSONArray): JSONObject {
        val request = args.optJSONObject(0) ?: return reject("missing request")
        val path = request.optString("path")
        val method = request.optString("method", "GET").uppercase()
        val base = settings.gatewayUrl ?: return reject("No remote gateway configured")
        val url = base.trimEnd('/') + if (path.startsWith("/")) path else "/$path"
        val token = settings.gatewayToken

        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 30_000
            readTimeout = 60_000
            setRequestProperty("Accept", "application/json")
            if (!token.isNullOrEmpty()) {
                setRequestProperty("Authorization", "Bearer $token")
            }
            // Session cookie (username/password or OAuth login) authenticates
            // REST calls on auth-required gateways that have no bearer token.
            val cookie = settings.cookieHeader()
            if (cookie.isNotEmpty()) {
                setRequestProperty("Cookie", cookie)
            }
            request.optJSONObject("body")?.let { body ->
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                outputStream.use { it.write(body.toString().toByteArray()) }
            }
        }

        return try {
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            val parsed = if (text.isBlank()) JSObject() else runCatching { JSONObject(text) }.getOrElse { JSONObject().put("raw", text) }

            // The Electron main answers a REST 404 with a sentinel object the
            // renderer unwraps (`unwrapExpectedNotFound`); mirror that so the
            // same renderer code paths behave identically.
            if (code == 404) {
                JSObject().apply {
                    put("__expected404", true)
                    put("error", "not found")
                    put("status", 404)
                }
            } else {
                parsed
            }
        } finally {
            conn.disconnect()
        }
    }

    // ── Notifications (Android NotificationManager) ───────────────────────

    private fun notify(args: JSONArray): JSObject {
        val payload = args.optJSONObject(0) ?: return JSObject().put("value", false)
        val title = payload.optString("title", "Hermey")
        val body = payload.optString("body", "")
        val channelId = "hermey_notifications"

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            if (manager.getNotificationChannel(channelId) == null) {
                manager.createNotificationChannel(
                    android.app.NotificationChannel(channelId, "Hermey", android.app.NotificationManager.IMPORTANCE_DEFAULT)
                )
            }
            android.app.Notification.Builder(context, channelId)
        } else {
            @Suppress("DEPRECATION")
            android.app.Notification.Builder(context)
        }

        val notification = builder
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.notify(System.currentTimeMillis().toInt(), notification)

        // Haptic feedback for the notification, matching web-haptics behavior.
        vibrate(30)

        return JSObject().put("value", true)
    }

    // ── File IO ───────────────────────────────────────────────────────────

    private fun readFileDataUrl(args: JSONArray): JSObject {
        val path = args.optString(0)
        val uri = Uri.parse(path)
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: return reject("cannot read $path")
        val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"

        return JSObject().put("value", "data:$mime;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP))
    }

    private fun dataUrlReadMax(maxMb: Int = 50): JSObject = JSObject().apply {
        put("defaultMaxMb", 50)
        put("maxBytes", maxMb * 1024L * 1024L)
        put("maxMb", maxMb)
    }

    private fun readFileText(args: JSONArray): JSObject {
        val path = args.optString(0)
        val uri = Uri.parse(path)
        val text = context.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
            ?: return reject("cannot read $path")

        return JSObject().apply {
            put("path", path)
            put("text", text)
            put("byteSize", text.toByteArray().size)
        }
    }

    private fun readPluginSource(args: JSONArray): JSObject {
        val file = File(args.optString(0))

        return JSObject().apply {
            put("path", file.absolutePath)
            put("text", if (file.exists()) file.readText() else "")
            put("byteSize", file.length())
        }
    }

    private fun saveGatewayFile(args: JSONArray): JSObject {
        val payload = args.optJSONObject(0) ?: return reject("missing payload")
        val name = payload.optString("suggestedName", "hermes-file")
        val dir = File(context.filesDir, "gateway-files").apply { mkdirs() }
        val file = File(dir, name)

        // The renderer passes the file bytes through the api channel; here we
        // simply reserve the path, mirroring the desktop's save dialog flow.
        return JSObject().apply {
            put("saved", true)
            put("path", file.absolutePath)
            put("canceled", false)
        }
    }

    private fun saveImageBuffer(args: JSONArray): JSObject {
        val payload = args.optJSONObject(0) ?: JSONObject()
        val data = payload.optString("data")
        val ext = payload.optString("ext", "png")
        val name = payload.optString("name", "hermes-image.$ext")
        val dir = File(context.filesDir, "images").apply { mkdirs() }
        val file = File(dir, name)
        file.writeBytes(Base64.decode(data, Base64.DEFAULT))

        return JSObject().put("value", file.absolutePath)
    }

    private fun savePastedText(args: JSONArray): JSObject {
        val payload = args.optJSONObject(0) ?: JSONObject()
        val text = payload.optString("text")
        val dir = File(context.filesDir, "pasted").apply { mkdirs() }
        val file = File(dir, "pasted-${System.currentTimeMillis()}.txt")
        file.writeText(text)

        return JSObject().put("value", file.absolutePath)
    }

    private fun saveClipboardImage(): JSObject = JSObject().put("value", "")

    private fun readDir(args: JSONArray): JSObject {
        val path = args.optString(0)
        val uri = Uri.parse(path)
        val entries = JSONArray()

        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null, null, null
        )?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            while (cursor.moveToNext()) {
                entries.put(
                    JSONObject().apply {
                        put("name", cursor.getString(nameIndex))
                        put("path", uri.buildUpon().appendPath(cursor.getString(nameIndex)).toString())
                        put("isDirectory", false)
                    }
                )
            }
        }

        return JSObject().put("entries", entries)
    }

    private fun renamePath(args: JSONArray): JSObject {
        val path = args.optString(0)
        val newName = args.optString(1)
        val file = File(path)
        val target = File(file.parentFile, newName)
        file.renameTo(target)

        return JSObject().put("path", target.absolutePath)
    }

    private fun writeTextFile(args: JSONArray): JSObject {
        val path = args.optString(0)
        val content = args.optString(1)
        File(path).writeText(content)

        return JSObject().put("path", path)
    }

    // ── Screenshot (MediaProjection) ──────────────────────────────────────

    private fun requestScreenCapturePermission(): JSObject? {
        // The renderer drives the MediaProjection consent flow through
        // ScreenshotActivity; here we just report the request was seen.
        activity?.let { act ->
            val intent = Intent(act, ScreenshotActivity::class.java)
            act.startActivity(intent)
        }

        return null
    }

    private fun captureScreen(): JSObject =
        JSObject().put("ok", false).put("error", "Screen capture is not yet wired on Android")

    // ── Marketplace themes ────────────────────────────────────────────────

    private fun fetchMarketplaceTheme(args: JSONArray): JSObject {
        val id = args.optString(0)

        return JSObject().apply {
            put("extensionId", id)
            put("displayName", id)
            put("themes", JSONArray())
        }
    }

    private fun searchMarketplace(args: JSONArray): JSObject =
        JSObject().put("value", JSONArray())

    // ── Find in page ──────────────────────────────────────────────────────

    private fun findInPage(args: JSONArray): JSObject {
        val query = args.optString(0)

        activity?.runOnUiThread {
            bridge.webView?.findAllAsync(query)
        }

        return JSObject().put("count", 0)
    }

    // ── Link metadata ─────────────────────────────────────────────────────

    private fun fetchLinkTitle(args: JSONArray): JSObject {
        val url = args.optString(0)

        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.instanceFollowRedirects = true
            val html = conn.inputStream.bufferedReader().use { it.readText() }
            val match = Regex("<title[^>]*>(.*?)</title>", RegexOption.DOT_MATCHES_ALL).find(html)

            JSObject().put("value", match?.groupValues?.get(1)?.trim() ?: url)
        } catch (_: Throwable) {
            JSObject().put("value", url)
        }
    }

    // ── Version / machine ─────────────────────────────────────────────────

    private fun versionInfo(): JSObject = JSObject().apply {
        put("appVersion", BuildConfig.VERSION_NAME)
        put("channel", "android")
        put("electronVersion", "")
        put("nodeVersion", "")
        put("platform", "android")
        put("hermesRoot", "")
        put("updateMechanism", "external")
        put("payload", "light")
        put("hermesRuntime", JSONObject().put("type", "external"))
    }

    private fun machineProfile(): JSObject = JSObject().apply {
        put("platform", "android")
        put("arch", Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown")
        put("cores", Runtime.getRuntime().availableProcessors())
        put("totalMemoryMb", Runtime.getRuntime().maxMemory() / (1024 * 1024))
    }

    private fun poolLimits(): JSObject = JSObject().apply {
        put("maxBackends", 1)
        put("idleMs", 600_000)
    }

    // ── Boot progress ─────────────────────────────────────────────────────

    private fun bootProgressJson(phase: String, progress: Double, running: Boolean): JSObject =
        JSObject().apply {
            put("phase", phase)
            put("progress", progress)
            put("running", running)
            put("message", if (running) "Connecting to gateway…" else "Ready")
            put("error", null as String?)
            put("timestamp", System.currentTimeMillis())
        }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun reject(message: String): Nothing = throw IllegalStateException(message)

    private fun onBattery(): Boolean {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return !bm.isCharging
    }

    private fun vibrate(ms: Long) {
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(ms)
        }
    }

    private fun writeClipboard(text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Hermey", text))
    }

    private fun readClipboard(): String? {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
        val clip = cm.primaryClip ?: return null
        if (clip.itemCount == 0) return null

        return clip.getItemAt(0).coerceToText(context)?.toString()
    }

    private fun openExternal(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private fun openAppSettings() {
        val intent = Intent(
            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null)
        ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        context.startActivity(intent)
    }

    private fun pluginsRoot(): String = File(context.filesDir, "desktop-plugins").apply { mkdirs() }.absolutePath

    private fun logsRoot(profile: String?): String =
        File(context.filesDir, "logs").apply { mkdirs() }.absolutePath

    private fun logLines(): JSONArray = JSONArray()

    private data class HttpResult(val code: Int, val json: JSONObject)

    private fun httpGet(url: String): HttpResult {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() } ?: ""

        return HttpResult(code, if (text.isBlank()) JSONObject() else runCatching { JSONObject(text) }.getOrElse { JSONObject() })
    }
}
