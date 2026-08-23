@file:OptIn(DexKitExperimentalApi::class)

package io.github.nexalloy

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.Context.MODE_PRIVATE
import android.os.Build
import android.os.Bundle
import android.webkit.WebView
import app.morphe.extension.shared.Logger
import app.morphe.extension.shared.ResourceUtils
import app.morphe.extension.shared.Utils
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import io.github.nexalloy.BuildConfig.DEBUG
import io.github.nexalloy.morphe.Fingerprint
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.DexKitCacheBridge
import org.luckypray.dexkit.annotations.DexKitExperimentalApi
import org.luckypray.dexkit.result.ClassData
import org.luckypray.dexkit.result.FieldData
import org.luckypray.dexkit.result.MethodData
import org.luckypray.dexkit.wrap.DexClass
import org.luckypray.dexkit.wrap.DexField
import org.luckypray.dexkit.wrap.DexMethod
import java.lang.reflect.Constructor
import java.lang.reflect.Member
import java.lang.reflect.Method
import kotlin.reflect.KProperty0
import kotlin.system.measureTimeMillis

typealias FindFunc = DexKitBridge.() -> Any
typealias FindClassFunc = DexKitBridge.() -> ClassData
typealias FindMethodFunc = DexKitBridge.() -> MethodData
typealias FindMethodListFunc = DexKitBridge.() -> List<MethodData>
typealias FindFieldFunc = DexKitBridge.() -> FieldData

fun patch(
    name: String = "",
    description: String = "",
    use: Boolean = true,
    category: String? = null,
    func: PatchExecutor.() -> Unit
) =
    Patch(name, description, use, category, func)

/**
 * Preference key for the per-app master switch, shared between the settings UI and
 * [PatchExecutor]. Deliberately prefixed so it cannot collide with a patch name.
 */
const val KEY_APP_PATCHING_ENABLED = "__nexalloy_app_patching_enabled"

/** Preference key for the toggle haptic, read by the settings UI only. */
const val KEY_HAPTICS_ENABLED = "__nexalloy_haptics_enabled"

class Patch(
    val name: String,
    val description: String,
    val use: Boolean,
    /**
     * Optional grouping for the settings list, e.g. "Ads", "Feed", "Player".
     *
     * The in-app Morphe settings group patches this way; the module's own list was a
     * single flat column of the same patches. Adding the field is what makes grouping
     * possible without a flag day: the UI groups by category where one is set and
     * falls back to a single list otherwise, so patches can be categorised as they
     * are touched rather than all at once.
     */
    val category: String? = null,
    val run: PatchExecutor.() -> Unit
)

interface IHook {
    val classLoader: ClassLoader

    fun DexMethod.hookMethod(callback: XC_MethodHook) {
        XposedBridge.hookMethod(toMember(), callback)
    }

    fun DexMethod.hookMethod(block: HookDsl<IHookCallback>.() -> Unit) {
        toMember().hookMethod(block)
    }

    fun DexClass.toClass() = getInstance(classLoader)
    fun DexMethod.toMethod(): Method {
        var clz = classLoader.loadClass(className)
        do {
            return XposedHelpers.findMethodExactIfExists(clz, name, *paramTypeNames.toTypedArray())
                ?: continue
        } while (clz.superclass.also { clz = it } != null)
        throw NoSuchMethodException("Method $this not found")
    }

    fun DexMethod.toConstructor(): Constructor<*> {
        var clz = classLoader.loadClass(className)
        do {
            return XposedHelpers.findConstructorExactIfExists(clz, *paramTypeNames.toTypedArray())
                ?: continue
        } while (clz.superclass.also { clz = it } != null)
        throw NoSuchMethodException("Method $this not found")
    }

    fun DexMethod.toMember(): Member {
        return when {
            isMethod -> toMethod()
            isConstructor -> toConstructor()
            else -> throw NotImplementedError()
        }
    }

    fun DexField.toField() = getFieldInstance(classLoader)
}

@Suppress("UNCHECKED_CAST")
class SharedPrefCache(app: Application) : DexKitCacheBridge.Cache {
    val pref = app.getSharedPreferences("xpmorphe", MODE_PRIVATE)!!
    private val map = mutableMapOf<String, String>().apply {
        putAll(pref.all as Map<String, String>)
    }

    override fun clearAll() {
        map.clear()
    }

    override fun getString(key: String, default: String?): String? = map.getOrDefault(key, default)

    override fun getAllKeys(): Collection<String> = map.keys

