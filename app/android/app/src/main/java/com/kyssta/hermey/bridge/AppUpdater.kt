package com.kyssta.hermey.bridge

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.kyssta.hermey.BuildConfig
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Android auto-updater — the mirror of the desktop's in-app updater.
 *
 * Checks the public GitHub releases feed for `kyssta-exe/hermey-rework`,
 * compares the latest release tag against the installed version, and offers a
 * one-click update: download the release APK, then hand it to the system
 * package installer. No app-store round trip, matching the desktop's
 * self-update feel.
 *
 * The repo is public precisely so the updater can read the release feed
 * without a token; the APK is signed with the release key stored in the
 * repo's CI secrets.
 */
class AppUpdater(private val context: Context) {

    companion object {
        private const val GITHUB_OWNER = "kyssta-exe"
        private const val GITHUB_REPO = "hermey-rework"
        private const val RELEASES_LATEST =
            "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"
        private const val APK_MIME = "application/vnd.android.package-archive"
    }

    /** Installed version name from BuildConfig, e.g. "0.0.2". */
    private fun installedVersion(): String = BuildConfig.VERSION_NAME

    /** `v0.0.2` / `0.0.2` → [0, 0, 2] for numeric comparison. */
    private fun parseVersion(raw: String): List<Int> =
        raw.trimStart('v', 'V', ' ')
            .split('.', '-', '+')
            .mapNotNull { it.trim().toIntOrNull() }

    /** True when `latest` is strictly newer than `installed`. */
    private fun isNewer(latest: String, installed: String): Boolean {
        val l = parseVersion(latest)
        val i = parseVersion(installed)
        val n = maxOf(l.size, i.size)
        for (idx in 0 until n) {
            val lv = l.getOrElse(idx) { 0 }
            val iv = i.getOrElse(idx) { 0 }
            if (lv != iv) return lv > iv
        }
        return false
    }

    /**
     * GET /releases/latest and compare. Returns a [DesktopUpdateStatus]-
     * shaped object the renderer's update surfaces already understand.
     */
    fun checkForUpdates(): JSONObject {
        return try {
            val conn = (URL(RELEASES_LATEST).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 15_000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "Hermey-Android-Updater")
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                conn.disconnect()
                return JSONObject().apply {
                    put("supported", true)
                    put("mechanism", "github")
                    put("updateAvailable", false)
                    put("error", "GitHub returned HTTP $code")
                    put("message", "Could not check for updates.")
                }
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()

            val release = JSONObject(body)
            val latestTag = release.optString("tag_name").ifEmpty { null }
            val installed = installedVersion()
            val apkAsset = release.optJSONArray("assets")
                ?.let { arr ->
                    (0 until arr.length())
                        .map { arr.getJSONObject(it) }
                        .firstOrNull { it.optString("name").endsWith(".apk") }
                }
            val apkUrl = apkAsset?.optString("browser_download_url")?.ifEmpty { null }
            val releaseUrl = release.optString("html_url").ifEmpty { null }

            val available = latestTag != null && isNewer(latestTag, installed)

            JSONObject().apply {
                put("supported", true)
                put("mechanism", "github")
                put("updateAvailable", available)
                put("branch", "release")
                put("currentVersion", installed)
                put("latestTag", latestTag)
                put("message", if (available) {
                    "Update available: $latestTag (installed $installed)."
                } else {
                    "You're on the latest version."
                })
                // Extra fields the Android updater consumes on apply.
                put("_apkUrl", apkUrl)
                put("_releaseUrl", releaseUrl)
            }
        } catch (err: Throwable) {
            JSONObject().apply {
                put("supported", true)
                put("mechanism", "github")
                put("updateAvailable", false)
                put("error", err.message ?: "update check failed")
                put("message", "Could not reach the update server.")
            }
        }
    }

    /**
     * Download the release APK and launch the system installer. Returns an
     * apply-result shaped object. The actual install runs in the system UI
     * (user taps Install); we can't force-install without special privileges.
     */
    fun applyUpdate(status: JSONObject): JSONObject {
        val apkUrl = status.optString("_apkUrl").ifEmpty { null }
            ?: status.optString("_releaseUrl").ifEmpty { null }
        if (apkUrl == null) {
            return JSONObject().apply {
                put("ok", false)
                put("error", "no download available")
                put("message", "Open the release page to update manually.")
            }
        }

        // If we only have a release page (no direct APK asset), open it.
        if (!apkUrl.endsWith(".apk")) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(apkUrl))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return JSONObject().apply {
                put("ok", true)
                put("manual", true)
                put("message", "Opened the release page to download the update.")
            }
        }

        return try {
            val apkFile = downloadApk(apkUrl)
            installApk(apkFile)
            JSONObject().apply {
                put("ok", true)
                put("message", "Downloaded update — follow the system prompt to install.")
            }
        } catch (err: Throwable) {
            JSONObject().apply {
                put("ok", false)
                put("error", err.message ?: "download failed")
                put("message", "Update download failed.")
            }
        }
    }

    private fun downloadApk(url: String): File {
        val cacheDir = File(context.cacheDir, "updates").apply { mkdirs() }
        val out = File(cacheDir, "hermey-update.apk")
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 30_000
            readTimeout = 120_000
            setRequestProperty("User-Agent", "Hermey-Android-Updater")
            // Follow GitHub's asset redirect to the CDN.
            instanceFollowRedirects = true
        }
        if (conn.responseCode !in 200..299) {
            conn.disconnect()
            throw IllegalStateException("download returned HTTP ${conn.responseCode}")
        }
        conn.inputStream.use { input ->
            FileOutputStream(out).use { fos -> input.copyTo(fos) }
        }
        conn.disconnect()
        return out
    }

    private fun installApk(file: File) {
        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
        } else {
            @Suppress("DEPRECATION")
            Uri.fromFile(file)
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
