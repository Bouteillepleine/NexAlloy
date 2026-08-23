@file:SuppressLint("WorldReadableFiles")

package io.github.nexalloy.activity

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.text.format.DateUtils
import android.view.Menu
import android.view.MenuItem
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.CheckBoxPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceViewHolder
import app.morphe.extension.shared.Utils
import app.morphe.extension.shared.settings.preference.about.MorpheAboutPreference
import com.google.android.material.appbar.MaterialToolbar
import io.github.nexalloy.AppPatchInfo
import io.github.nexalloy.BuildConfig
import io.github.nexalloy.KEY_HAPTICS_ENABLED
import io.github.nexalloy.R
import io.github.nexalloy.appPatchConfigurations
import io.github.nexalloy.common.UpdateChecker

class SettingsActivity : AppCompatActivity() {

    /**
     * Kept as a FRAMEWORK preference on purpose.
     *
     * MorpheAboutPreference comes from the Morphe extension and extends
     * android.preference.Preference, so it cannot be added to an AndroidX
     * PreferenceScreen. It never was: it is only ever used as a holder for its click
     * listener, driven from the overflow menu, which works regardless of which
     * preference stack the screen itself uses.
     */
    @Suppress("DEPRECATION")
    private lateinit var aboutPreference: MorpheAboutPreference

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        applySystemBarInsets(findViewById(R.id.settings_container))

        setSupportActionBar(findViewById<MaterialToolbar>(R.id.toolbar))
        supportActionBar?.title = getString(R.string.app_name)

        Utils.setContext(this)
        @Suppress("DEPRECATION")
        aboutPreference = MorpheAboutPreference(this).apply { setTitle(R.string.about_title) }