    override fun getStringList(
        key: String, default: List<String>?
    ): List<String>? =
        map.getOrDefault(key, null)?.takeIf(String::isNotBlank)?.split('|') ?: default

    override fun putString(key: String, value: String) {
        map.put(key, value)
    }

    override fun putStringList(key: String, value: List<String>) {
        map.put(key, value.joinToString("|"))
    }

    override fun remove(key: String) {
        map.remove(key)
    }

    fun saveCache() {
        val edit = pref.edit()
        edit.clear()
        map.forEach { (k, v) ->
            edit.putString(k, v)
        }
        edit.commit()
    }
}

class DependedHookFailedException(
    subHookName: String, exception: Throwable
) : Exception("Depended hook $subHookName failed.", exception)

@SuppressLint("CommitPrefEdits")
class PatchExecutor(val appContext: Application, val lpparam: LoadPackageParam) : IHook {
    override val classLoader = lpparam.classLoader!!

    /**
     * @see io.github.nexalloy.activity.AppPatchSettingsActivity.AppPatchSettingsFragment.onCreate
     * */
    private val patchPreferencesFile = XSharedPreferences(
        BuildConfig.APPLICATION_ID, lpparam.packageName
    )

    private val patchPreferences = patchPreferencesFile.takeIf { it.file.canRead() }

    /**
     * True only when the user's per-patch choices EXIST but could not be read.
     *
     * Every patch then falls back to its shipped default, which for most of them is
     * ON -- so a patch the user deliberately switched OFF gets applied anyway. That
     * used to happen silently. It cannot be fixed by refusing to patch (a module that
     * does nothing, also silently, is not better), so it is reported instead: once in
     * the log, and once on screen, because the visible symptom otherwise is "settings
     * do nothing" with no explanation anywhere.
     *
     * The `exists()` half matters. `canRead()` is false for a file that was never
     * created, which is the normal state of every app whose patches the user has not
     * customised -- reporting that as a failure fired the warning on a clean install
     * and told people something was broken when nothing was. No file means no choices
     * to lose, and the defaults are exactly right.
     */
    private val preferencesUnreadable =
        patchPreferences == null && patchPreferencesFile.file.exists()

    private lateinit var patches: Array<Patch>
    private val appliedPatches = mutableSetOf<Patch>()
    private val failedPatches = mutableListOf<Patch>()

    // cache
    private val moduleRel = BuildConfig.COMMIT_HASH
    private var cache = SharedPrefCache(appContext)
    private var dexkit = run {
        System.loadLibrary("dexkit")
        DexKitCacheBridge.init(cache)
        DexKitCacheBridge.create("", lpparam.appInfo.sourceDir)
    }

    fun applyPatches(patches: Array<Patch>) {
        this.patches = patches
        val t = measureTimeMillis {
            loadCacheIfValid()
            try {
                executePatches()
                finalizePatching()
                logDebugInfo()
            } finally {
                dexkit.close()
            }
        }
        Logger.printDebug { "${lpparam.packageName} handleLoadPackage: ${t}ms" }
    }

    @Suppress("UNCHECKED_CAST")
    private fun loadCacheIfValid() {
        // cache by host update time + module version
        // also no cache if is DEBUG
        val packageInfo = appContext.packageManager.getPackageInfo(appContext.packageName, 0)

        val id = "${packageInfo.lastUpdateTime}-$moduleRel"
        val cachedId = cache.getString("id", null)
        val isCached = cachedId.equals(id) && !DEBUG

        Logger.printInfo { "cache ID : $id" }
        Logger.printInfo { "cached ID: ${cachedId ?: ""}" }
        Logger.printInfo { "Using cached keys: $isCached" }

        if (!isCached) {
            cache.clearAll()
            cache.putString("id", id)
            Utils.showToastLong("NexAlloy is initializing, please wait...")
        }
    }

