package io.github.nexalloy

import android.content.pm.PackageManager
import io.github.nexalloy.hoodles.morphe.alltrails.AllTrailsPatches
import io.github.nexalloy.morphe.music.YTMusicPatches
import io.github.nexalloy.morphe.reddit.RedditPatches
import io.github.nexalloy.morphe.youtube.YouTubePatches
import io.github.nexalloy.revanced.googlephotos.GooglePhotosPatches
import io.github.nexalloy.revanced.meta.MetaPatches
import io.github.nexalloy.revanced.photomath.PhotomathPatches
import io.github.nexalloy.revanced.strava.StravaPatches

class AppPatchInfo(val appName: String, val packageName: String, val patches: Array<Patch>)

val appPatchConfigurations = listOf(
    AppPatchInfo("YouTube", "com.google.android.youtube", YouTubePatches),
    AppPatchInfo("YT Music", "com.google.android.apps.youtube.music", YTMusicPatches),
    AppPatchInfo("Reddit", "com.reddit.frontpage", RedditPatches),
    AppPatchInfo("Google Photos", "com.google.android.apps.photos", GooglePhotosPatches),
    AppPatchInfo("Photomath", "com.microblink.photomath", PhotomathPatches),
    AppPatchInfo("Instagram", "com.instagram.android", MetaPatches),
    AppPatchInfo("Threads", "com.instagram.barcelona", MetaPatches),
    AppPatchInfo("Strava", "com.strava", StravaPatches),
    AppPatchInfo("AllTrails", "com.alltrails.alltrails", AllTrailsPatches),
)

val patchesByPackage = appPatchConfigurations.associate { it.packageName to it.patches }

fun appPatchInfoOf(appName: String?) = appPatchConfigurations.find { it.appName == appName }

/**
 * Patches a user can turn on and off.
 *
 * Unnamed patches are internal dependencies, and a leading `<` marks a patch that is deliberately
 * hidden from the UI. Both the patch list and the enabled/total counter have to apply the same
 * rule, otherwise the counter reports a total the list never shows.
 */
val AppPatchInfo.selectablePatches: List<Patch>
    get() = patches.filter { it.name.isNotEmpty() && !it.name.startsWith("<") }

fun AppPatchInfo.isInstalled(packageManager: PackageManager) = runCatching {
    packageManager.getPackageInfo(packageName, 0)
}.isSuccess