        if (savedInstanceState != null) return
        supportFragmentManager.beginTransaction()
            .replace(R.id.settings_container, SettingsFragment())
            .commit()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.xp_settings_menu, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val aliasName = ComponentName(this, SettingsActivity::class.java.name + "Alias")
        menu.findItem(R.id.menu_hide_icon)?.isChecked =
            packageManager.getComponentEnabledSetting(aliasName) ==
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        try {
            val prefs = getSharedPreferences("prefs", Context.MODE_WORLD_READABLE)
            menu.findItem(R.id.menu_disable_auto_check)?.isChecked =
                prefs.getBoolean("disable_auto_check_update", false)
        } catch (_: SecurityException) {
            menu.findItem(R.id.menu_disable_auto_check)?.isVisible = false
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menu_about -> {
                @Suppress("DEPRECATION")
                aboutPreference.onPreferenceClickListener?.onPreferenceClick(aboutPreference)
                true
            }
            R.id.menu_hide_icon -> {
                val newChecked = !item.isChecked
                item.isChecked = newChecked
                val aliasName = ComponentName(this, SettingsActivity::class.java.name + "Alias")
                packageManager.setComponentEnabledSetting(
                    aliasName,
                    if (newChecked) PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    else PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP
                )
                true
            }
            R.id.menu_disable_auto_check -> {
                val newChecked = !item.isChecked
                item.isChecked = newChecked
                getSharedPreferences("prefs", Context.MODE_WORLD_READABLE)
                    .edit().putBoolean("disable_auto_check_update", newChecked).apply()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // onBackPressed is deliberately NOT overridden. It used to call finishAndRemoveTask()
    // then exitProcess(0), so every back gesture tore the process down and returning from
    // a patch list exited the app instead of going up a level.

    class SettingsFragment : PreferenceFragmentCompat() {

        /**
         * Static guidance text.
         *
         * A plain Preference carrying only a summary, NOT disabled: isEnabled=false was
         * the original bug (disabled opacity made the most useful text the least
         * legible), and a custom layout was the second attempt -- it bound at full
         * height with invisible text, because preference rows are inflated with the
         * PreferenceThemeOverlay context where the Material 3 colour attributes do not
         * resolve. isSelectable=false keeps it non-interactive while the summary is
         * themed by AndroidX exactly like every other row.
         */
        private fun caption(ctx: Context, text: CharSequence) = Preference(ctx).apply {
            summary = text
            isSelectable = false
            isIconSpaceReserved = false
        }

        private fun AppPatchInfo.getPreference(ctx: Context): Preference {
            val preference = Preference(ctx)
            preference.title = appName
            preference.key = appName
            preference.isIconSpaceReserved = true

            val isInstalled = runCatching {
                ctx.packageManager.getPackageInfo(packageName, 0)
            }.isSuccess

            if (isInstalled) {
                runCatching { ctx.packageManager.getApplicationIcon(packageName) }
                    .getOrNull()?.let { preference.icon = it }

                val visible = patches.filter { it.name.isNotEmpty() && !it.name.startsWith("<") }
                val enabled = runCatching {
                    val prefs = ctx.getSharedPreferences(packageName, Context.MODE_WORLD_READABLE)
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
                preference.setSummary(R.string.app_not_installed)
                preference.isEnabled = false
            }
            return preference
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            val ctx = preferenceManager.context
            val rootScreen = preferenceManager.createPreferenceScreen(ctx)
            preferenceScreen = rootScreen

            rootScreen.addPreference(caption(ctx, getString(R.string.slogan_summary)))

            Utils.setContext(ctx)

            rootScreen.addPreference(Preference(ctx).apply {
                summary = getString(R.string.morphe_attribution_summary, "https://morphe.software")
                intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://morphe.software"))
                isIconSpaceReserved = false
            })

            rootScreen.addPreference(Preference(ctx).apply {
                setTitle(R.string.faq_title)
                // Derived from the build's update repository, so a fork does not send its
                // users to upstream's wiki for builds they are not running.
                intent = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse(
                        "https://github.com/${BuildConfig.UPDATE_OWNER}/" +
                            "${BuildConfig.UPDATE_REPO}/wiki/Frequently-Asked-Questions"
                    )
                )
                isIconSpaceReserved = false
            })

            addPreferencesFromResource(R.xml.license_prefs)

            rootScreen.addPreference(Preference(ctx).apply {
                setTitle(R.string.check_for_update_title)
                summary = getString(
                    R.string.version_summary,
                    BuildConfig.VERSION_NAME,
                    BuildConfig.COMMIT_HASH,
                    BuildConfig.BUILD_TYPE,
                    DateUtils.getRelativeTimeSpanString(BuildConfig.COMMIT_DATE * 1000)
                )
                isIconSpaceReserved = false
                setOnPreferenceClickListener {
                    UpdateChecker(activity?.applicationContext).apply {
                        activity?.let { setActivity(it) }
                        checkUpdate(silent = false)
                    }
                    true
                }
            })
            UpdateChecker(activity?.applicationContext).apply {
                activity?.let { setActivity(it) }
                autoCheckUpdate()
            }

            val isModuleActivated: Boolean = try {
                ctx.getSharedPreferences("prefs", Context.MODE_WORLD_READABLE)
                true
            } catch (_: SecurityException) {
                false
            }

            if (!isModuleActivated) {
                rootScreen.addPreference(
                    caption(ctx, getString(R.string.module_not_activated_summary))
                )
                rootScreen.addPreference(Preference(ctx).apply {
                    setTitle(R.string.open_lsposed)
                    isIconSpaceReserved = false
                    setOnPreferenceClickListener { openLsposed(ctx); true }
                })
                return
            }

            val patchSelectionCategory = PreferenceCategory(ctx).apply {
                setTitle(R.string.patch_selection)
                isIconSpaceReserved = false
            }
            rootScreen.addPreference(patchSelectionCategory)
            patchSelectionCategory.addPreference(
                caption(ctx, getString(R.string.force_stop_to_apply_summary))
            )
            for (appPatchInfo in appPatchConfigurations) {
                patchSelectionCategory.addPreference(appPatchInfo.getPreference(ctx))
            }

            rootScreen.addPreference(CheckBoxPreference(ctx).apply {
                key = KEY_HAPTICS_ENABLED
                setTitle(R.string.haptics_title)
                setSummary(R.string.haptics_summary)
                setDefaultValue(true)
                isIconSpaceReserved = false
            })
        }

        /** LSPosed exposes no stable public activity; try the known manager packages. */
        private fun openLsposed(ctx: Context) {
            val candidates = listOf(
                "org.lsposed.manager", "io.github.lsposed.manager", "org.lsposed.lspatch"
            )
            for (pkg in candidates) {
                val intent = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: continue
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { ctx.startActivity(intent) }.onSuccess { return }
            }
            Utils.showToastLong(getString(R.string.lsposed_not_found))
        }
    }
}
