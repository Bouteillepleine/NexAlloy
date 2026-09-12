package io.github.nexalloy.common

import android.R
import android.app.Activity
import android.app.AlertDialog
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import android.text.Html
import android.text.method.LinkMovementMethod
import android.widget.TextView
import app.morphe.extension.shared.Logger
import app.morphe.extension.shared.Utils
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import de.robv.android.xposed.XposedHelpers
import fuel.Fuel
import fuel.get
import io.github.nexalloy.BuildConfig
import io.github.nexalloy.hookMethod
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.io.readString
import java.lang.ref.WeakReference
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random

/**
 * Mirrors the subset of the GitHub release payload this checker needs.
 *
 * Gson writes fields reflectively and ignores Kotlin nullability, so every field is declared
 * nullable with a default. Treating them as non-null produced a `NullPointerException` deep
 * inside the dialog code whenever GitHub omitted one (for example a release created without
 * notes has no `body_html`).
 */
data class ReleaseInfo(
    @SerializedName("tag_name") val tagName: String? = null,
    @SerializedName("body_html") val releaseNoteHtml: String? = null,
    @SerializedName("html_url") val releaseUrl: String? = null
)

data class VersionInfo(val versionCode: Int, val versionName: String) {
    companion object {
        fun fromTagName(tagName: String): VersionInfo {
            val versionCode: Int
            val versionName: String

            val split = tagName.split('-', limit = 2)
            if (split.count() == 2) {
                // VersionCode-VersionName
                versionCode = split[0].toIntOrNull() ?: 0
                versionName = split[1]
            } else {
                // X.Y.Z, Z is versionCode
                versionCode = tagName.split('.').last().toIntOrNull() ?: 0
                versionName = tagName
            }
            return VersionInfo(versionCode, versionName)
        }
    }
}

const val OWNER = "NexAlloy"
const val REPO = "NexAlloy"
const val currentVersionCode = BuildConfig.VERSION_CODE

/** Odds that a host-app launch performs a background update check: 1 in this many. */
private const val AUTO_CHECK_ODDS = 10

class UpdateChecker : CoroutineScope {
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.IO + CoroutineExceptionHandler { _, err ->
            Logger.printException({ "coroutineContext error" }, err)
        }

    private var currentActivity = WeakReference<Activity>(null)
    private var latestVersionInfo: VersionInfo? = null
    private var latestRelease: ReleaseInfo? = null

    var runOnce = false

    fun setActivity(activity: Activity) {
        currentActivity = WeakReference(activity)
    }

    fun hookNewActivity() {

        XposedHelpers.findMethodExact(
            Instrumentation::class.java,
            "newActivity",
            ClassLoader::class.java,
            String::class.java,
            Intent::class.java
        ).hookMethod {
            after {
                if (!runOnce) {
                    runOnce = true
                    currentActivity = WeakReference(it.result as Activity)
                    autoCheckUpdate()
                }
            }
        }
    }

    fun autoCheckUpdate() {
        if (Random.nextInt(0, AUTO_CHECK_ODDS) != 0) return
        Logger.printInfo { "start auto check update." }
        runCatching { checkUpdate() }
    }

