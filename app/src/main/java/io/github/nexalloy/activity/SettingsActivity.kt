@file:Suppress("DEPRECATION") @file:SuppressLint("WorldReadableFiles")

package io.github.nexalloy.activity

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.preference.CheckBoxPreference
import android.preference.Preference
import android.preference.PreferenceCategory
import android.preference.PreferenceFragment
import android.text.format.DateUtils
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import android.window.OnBackInvokedDispatcher
import app.morphe.extension.shared.Utils
import app.morphe.extension.shared.settings.preference.about.MorpheAboutPreference
import io.github.nexalloy.AppPatchInfo
import io.github.nexalloy.BuildConfig
import io.github.nexalloy.KEY_HAPTICS_ENABLED
import io.github.nexalloy.R
import io.github.nexalloy.appPatchConfigurations
import io.github.nexalloy.common.UpdateChecker

class SettingsActivity : Activity() {
    private lateinit var aboutPreference: MorpheAboutPreference

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Registered so predictive back has something to animate towards. It used
            // to call the deprecated onBackPressed(), which killed the process -- so
            // the system previewed a screen it was about to destroy rather than pop.
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT
            ) {
                finish()
            }
        }
        setContentView(R.layout.activity_settings)
        applySystemBarInsets(findViewById(R.id.settings_container))
        actionBar?.setDisplayShowHomeEnabled(true)

        Utils.setContext(this)
        aboutPreference = MorpheAboutPreference(this).apply {
            setTitle(R.string.about_title)
        }

        if (savedInstanceState != null) return

        fragmentManager.beginTransaction().replace(R.id.settings_container, SettingsFragment())
            .commit()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.xp_settings_menu, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val aliasName = ComponentName(this, SettingsActivity::class.java.name + "Alias")
        menu.findItem(R.id.menu_hide_icon).isChecked =
            packageManager.getComponentEnabledSetting(aliasName) == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        try {
            val prefs = getSharedPreferences("prefs", MODE_WORLD_READABLE)
            menu.findItem(R.id.menu_disable_auto_check).isChecked =
                prefs.getBoolean("disable_auto_check_update", false)
        } catch (_: SecurityException) {
            menu.findItem(R.id.menu_disable_auto_check).isVisible = false
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
                val aliasName = ComponentName(this, SettingsActivity::class.java.name + "Alias")
                val status = if (newChecked) PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                             else PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                packageManager.setComponentEnabledSetting(aliasName, status, PackageManager.DONT_KILL_APP)
                true
            }
            R.id.menu_disable_auto_check -> {
                val newChecked = !item.isChecked
                item.isChecked = newChecked
                getSharedPreferences("prefs", MODE_WORLD_READABLE)
                    .edit().putBoolean("disable_auto_check_update", newChecked).apply()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // onBackPressed() is deliberately NOT overridden. It used to call
    // finishAndRemoveTask() followed by exitProcess(0), so every back gesture tore the
    // process down: returning from a patch list exited the app instead of going up a
    // level, and no instance state ever survived. The default behaviour is correct.

    class SettingsFragment : PreferenceFragment() {

        /** Static guidance rendered as text rather than as a disabled control. */
        private fun caption(ctx: Context, text: CharSequence) =
            object : Preference(ctx) {
                @Deprecated("Deprecated in Java")
                override fun onBindView(view: View) {
                    super.onBindView(view)
                    view.findViewById<TextView>(R.id.caption_text)?.text = text
                }
            }.apply {
                layoutResource = R.layout.preference_caption
                isSelectable = false
            }

        fun AppPatchInfo.getPreference(ctx: Context): Preference {
            val preference = Preference(ctx)
            preference.title = appName
            preference.key = appName

            val isInstalled = runCatching {
                ctx.packageManager.getPackageInfo(packageName, 0)
            }.isSuccess

            if (isInstalled) {
                // The icon makes the list scannable, and its absence marks the apps
                // that are not installed without needing to read the summary.
                runCatching { ctx.packageManager.getApplicationIcon(packageName) }
                    .getOrNull()?.let { preference.icon = it }

                // "34 of 61 patches on" -- the row exists to answer this and did not.
                val visible = patches.filter { it.name.isNotEmpty() && !it.name.startsWith("<") }
                val enabled = runCatching {
                    val prefs = ctx.getSharedPreferences(packageName, MODE_WORLD_READABLE)
                    visible.count { prefs.getBoolean(it.name, it.use) }
                }.getOrNull()
                if (enabled != null) {
                    preference.summary =
                        getString(R.string.patches_enabled_summary, enabled, visible.size)
                }
                preference.intent = Intent(ctx, AppPatchSettingsActivity::class.java).apply {
                    putExtra(AppPatchSettingsActivity.ARGUMENT_APP_NAME, appName)
                }
            } else {
                // Opening a full patch catalogue for an app that is not on the device
                // is a dead end; say so instead.
                preference.setSummary(R.string.app_not_installed)
                preference.isEnabled = false
            }
            return preference
        }

        @Deprecated("Deprecated in Java")
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)

            val ctx = context ?: return
            val rootScreen = preferenceManager.createPreferenceScreen(ctx)
            preferenceScreen = rootScreen

            rootScreen.addPreference(caption(ctx, getString(R.string.slogan_summary)))

            Utils.setContext(ctx)

            Preference(ctx).apply {
                summary = getString(R.string.morphe_attribution_summary, "https://morphe.software")
                intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://morphe.software"))
                rootScreen.addPreference(this)
            }

            Preference(ctx).apply {
                setTitle(R.string.faq_title)
                // Derived from the build's update repository, so a fork does not send
                // its users to upstream's wiki for help with builds they are not running.
                intent = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse(
                        "https://github.com/${BuildConfig.UPDATE_OWNER}/" +
                            "${BuildConfig.UPDATE_REPO}/wiki/Frequently-Asked-Questions"
                    )
                )
                rootScreen.addPreference(this)
            }

            addPreferencesFromResource(R.xml.license_prefs)

            Preference(ctx).apply {
                setTitle(R.string.check_for_update_title)
                summary = getString(
                    R.string.version_summary,
                    BuildConfig.VERSION_NAME,
                    BuildConfig.COMMIT_HASH,
                    BuildConfig.BUILD_TYPE,
                    DateUtils.getRelativeTimeSpanString(BuildConfig.COMMIT_DATE * 1000)
                )
                setOnPreferenceClickListener {
                    UpdateChecker(activity?.applicationContext).apply {
                        activity?.let { setActivity(it) }
                        checkUpdate(silent = false)
                    }
                    true
                }
                rootScreen.addPreference(this)
            }
            UpdateChecker(activity?.applicationContext).apply {
                activity?.let { setActivity(it) }
                autoCheckUpdate()
            }

            val isModuleActivated: Boolean = try {
                ctx.getSharedPreferences("prefs", MODE_WORLD_READABLE)
                true
            } catch (_: SecurityException) {
                false
            }

            if (!isModuleActivated) {
                // Previously a single greyed line and nothing else -- the most important
                // screen in the app for a first-run user, with no way onwards from it.
                rootScreen.addPreference(caption(ctx, getString(R.string.module_not_activated_summary)))
                rootScreen.addPreference(Preference(ctx).apply {
                    setTitle(R.string.open_lsposed)
                    setOnPreferenceClickListener {
                        openLsposed(ctx)
                        true
                    }
                })
                return
            }

            val patchSelectionCategory = PreferenceCategory(ctx).apply {
                setTitle(R.string.patch_selection)
                rootScreen.addPreference(this)
            }
            patchSelectionCategory.addPreference(
                caption(ctx, getString(R.string.force_stop_to_apply_summary))
            )

            for (appPatchInfo in appPatchConfigurations) {
                patchSelectionCategory.addPreference(appPatchInfo.getPreference(ctx))
            }

            // Haptics are now opt-out rather than unconditional; the key is shared with
            // the patch screen, which reads it before buzzing.
            rootScreen.addPreference(CheckBoxPreference(ctx).apply {
                key = KEY_HAPTICS_ENABLED
                setTitle(R.string.haptics_title)
                setSummary(R.string.haptics_summary)
                setDefaultValue(true)
            })
        }

        /**
         * LSPosed exposes no stable public activity, so try the known manager packages
         * and fall back to saying so rather than throwing.
         */
        private fun openLsposed(ctx: Context) {
            val candidates = listOf(
                "org.lsposed.manager",
                "io.github.lsposed.manager",
                "org.lsposed.lspatch"
            )
            for (pkg in candidates) {
                val intent = ctx.packageManager.getLaunchIntentForPackage(pkg)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { ctx.startActivity(intent) }.onSuccess { return }
                }
            }
            Utils.showToastLong(getString(R.string.lsposed_not_found))
        }
    }
}