    private fun executePatches() {
        // Per-app master switch. There was no way to say "leave this app alone for
        // now" short of clearing every patch one by one and losing the selection --
        // which is the first thing anyone reaches for when working out whether the
        // module is behind an app misbehaving. Defaults to on, so an app with no
        // stored value behaves exactly as before.
        if (patchPreferences?.getBoolean(KEY_APP_PATCHING_ENABLED, true) == false) {
            XposedBridge.log(
                "NexAlloy: patching disabled for ${lpparam.packageName} by its master switch"
            )
            return
        }
        patches.forEach { hook ->
            if (appliedPatches.contains(hook)) return@forEach
            /**
             * @see io.github.nexalloy.activity.AppPatchSettingsActivity.AppPatchSettingsFragment.onCreate
             * */
            val isEnabled = patchPreferences?.getBoolean(hook.name, hook.use) ?: hook.use
            if (!isEnabled) return@forEach // Pref Key
            runCatching { hook.run(this) }.onFailure { err ->
                // One line, naming the patch. This used to be XposedBridge.log(err),
                // which dumps the whole stack -- so a patch that no longer matches its
                // target printed ~25 frames of R8-obfuscated trace into LSPosed's ERROR
                // log on every single app start, for a failure that is already handled
                // (it lands in failedPatches and is reported in the summary toast).
                // The result read like a crash and identified nothing: the frames are
                // all `c60.i`, `pc.h`, `tm.h`. The patch NAME is the one thing worth
                // knowing, and it was the one thing missing.
                XposedBridge.log(
                    "NexAlloy: patch \"${hook.name}\" failed on ${lpparam.packageName}: " +
                        "${err.javaClass.simpleName}: ${err.message}"
                )
                // The full trace is still there when explicitly debugging.
                if (DEBUG) XposedBridge.log(err)
                failedPatches.add(hook)
            }.onSuccess {
                appliedPatches.add(hook)
            }
        }
    }

    private fun finalizePatching() {
        cache.saveCache()
        if (preferencesUnreadable) {
            XposedBridge.log(
                "NexAlloy: could not read patch preferences for ${lpparam.packageName} " +
                    "(${BuildConfig.APPLICATION_ID}/${lpparam.packageName}.xml is unreadable). " +
                    "Every patch used its default state, so per-patch settings had no effect."
            )
            Utils.showToastLong(
                "NexAlloy: patch settings unreadable — defaults applied. Open the module " +
                    "settings once, then force stop ${lpparam.packageName}."
            )
        }
        val success = failedPatches.isEmpty()
        if (!success) {
            XposedBridge.log("${lpparam.appInfo.packageName} version: ${getAppVersion()}")
            Utils.showToastLong("Error while apply following patches:\n${failedPatches.joinToString { it.name }}")
        }
    }

    private fun logDebugInfo() {
        val success = failedPatches.isEmpty()
        if (DEBUG) {
            XposedBridge.log("${lpparam.appInfo.packageName} version: ${getAppVersion()}")
            if (success) {
                Utils.showToastLong("apply patches success")
            }
        }
    }