    /**
     * @param silent when false the user explicitly asked for the check, so every outcome —
     * including failures — has to be reported back. Previously only the "already up to date"
     * branch spoke up, which made the "Check for update" button look dead whenever the request
     * failed (no network, GitHub rate limit, no published stable release).
     */
    fun checkUpdate(silent: Boolean = true) {
        launch {
            try {
                val response = Fuel.get(
                    "https://api.github.com/repos/$OWNER/$REPO/releases/latest",
                    headers = mapOf("Accept" to "application/vnd.github.html+json")
                )
                if (response.statusCode != 200) {
                    Logger.printException { "Failed to fetch latest release: HTTP ${response.statusCode}" }
                    if (!silent) reportFailure(describeHttpFailure(response.statusCode))
                    return@launch
                }

                val content = response.source.readString()
                Logger.printDebug { content }
                val release = Gson().fromJson(content, ReleaseInfo::class.java)
                val tagName = release?.tagName
                if (tagName.isNullOrBlank()) {
                    Logger.printException { "Latest release has no tag name" }
                    if (!silent) reportFailure("Could not read the latest release.")
                    return@launch
                }

                latestRelease = release
                val versionInfo = VersionInfo.fromTagName(tagName)
                latestVersionInfo = versionInfo
                Logger.printDebug { "$versionInfo" }
                if (versionInfo.versionCode > currentVersionCode) {
                    Logger.printInfo { "Found new version of NexAlloy $tagName" }
                    showUpdateDialog()
                } else {
                    Logger.printInfo { "no update found for NexAlloy" }
                    if (!silent) Utils.showToastLong("NexAlloy is up to date.")
                }
            } catch (e: Throwable) {
                Logger.printException({ "checkUpdate error" }, e)
                if (!silent) reportFailure("Could not reach GitHub. Check your connection.")
            }
        }
    }

    private fun describeHttpFailure(statusCode: Int) = when (statusCode) {
        403, 429 -> "GitHub rate limit reached. Try again later."
        404 -> "No published release found."
        else -> "Update check failed (HTTP $statusCode)."
    }

    private fun reportFailure(message: String) {
        Utils.showToastLong(message)
    }

    @Deprecated("Test only.")
    fun showRelease(version: String) {
        launch {
            val response = Fuel.get(
                "https://api.github.com/repos/$OWNER/$REPO/releases/tags/$version",
                headers = mapOf("Accept" to "application/vnd.github.html+json")
            )
            if (response.statusCode != 200) {
                Logger.printException { "responseCode ${response.statusCode}" }
                return@launch
            }

            val content = response.source.readString()
            Logger.printDebug { content }

            val release = Gson().fromJson(content, ReleaseInfo::class.java) ?: return@launch
            val tagName = release.tagName ?: return@launch
            latestRelease = release
            latestVersionInfo = VersionInfo.fromTagName(tagName)
            showUpdateDialog()
        }
    }

    /** The activity, or null once it has gone away or started tearing down. */
    private fun liveActivity(): Activity? =
        currentActivity.get()?.takeIf { !it.isFinishing && !it.isDestroyed }

    private fun showUpdateDialog() {
        launch(Dispatchers.Main) {
            try {
                // Showing a dialog on a finishing activity throws BadTokenException, which used to
                // be swallowed below and silently dropped the update notice.
                val activity = liveActivity() ?: return@launch
                val release = latestRelease ?: return@launch
                val versionInfo = latestVersionInfo ?: return@launch

                val theme =
                    if (Utils.isDarkModeEnabled()) R.style.Theme_DeviceDefault_Dialog_Alert
                    else R.style.Theme_DeviceDefault_Light_Dialog_Alert
                val dialog = AlertDialog.Builder(activity, theme)
                    .setTitle("Found new version of NexAlloy ${versionInfo.versionName}")
                    .setMessage(
                        Html.fromHtml(
                            release.releaseNoteHtml.orEmpty(),
                            Html.FROM_HTML_MODE_LEGACY
                        )
                    ).setPositiveButton(R.string.ok) { _, _ ->
                        openReleasePage()
                    }.setNegativeButton(activity.getString(R.string.cancel), null)
                    .create()
                dialog.show()
                dialog.findViewById<TextView>(R.id.message)?.movementMethod =
                    LinkMovementMethod.getInstance()
            } catch (e: Throwable) {
                Logger.printException({ "showUpdateDialog error" }, e)
            }
        }
    }

    private fun openReleasePage() {
        val url = latestRelease?.releaseUrl ?: return
        val activity = liveActivity() ?: return
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            activity.startActivity(intent)
        } catch (e: Exception) {
            Logger.printException({ "openReleasePage error" }, e)
            Utils.showToastLong("No app can open ${url}.")
        }
    }
}
