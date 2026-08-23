package io.github.nexalloy.activity

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.preference.CheckBoxPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceScreen
import androidx.preference.PreferenceViewHolder
import com.google.android.material.appbar.MaterialToolbar
import io.github.nexalloy.KEY_APP_PATCHING_ENABLED
import io.github.nexalloy.KEY_HAPTICS_ENABLED
import io.github.nexalloy.Patch
import io.github.nexalloy.R
import io.github.nexalloy.appPatchConfigurations

class AppPatchSettingsActivity : AppCompatActivity() {

    companion object {
        const val ARGUMENT_APP_NAME = "app_name_key"
    }

    private var fragment: AppPatchSettingsFragment? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_patch_settings)
        applySystemBarInsets(findViewById(R.id.app_patch_settings_container))

        val appName = intent.getStringExtra(ARGUMENT_APP_NAME)
        setSupportActionBar(findViewById<MaterialToolbar>(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = appName

        if (savedInstanceState != null) {
            fragment = supportFragmentManager.findFragmentById(R.id.app_patch_settings_container)
                as? AppPatchSettingsFragment
            return
        }
        val f = AppPatchSettingsFragment().apply {
            arguments = Bundle().apply { putString(ARGUMENT_APP_NAME, appName) }
        }
        fragment = f
        supportFragmentManager.beginTransaction()
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
                    fragment?.applyFilter(query.orEmpty()); return true
                }

                override fun onQueryTextChange(newText: String?): Boolean {
                    fragment?.applyFilter(newText.orEmpty()); return true
                }
            })
        }
        searchItem?.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: MenuItem) = true
            override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                fragment?.applyFilter(""); return true
            }
        })
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    @SuppressLint("WorldReadableFiles")
    class AppPatchSettingsFragment : PreferenceFragmentCompat() {

        private lateinit var allPatches: List<Patch>
        private lateinit var defaultPatchStates: Map<String, Boolean>
        private var packageName: String = ""
        private var appLabel: String = ""
        private var hiddenPatchCount: Int = 0
        private var currentFilter: String = ""

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            val appName = arguments?.getString(ARGUMENT_APP_NAME)
            val appPatchInfo = appPatchConfigurations.find { it.appName == appName }
                ?: throw IllegalStateException("AppPatchInfo not found, app_name: $appName")

            packageName = appPatchInfo.packageName
            appLabel = appPatchInfo.appName
            defaultPatchStates = appPatchInfo.patches.associate { it.name to it.use }

            /** XSharedPreference
             * @see io.github.nexalloy.PatchExecutor.patchPreferences */
            @Suppress("DEPRECATION")
            preferenceManager.sharedPreferencesMode = Context.MODE_WORLD_READABLE
            preferenceManager.sharedPreferencesName = appPatchInfo.packageName

            // Patches with no name, or a name starting with "<", are internal and have
            // no meaningful toggle -- but they still RUN, so silently dropping them made
            // the list quietly disagree with what was applied. Counted and stated below.
            val visible = appPatchInfo.patches.filter {
                it.name.isNotEmpty() && !it.name.startsWith("<")
            }
            hiddenPatchCount = appPatchInfo.patches.size - visible.size
            allPatches = visible.sortedWith(compareBy({ it.category ?: "" }, { it.name }))

            buildScreen()
        }

        private fun buildScreen() {
            val ctx = context ?: return
            val screen: PreferenceScreen = preferenceManager.createPreferenceScreen(ctx)

            val isInstalled = runCatching {
                ctx.packageManager.getPackageInfo(packageName, 0)
            }.isSuccess

            screen.addPreference(headerButtonsPreference(ctx, isInstalled))

            if (!isInstalled) {
                screen.addPreference(caption(ctx, getString(R.string.app_not_installed)))
            }

            screen.addPreference(CheckBoxPreference(ctx).apply {
                key = KEY_APP_PATCHING_ENABLED
                setTitle(R.string.app_patching_enabled_title)
                setSummary(R.string.app_patching_enabled_summary)
                setDefaultValue(true)
                isIconSpaceReserved = false
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

            // Group by category where patches declare one; a flat list otherwise, which
            // is every patch set until they are annotated.
            val grouped = filtered.groupBy { it.category }
            grouped[null].orEmpty().forEach { screen.addPreference(patchPreference(ctx, it)) }

            grouped.filterKeys { it != null }
                .toSortedMap(compareBy { it })
                .forEach { (category, patches) ->
                    val enabled = patches.count { prefEnabled(it) }
                    val header = PreferenceCategory(ctx).apply {
                        title = "$category  ·  " +
                            getString(R.string.patches_enabled_summary, enabled, patches.size)
                        isIconSpaceReserved = false
                    }
                    screen.addPreference(header)
                    patches.forEach { header.addPreference(patchPreference(ctx, it)) }
                }

            if (hiddenPatchCount > 0 && currentFilter.isBlank()) {
                screen.addPreference(
                    caption(ctx, getString(R.string.internal_patches_footer, hiddenPatchCount))
                )
            }

            preferenceScreen = screen
        }

        /** Static text that is text, not a disabled control at reduced contrast. */
        private fun caption(ctx: Context, text: String) =
            object : Preference(ctx) {
                override fun onBindViewHolder(holder: PreferenceViewHolder) {
                    super.onBindViewHolder(holder)
                    (holder.findViewById(R.id.caption_text) as? android.widget.TextView)?.text = text
                }
            }.apply {
                layoutResource = R.layout.preference_caption
                isSelectable = false
                isIconSpaceReserved = false
            }

        private fun prefEnabled(patch: Patch): Boolean =
            preferenceManager.sharedPreferences?.getBoolean(patch.name, patch.use) ?: patch.use

        private fun patchPreference(ctx: Context, patchInfo: Patch) =
            CheckBoxPreference(ctx).apply {
                key = patchInfo.name // Pref Key
                title = patchInfo.name
                isIconSpaceReserved = false
                // Mark what the user changed: nothing distinguished a patch left at its
                // default from one deliberately switched.
                val isDefault =
                    prefEnabled(patchInfo) == (defaultPatchStates[patchInfo.name] ?: patchInfo.use)
                summary = if (isDefault) patchInfo.description
                else "● " + getString(R.string.patch_changed_marker) + " · " + patchInfo.description
                setDefaultValue(patchInfo.use)
                setOnPreferenceChangeListener { _, _ ->
                    vibrateIfEnabled(ctx)
                    view?.post { buildScreen() }
                    true
                }
            }

        private fun vibrateIfEnabled(ctx: Context) {
            val on = preferenceManager.sharedPreferences
                ?.getBoolean(KEY_HAPTICS_ENABLED, true) ?: true
            if (!on) return
            val vibrator = ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator?
            if (vibrator?.hasVibrator() != true) return
            // vibrate(long) is deprecated since API 26.
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                    vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ->
                    vibrator.vibrate(
                        VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE)
                    )
                else -> @Suppress("DEPRECATION") vibrator.vibrate(30)
            }
        }

        private fun headerButtonsPreference(ctx: Context, isInstalled: Boolean) =
            object : Preference(ctx) {
                override fun onBindViewHolder(holder: PreferenceViewHolder) {
                    super.onBindViewHolder(holder)
                    (holder.findViewById(R.id.button_default) as? Button)?.setOnClickListener {
                        restoreDefaultPreferences(defaultPatchStates)
                    }
                    (holder.findViewById(R.id.button_none) as? Button)?.setOnClickListener {
                        confirmDisableAll()
                    }
                    (holder.findViewById(R.id.button_app_info) as? Button)?.apply {
                        visibility = if (isInstalled) View.VISIBLE else View.GONE
                        setOnClickListener {
                            startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                    .setData(Uri.parse("package:$packageName"))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    }
                }
            }.apply {
                layoutResource = R.layout.preference_header_buttons
                isSelectable = false
                isIconSpaceReserved = false
            }

        fun applyFilter(query: String) {
            if (!isAdded || query == currentFilter) return
            currentFilter = query
            buildScreen()
        }

        /** "None" cleared everything with no confirmation, no undo and no record. */
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

        fun restoreDefaultPreferences(defaults: Map<String, Boolean>) {
            if (!isAdded) return
            forEachPatchPreference { pref -> pref.isChecked = defaults[pref.key] ?: pref.isChecked }
            buildScreen()
        }

        /**
         * Walks nested categories. Iterating only the screen's direct children would
         * skip every patch once they sit under a PreferenceCategory.
         */
        private fun forEachPatchPreference(action: (CheckBoxPreference) -> Unit) {
            fun walk(group: PreferenceGroup) {
                for (i in 0 until group.preferenceCount) {
                    when (val pref = group.getPreference(i)) {
                        is CheckBoxPreference ->
                            if (pref.key != KEY_APP_PATCHING_ENABLED &&
                                pref.key != KEY_HAPTICS_ENABLED
                            ) action(pref)
                        is PreferenceGroup -> walk(pref)
                    }
                }
            }
            walk(preferenceScreen)
        }
    }
}