    private fun getAppVersion(): String {
        val packageInfo = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        val versionName = packageInfo.versionName
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION") packageInfo.versionCode
        }
        return "$versionName ($versionCode)"
    }

    fun dependsOn(vararg patches: Patch) {
        patches.forEach { hook ->
            if (appliedPatches.contains(hook)) return@forEach
            runCatching { (hook.run(this)) }.onFailure { err ->
                throw DependedHookFailedException(hook.name, err)
            }.onSuccess {
                appliedPatches.add(hook)
            }
        }
    }

    val KProperty0<FindMethodFunc>.dexMethod
        get() = getDexMethod(this.name, this.get())

    val KProperty0<FindMethodFunc>.method
        get() = dexMethod.toMethod()

    val KProperty0<FindMethodFunc>.constructor
        get() = dexMethod.toConstructor()

    val KProperty0<FindMethodFunc>.member
        get() = dexMethod.toMember()

    val KProperty0<FindMethodFunc>.memberOrNull
        get() = runCatching { this.member }.getOrNull()

    fun KProperty0<FindMethodFunc>.hookMethod(block: HookDsl<IHookCallback>.() -> Unit) {
        dexMethod.hookMethod(block)
    }

    fun KProperty0<FindMethodFunc>.hookMethod(callback: XC_MethodHook) {
        dexMethod.hookMethod(callback)
    }

    val KProperty0<FindMethodListFunc>.dexMethodList
        get() = getDexMethods(this.name, this.get())

    val KProperty0<FindFieldFunc>.dexField
        get() = getDexField(this.name, this.get())

    val KProperty0<FindFieldFunc>.field
        get() = dexField.toField()

    val KProperty0<FindFieldFunc>.declaredClass
        get() = classLoader.loadClass(dexField.declaredClassName)

    val KProperty0<FindFieldFunc>.type
        get() = classLoader.loadClass(dexField.className)

    val KProperty0<FindClassFunc>.dexClass
        get() = getDexClass(this.name, this.get())

    val KProperty0<FindClassFunc>.clazz
        get() = dexClass.toClass()

    // Fingerprint object extensions

    private val Fingerprint.cacheKey
        get() = this::class.simpleName ?: error("Anonymous Fingerprint has no cache key")

    fun Fingerprint.hookMethod(block: HookDsl<IHookCallback>.() -> Unit) {
        getDexMethod(cacheKey) { this@hookMethod.run() }.hookMethod(block)
    }

    fun Fingerprint.hookMethod(callback: XC_MethodHook) {
        getDexMethod(cacheKey) { this@hookMethod.run() }.hookMethod(callback)
    }

    val Fingerprint.dexMethod get() = getDexMethod(cacheKey) { this@dexMethod.run() }

    val Fingerprint.member get() = dexMethod.toMember()

    val Fingerprint.memberOrNull get() = runCatching { this.member }.getOrNull()

    val Fingerprint.method get() = dexMethod.toMethod()

    val Fingerprint.declaredClass get() = classLoader.loadClass(dexMethod.declaredClassName)

    val Fingerprint.constructor get() = dexMethod.toConstructor()

    private inline fun <reified T : Any> wrapFind(
        key: String,
        crossinline funcFunc: DexKitBridge.() -> T,
        crossinline serializer: (T) -> String
    ): DexKitBridge.() -> T? {
        return {
            try {
                funcFunc().also { Logger.printInfo { "$key Matches: ${serializer(it)}" } }
            } catch (e: Exception) {
                Logger.printInfo({ "Fingerprint $key Not Found" }, e)
                null
            }
        }
    }

    private inline fun <reified T : Any> wrapFindList(
        key: String,
        crossinline funcFunc: DexKitBridge.() -> List<T>,
        crossinline serializer: (T) -> String
    ): DexKitBridge.() -> List<T> {
        return {
            try {
                funcFunc().also {
                    Logger.printInfo { "$key Matches: ${it.joinToString { serializer(it) }}" }
                }
            } catch (e: Exception) {
                Logger.printInfo({ "Fingerprint $key Not Found" }, e)
                emptyList()
            }
        }
    }

    private inline fun getDexClass(
        key: String, crossinline findFunc: DexKitBridge.() -> ClassData
    ): DexClass = dexkit.getClassDirectOrNull(key, wrapFind(key, findFunc) { it.descriptor })!!

    private inline fun getDexMethod(
        key: String, crossinline findFunc: DexKitBridge.() -> MethodData
    ): DexMethod = dexkit.getMethodDirectOrNull(key, wrapFind(key, findFunc) { it.descriptor })!!

    private inline fun getDexField(
        key: String, crossinline findFunc: DexKitBridge.() -> FieldData
    ): DexField = dexkit.getFieldDirectOrNull(key, wrapFind(key, findFunc) { it.descriptor })!!

    private inline fun getDexMethods(
        key: String, crossinline findFunc: DexKitBridge.() -> List<MethodData>
    ): List<DexMethod> = dexkit.getMethodsDirectOrEmpty(
        key, wrapFindList(key, findFunc) { it.descriptor })
}

val ExtensionResourceHook = patch {
    appContext.addModuleAssets()
    ResourceUtils.fallbackPackageName = BuildConfig.APPLICATION_ID

    appContext.callMethod(
        "registerActivityLifecycleCallbacks", object : Application.ActivityLifecycleCallbacks {
            var handleWebView: Boolean = false

            override fun onActivityCreated(activity: Activity, bundle: Bundle?) {
                Logger.printDebug { "onActivityCreated $activity" }
                if (!handleWebView) {
                    WebView(activity).destroy()
                    appContext.addModuleAssets()
                    handleWebView = true
                }

                activity.addModuleAssets()
            }

            override fun onActivityDestroyed(activity: Activity) {
                Logger.printDebug { "onActivityDestroyed $activity" }
            }

            override fun onActivityPaused(activity: Activity) {
                Logger.printDebug { "onActivityPaused $activity" }
            }

            override fun onActivityResumed(activity: Activity) {
                Logger.printDebug { "onActivityResumed $activity" }
                activity.addModuleAssets()
            }

            override fun onActivitySaveInstanceState(activity: Activity, bundle: Bundle) {
                Logger.printDebug { "onActivitySaveInstanceState $activity" }
            }

            override fun onActivityStarted(activity: Activity) {
                Logger.printDebug { "onActivityStarted $activity" }
            }

            override fun onActivityStopped(activity: Activity) {
                Logger.printDebug { "onActivityStopped $activity" }
            }
        })
}
