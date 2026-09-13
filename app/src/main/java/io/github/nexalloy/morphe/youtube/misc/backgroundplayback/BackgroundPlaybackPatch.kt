package io.github.nexalloy.morphe.youtube.misc.backgroundplayback

import app.morphe.extension.youtube.patches.BackgroundPlaybackPatch
import de.robv.android.xposed.XC_MethodReplacement.returnConstant
import io.github.nexalloy.morphe.shared.misc.settings.preference.SwitchPreference
import io.github.nexalloy.morphe.youtube.insertLiteralOverride
import io.github.nexalloy.morphe.youtube.misc.playservice.VersionCheck
import io.github.nexalloy.morphe.youtube.misc.playservice.is_20_29_or_greater
import io.github.nexalloy.morphe.youtube.misc.playservice.is_20_49_or_greater
import io.github.nexalloy.morphe.youtube.misc.playservice.is_21_04_or_greater
import io.github.nexalloy.morphe.youtube.misc.playservice.is_21_15_or_greater
import io.github.nexalloy.morphe.youtube.misc.playservice.is_21_21_or_greater
import io.github.nexalloy.morphe.youtube.misc.settings.PreferenceScreen
import io.github.nexalloy.patch
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager

@Volatile
private var screenInteractive = true

private val Context.isInteractive: Boolean
    get() = (getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive ?: true

private fun Context.registerScreenStateReceiver() {
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            screenInteractive = intent?.action != Intent.ACTION_SCREEN_OFF
        }
    }
    val filter = IntentFilter().apply {
        addAction(Intent.ACTION_SCREEN_ON)
        addAction(Intent.ACTION_SCREEN_OFF)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
    } else {
        registerReceiver(receiver, filter)
    }
}

val BackgroundPlayback = patch(
    name = "Remove background playback restrictions",
    description = "Removes restrictions on background playback, including playing kids videos in the background.",
) {

    dependsOn(VersionCheck)

    PreferenceScreen.SHORTS.addPreferences(
        SwitchPreference("morphe_shorts_disable_background_playback"),
    )

    BackgroundPlaybackManagerFingerprint.hookMethod {
        after {
            it.result = BackgroundPlaybackPatch.isBackgroundPlaybackAllowed(it.result as Boolean)
        }
    }

    ::backgroundPlaybackManagerShortsFingerprint.dexMethodList.forEach {
        it.hookMethod {
            after {
                it.result =
                    BackgroundPlaybackPatch.isBackgroundShortsPlaybackAllowed(it.result as Boolean)
            }
        }
    }

    // Enable background playback option in YouTube settings
    ::backgroundPlaybackSettingsSubFingerprint.hookMethod(returnConstant(true))

    // Prevents playback from resuming if it was interrupted from the notification
    // and the app was subsequently brought to the foreground.
    if (is_21_15_or_greater) {
        insertLiteralOverride(45770945L, BackgroundPlaybackPatch::isAutomaticForegroundPlaybackAllowed)
    }

    // Prevents playback from pausing when the overlay video settings is invoked.
    if (is_20_49_or_greater) {
        insertLiteralOverride(45741823L, BackgroundPlaybackPatch::isAutomaticPlaybackPauseInFlyout)
    }

    // Force allowing background play for Shorts.
    insertLiteralOverride(45415425, true)

    // Force allowing background play for videos labeled for kids.
    KidsBackgroundPlaybackPolicyControllerFingerprint.hookMethod(returnConstant(Unit))

    // Fix PiP buttons not working after locking/unlocking device screen.
    if (!is_21_21_or_greater) {
        insertLiteralOverride(45638483L)
    }

    if (is_20_29_or_greater) {
        // Client flag that interferes with background playback of some video types.
        // Exact purpose is not clear and it's used in ~ 100 locations.
        screenInteractive = appContext.isInteractive
        appContext.registerScreenStateReceiver()
        insertLiteralOverride(45698813L) { original ->
            if (screenInteractive) original else false
        }
    }

    if (is_21_04_or_greater) {
        // If NewPlayerTypeEnumFeatureFlagFingerprint is present and forced off then this flag
        // must also be disabled, otherwise the player is a black screen with no buttons and no playback.
        insertLiteralOverride(45752335L)
    }
}
