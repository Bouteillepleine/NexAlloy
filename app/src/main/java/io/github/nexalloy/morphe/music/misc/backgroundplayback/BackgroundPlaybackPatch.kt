package io.github.nexalloy.morphe.music.misc.backgroundplayback

import de.robv.android.xposed.XC_MethodReplacement
import io.github.nexalloy.morphe.music.misc.playservice.is_9_46_or_greater
import io.github.nexalloy.morphe.music.misc.playservice.versionCheckPatch
import io.github.nexalloy.patch

val BackgroundPlayback = patch(
    name = "Remove background playback restrictions",
    description = "Removes restrictions on background playback, including playing kids videos in the background.",
) {
    dependsOn(versionCheckPatch)

    BackgroundPlaybackDisableFingerprint.hookMethod(XC_MethodReplacement.returnConstant(true))
    if (!is_9_46_or_greater) {
        KidsBackgroundPlaybackPolicyControllerFingerprint.hookMethod(XC_MethodReplacement.DO_NOTHING)
    }
}