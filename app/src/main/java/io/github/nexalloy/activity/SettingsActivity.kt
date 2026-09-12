@file:Suppress("DEPRECATION")

package io.github.nexalloy.activity

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.preference.Preference
import android.preference.PreferenceCategory
import android.preference.PreferenceFragment
import android.text.format.DateUtils
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import android.window.OnBackInvokedDispatcher
import app.morphe.extension.shared.Utils
import app.morphe.extension.shared.settings.preference.about.MorpheAboutPreference
import io.github.libxposed.service.XposedService
import io.github.nexalloy.AppPatchInfo
import io.github.nexalloy.BuildConfig
import io.github.nexalloy.R
import io.github.nexalloy.appPatchConfigurations
import io.github.nexalloy.common.UpdateChecker
import io.github.nexalloy.isInstalled
import io.github.nexalloy.selectablePatches
import kotlin.system.exitProcess

class SettingsActivity : Activity(), SettingApplication.ServiceStateListener {

    private var mService: XposedService? = null
    private lateinit var aboutPreference: MorpheAboutPreference

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT
            ) {
                onBackPressed()
            }
        }
        setContentView(R.layout.activity_settings)
        actionBar?.setDisplayShowHomeEnabled(true)

        Utils.setContext(this)
        aboutPreference = MorpheAboutPreference(this).apply {
            setTitle(R.string.about_title)
        }

        if (savedInstanceState != null) return

        fragmentManager.beginTransaction().replace(R.id.settings_container, SettingsFragment())
            .commit()
    }

    override fun onStart() {
        super.onStart()
        SettingApplication.addServiceStateListener(this, true)
    }

    override fun onStop() {
        SettingApplication.removeServiceStateListener(this)
        super.onStop()
    }

    override fun onServiceStateChanged(service: XposedService?) {
        mService = service
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.xp_settings_menu, menu)
        menu.findItem(R.id.menu_disable_auto_check).isVisible = false
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.menu_hide_icon).isChecked = isLauncherIconHidden()

        val menuDisableAutoCheck = menu.findItem(R.id.menu_disable_auto_check)
        val prefs = modulePreferences()
        if (prefs != null) {
            menuDisableAutoCheck.isChecked = prefs.getBoolean(PREF_DISABLE_AUTO_CHECK, false)
            menuDisableAutoCheck.isVisible = true
        } else {
            menuDisableAutoCheck.isVisible = false
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menu_about -> {
                aboutPreference.onPreferenceClickListener?.onPreferenceClick(aboutPreference)
                true
            }

            R.id.menu_hide_icon -> {
                val newChecked = !item.isChecked
                item.isChecked = newChecked
                packageManager.setComponentEnabledSetting(
                    launcherAliasName(),
                    if (newChecked) PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    else PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP
                )
                // The launcher icon is the only way back into these settings for most users, so
                // spell out the second half of the change instead of leaving them stranded.
                if (newChecked) {
                    Toast.makeText(this, R.string.hide_icon_summary, Toast.LENGTH_LONG).show()
                }
                true
            }

            R.id.menu_disable_auto_check -> {
                val prefs = modulePreferences()
                if (prefs == null) {
                    item.isVisible = false
                    return true
                }
                val newChecked = !item.isChecked
                item.isChecked = newChecked
                prefs.edit().putBoolean(PREF_DISABLE_AUTO_CHECK, newChecked).apply()
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun launcherAliasName() =
        ComponentName(this, SettingsActivity::class.java.name + "Alias")

    private fun isLauncherIconHidden() =
        packageManager.getComponentEnabledSetting(launcherAliasName()) ==
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED

    /** Remote preferences, or null when the Xposed service is not reachable. */
    private fun modulePreferences(): SharedPreferences? =
        runCatching { mService?.getRemotePreferences(MODULE_PREFS) }.getOrNull()

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        finishAndRemoveTask()
        exitProcess(0)
    }

    companion object {
        const val MODULE_PREFS = "prefs"
        const val PREF_DISABLE_AUTO_CHECK = "disable_auto_check_update"
    }

    @Suppress("OVERRIDE_DEPRECATION")
    class SettingsFragment : PreferenceFragment(), SettingApplication.ServiceStateListener {
        private var mService: XposedService? = null

        private var statusPreference: Preference? = null
        private var patchCategory: PreferenceCategory? = null

        @Deprecated("Deprecated in Java")
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)

            val rootScreen = preferenceManager.createPreferenceScreen(context)
            // Build in a fixed order regardless of when each section is attached, so the patch
            // list — the reason the screen exists — always sits above the informational entries.
            rootScreen.isOrderingAsAdded = false
            preferenceScreen = rootScreen

            Utils.setContext(context)

            addPreferencesFromResource(R.xml.license_prefs)
            val licensePreference = findPreference(LICENSE_PREF_KEY)?.also {
                rootScreen.removePreference(it)
            }

            // A PreferenceGroup resolves its PreferenceManager from its parent, so every category
            // has to be attached to the screen before any child is added to it.
            val aboutCategory = PreferenceCategory(context).apply {
                order = ORDER_ABOUT
                setTitle(R.string.about_category)
            }
            rootScreen.addPreference(aboutCategory)

            aboutCategory.addPreference(Preference(context).apply {
                setSummary(R.string.slogan_summary)
                isSelectable = false
            })

            aboutCategory.addPreference(Preference(context).apply {
                setTitle(R.string.faq_title)
                setSummary(R.string.faq_summary)
                intent = Intent(Intent.ACTION_VIEW, Uri.parse(FAQ_URL))
            })

            aboutCategory.addPreference(Preference(context).apply {
                setSummary(R.string.powered_by_morphe_summary)
                intent = Intent(Intent.ACTION_VIEW, Uri.parse(MORPHE_URL))
            })

            licensePreference?.let { aboutCategory.addPreference(it) }

            val versionCategory = PreferenceCategory(context).apply {
                order = ORDER_VERSION
                setTitle(R.string.version_category)
            }
            rootScreen.addPreference(versionCategory)

            versionCategory.addPreference(Preference(context).apply {
                setTitle(R.string.check_for_update_title)
                val version = getString(
                    R.string.version_summary_short,
                    BuildConfig.VERSION_NAME,
                    BuildConfig.COMMIT_HASH,
                    BuildConfig.BUILD_TYPE
                )
                // COMMIT_DATE is 0 when the APK was built outside a git checkout.
                summary = if (BuildConfig.COMMIT_DATE > 0) getString(
                    R.string.version_summary_built,
                    version,
                    DateUtils.getRelativeTimeSpanString(BuildConfig.COMMIT_DATE * 1000)
                ) else version
                setOnPreferenceClickListener {
                    activity?.let { host ->
                        UpdateChecker().apply {
                            setActivity(host)
                            checkUpdate(silent = false)
                        }
                    }
                    true
                }
            })

            activity?.let { host ->
                UpdateChecker().apply {
                    setActivity(host)
                    autoCheckUpdate()
                }
            }

            updateDynamicUI(null)
        }

        override fun onResume() {
            super.onResume()
            // Patch counts change while the user is inside an app's patch list; refresh on return.
            updateDynamicUI(activatedService())
        }

        /** The service, but only once it actually answers — a bound binder is not activation. */
        private fun activatedService(): XposedService? {
            val service = mService ?: return null
            return runCatching {
                service.getRemotePreferences(SettingsActivity.MODULE_PREFS)
                service.apiVersion
                service
            }.getOrNull()
        }

        private fun AppPatchInfo.buildPreference(prefs: SharedPreferences?): Preference {
            val selectable = selectablePatches
            val installed = runCatching {
                context.packageManager.getPackageInfo(packageName, 0)
            }.isSuccess

            return Preference(context).apply {
                title = appName
                key = packageName
                summary = when {
                    !installed -> getString(
                        R.string.app_not_installed_with_count, selectable.size
                    )

                    else -> {
                        val enabled = selectable.count { patch ->
                            prefs?.getBoolean(patch.name, patch.use) ?: patch.use
                        }
                        if (enabled == 0) getString(
                            R.string.patch_count_summary_none, selectable.size
                        ) else getString(
                            R.string.patch_count_summary, enabled, selectable.size
                        )
                    }
                }
                runCatching {
                    icon = context.packageManager.getApplicationIcon(packageName)
                }
                intent = Intent(context, AppPatchSettingsActivity::class.java).apply {
                    putExtra(AppPatchSettingsActivity.ARGUMENT_APP_NAME, appName)
                }
            }
        }

        /**
         * Rebuilds the status line and the patch list. Passing null renders the
         * "module not activated" state.
         */
        private fun updateDynamicUI(service: XposedService?) {
            val rootScreen = preferenceScreen ?: return
            statusPreference?.let { rootScreen.removePreference(it) }
            patchCategory?.let { rootScreen.removePreference(it) }
            statusPreference = null
            patchCategory = null

            if (service == null) {
                statusPreference = Preference(context).apply {
                    order = ORDER_STATUS
                    setTitle(R.string.module_not_activated_title)
                    setSummary(R.string.module_not_activated_summary)
                    isSelectable = false
                    rootScreen.addPreference(this)
                }
                return
            }

            val category = PreferenceCategory(context).apply {
                order = ORDER_PATCHES
                setTitle(R.string.patch_selection)
            }
            patchCategory = category
            rootScreen.addPreference(category)

            category.addPreference(Preference(context).apply {
                setSummary(R.string.force_stop_to_apply_summary)
                isSelectable = false
            })

            // Installed apps first: someone with three of the nine apps should not have to hunt
            // for them among entries they cannot use.
            appPatchConfigurations
                .sortedByDescending { it.isInstalled(context.packageManager) }
                .forEach { appPatchInfo ->
                    val prefs = runCatching {
                        service.getRemotePreferences(appPatchInfo.packageName)
                    }.getOrNull()
                    category.addPreference(appPatchInfo.buildPreference(prefs))
                }
        }

        override fun onStart() {
            super.onStart()
            SettingApplication.addServiceStateListener(this, true)
        }

        override fun onStop() {
            SettingApplication.removeServiceStateListener(this)
            super.onStop()
        }

        override fun onServiceStateChanged(service: XposedService?) {
            mService = service

            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                updateDynamicUI(activatedService())
            }
        }

        private companion object {
            const val LICENSE_PREF_KEY = "open_source_licenses"
            const val FAQ_URL =
                "https://github.com/NexAlloy/NexAlloy/wiki/Frequently-Asked-Questions"
            const val MORPHE_URL = "https://morphe.software"

            const val ORDER_STATUS = 0
            const val ORDER_PATCHES = 10
            const val ORDER_ABOUT = 20
            const val ORDER_VERSION = 30
        }
    }
}
