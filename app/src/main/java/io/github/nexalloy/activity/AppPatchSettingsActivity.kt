@file:Suppress("DEPRECATION")

package io.github.nexalloy.activity

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.preference.CheckBoxPreference
import android.preference.Preference
import android.preference.PreferenceCategory
import android.preference.PreferenceFragment
import android.preference.PreferenceScreen
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.SearchView
import android.widget.TextView
import io.github.nexalloy.KEY_APP_PATCHING_ENABLED
import io.github.nexalloy.KEY_HAPTICS_ENABLED
import io.github.nexalloy.Patch
import io.github.nexalloy.R
import io.github.nexalloy.appPatchConfigurations

class AppPatchSettingsActivity : Activity() {

    companion object {
        const val ARGUMENT_APP_NAME = "app_name_key"
    }

    private var fragment: AppPatchSettingsFragment? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_patch_settings)
        applySystemBarInsets(findViewById(R.id.app_patch_settings_container))

        actionBar?.setDisplayHomeAsUpEnabled(true)

        val appName = intent.getStringExtra(ARGUMENT_APP_NAME)
        actionBar?.title = appName

        if (savedInstanceState != null) {
            fragment = fragmentManager.findFragmentById(R.id.app_patch_settings_container)
                as? AppPatchSettingsFragment
            return
        }
        val f = AppPatchSettingsFragment().apply {
            arguments = Bundle().apply {
                putString(ARGUMENT_APP_NAME, appName)
            }
        }
        fragment = f
        fragmentManager.beginTransaction()
            .replace(R.id.app_patch_settings_container, f)
            .commit()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.xp_patch_menu, menu)
        val searchItem = menu.findItem(R.id.menu_search)
        (searchItem?.actionView as? SearchView)?.apply {
            queryHint = getString(R.string.search_patches)
            setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String?): Boolean {
                    fragment?.applyFilter(query.orEmpty())
                    return true
                }

                override fun onQueryTextChange(newText: String?): Boolean {
                    fragment?.applyFilter(newText.orEmpty())
                    return true
                }
            })
        }
        searchItem?.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: MenuItem) = true
            override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                fragment?.applyFilter("")
                return true
            }
        })
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            // Plain finish(): this screen is a child of SettingsActivity, and going up
            // from it should return there, not tear the process down.
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    @SuppressLint("WorldReadableFiles")
    class AppPatchSettingsFragment : PreferenceFragment() {

        private lateinit var allPatches: List<Patch>
        private lateinit var defaultPatchStates: Map<String, Boolean>
        private var packageName: String = ""
        private var appLabel: String = ""
        private var hiddenPatchCount: Int = 0
        private var currentFilter: String = ""

        @Deprecated("Deprecated in Java")
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)

            // Retrieve appName from the Activity's Intent extras
            val appName = arguments?.getString(ARGUMENT_APP_NAME)
            val appPatchInfo = appPatchConfigurations.find { it.appName == appName }
            if (appPatchInfo == null) throw Exception("AppPatchInfo not found, app_name: $appName")

            packageName = appPatchInfo.packageName
            appLabel = appPatchInfo.appName
            defaultPatchStates = appPatchInfo.patches.associate { it.name to it.use }

            /** XSharedPreference
             * @see io.github.nexalloy.PatchExecutor.patchPreferences */
            preferenceManager.sharedPreferencesMode = MODE_WORLD_READABLE
            preferenceManager.sharedPreferencesName = appPatchInfo.packageName

            // Patches with no name, or a name starting with "<", are internal and have
            // no meaningful toggle -- but they still RUN, so silently dropping them made
            // the list quietly disagree with what was being applied. They are excluded
            // here and counted, and the count is stated at the bottom of the screen.
            val visible = appPatchInfo.patches.filter {
                it.name.isNotEmpty() && !it.name.startsWith("<")
            }
            hiddenPatchCount = appPatchInfo.patches.size - visible.size
            allPatches = visible.sortedWith(
                compareBy({ it.category ?: "" }, { it.name })
            )

            buildScreen()
        }

        /** Rebuilds the whole screen for [currentFilter]. */
        private fun buildScreen() {
            val ctx = context ?: return
            val screen: PreferenceScreen = preferenceManager.createPreferenceScreen(ctx)

            val isInstalled = runCatching {
                ctx.packageManager.getPackageInfo(packageName, 0)
            }.isSuccess

            // Header buttons, only meaningful when there is something to act on.
            headerButtonsPreference(isInstalled).let(screen::addPreference)

            if (!isInstalled) {
                screen.addPreference(caption(ctx, getString(R.string.app_not_installed)))
            }

            // Per-app master switch.
            screen.addPreference(CheckBoxPreference(ctx).apply {
                key = KEY_APP_PATCHING_ENABLED
                setTitle(R.string.app_patching_enabled_title)
                setSummary(R.string.app_patching_enabled_summary)
                setDefaultValue(true)
            })

            screen.addPreference(caption(ctx, getString(R.string.apply_hint_summary)))

            val filtered = if (currentFilter.isBlank()) allPatches else allPatches.filter {
                it.name.contains(currentFilter, ignoreCase = true) ||
                    it.description.contains(currentFilter, ignoreCase = true)
            }

            if (filtered.isEmpty()) {
                screen.addPreference(
                    caption(ctx, getString(R.string.no_patches_match, currentFilter))
                )
            }

            // Group by category when patches declare one; a single flat list otherwise,
            // which is the current state of every patch set until they are annotated.
            val grouped = filtered.groupBy { it.category }
            val plain = grouped[null].orEmpty()
            val categorised = grouped.filterKeys { it != null }.toSortedMap(compareBy { it })

            plain.forEach { screen.addPreference(patchPreference(it)) }

            for ((category, patches) in categorised) {
                val enabled = patches.count { prefEnabled(it) }
                val header = PreferenceCategory(ctx).apply {
                    title = "$category  ·  " +
                        getString(R.string.patches_enabled_summary, enabled, patches.size)
                }
                screen.addPreference(header)
                patches.forEach { header.addPreference(patchPreference(it)) }
            }

            if (hiddenPatchCount > 0 && currentFilter.isBlank()) {
                screen.addPreference(
                    caption(ctx, getString(R.string.internal_patches_footer, hiddenPatchCount))
                )
            }

            preferenceScreen = screen
        }

        /** Static text that is text, not a disabled control. */
        private fun caption(ctx: android.content.Context, text: String) =
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

        private fun prefEnabled(patch: Patch): Boolean =
            preferenceManager.sharedPreferences
                ?.getBoolean(patch.name, patch.use) ?: patch.use

        private fun patchPreference(patchInfo: Patch) =
            CheckBoxPreference(context).apply {
                /** XSharedPreference
                 * @see io.github.nexalloy.PatchExecutor.applyPatches */
                key = patchInfo.name // Pref Key
                title = patchInfo.name
                // Mark what the user has changed. Nothing distinguished a patch left at
                // its default from one deliberately switched, so "what have I actually
                // customised?" could only be answered by pressing Default and watching.
                val isDefault = prefEnabled(patchInfo) == (defaultPatchStates[patchInfo.name] ?: patchInfo.use)
                summary = if (isDefault) patchInfo.description
                else "● " + getString(R.string.patch_changed_marker) + " · " + patchInfo.description
                setDefaultValue(patchInfo.use)
                setOnPreferenceChangeListener { _, _ ->
                    vibrateIfEnabled()
                    // Re-render so the changed-marker and per-category counts stay true.
                    view?.post { buildScreen() }
                    true
                }
            }

        private fun vibrateIfEnabled() {
            val ctx = context ?: return
            val on = preferenceManager.sharedPreferences
                ?.getBoolean(KEY_HAPTICS_ENABLED, true) ?: true
            if (!on) return
            val vibrator = ctx.getSystemService(VIBRATOR_SERVICE) as Vibrator?
            if (vibrator?.hasVibrator() != true) return
            // vibrate(long) is deprecated since API 26.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(30)
            }
        }

        private fun headerButtonsPreference(isInstalled: Boolean) =
            object : Preference(context) {
                @Deprecated("Deprecated in Java")
                override fun onBindView(view: View) {
                    super.onBindView(view)
                    view.findViewById<Button>(R.id.button_default).setOnClickListener {
                        restoreDefaultPreferences(defaultPatchStates)
                    }
                    view.findViewById<Button>(R.id.button_none).setOnClickListener {
                        confirmDisableAll()
                    }
                    view.findViewById<Button>(R.id.button_app_info).apply {
                        if (!isInstalled) visibility = View.GONE
                        setOnClickListener {
                            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                .setData(Uri.parse("package:$packageName"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            startActivity(intent)
                        }
                    }
                }
            }.apply {
                layoutResource = R.layout.preference_header_buttons
                isSelectable = false
            }

        /** Re-filter from the activity's SearchView. */
        fun applyFilter(query: String) {
            if (!isAdded) return
            if (query == currentFilter) return
            currentFilter = query
            buildScreen()
        }

        /**
         * "None" cleared every patch instantly, with no confirmation, no undo, and no
         * record of what had been on -- one mis-tap discarded the whole selection.
         */
        private fun confirmDisableAll() {
            val ctx = context ?: return
            AlertDialog.Builder(ctx)
                .setTitle(R.string.confirm_none_title)
                .setMessage(getString(R.string.confirm_none_message, allPatches.size, appLabel))
                .setPositiveButton(R.string.confirm_none_ok) { _, _ -> setAllPreferences(false) }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        fun setAllPreferences(enable: Boolean) {
            if (!isAdded) return
            forEachPatchPreference { it.isChecked = enable }
            buildScreen()
        }

        fun restoreDefaultPreferences(defaultPatchStates: Map<String, Boolean>) {
            if (!isAdded) return
            forEachPatchPreference { pref ->
                pref.isChecked = defaultPatchStates[pref.key] ?: pref.isChecked
            }
            buildScreen()
        }

        /**
         * Walks nested categories too. The previous version iterated only the screen's
         * direct children, so once patches sit under a PreferenceCategory the bulk
         * actions would silently skip every one of them.
         */
        private fun forEachPatchPreference(action: (CheckBoxPreference) -> Unit) {
            fun walk(group: android.preference.PreferenceGroup) {
                for (i in 0 until group.preferenceCount) {
                    when (val pref = group.getPreference(i)) {
                        is CheckBoxPreference -> {
                            if (pref.key != KEY_APP_PATCHING_ENABLED &&
                                pref.key != KEY_HAPTICS_ENABLED
                            ) action(pref)
                        }
                        is android.preference.PreferenceGroup -> walk(pref)
                    }
                }
            }
            walk(preferenceScreen)
        }
    }
}
