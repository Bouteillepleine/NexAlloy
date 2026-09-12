@file:Suppress("DEPRECATION")

package io.github.nexalloy.activity

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.preference.CheckBoxPreference
import android.preference.Preference
import android.preference.PreferenceFragment
import android.preference.PreferenceScreen
import android.provider.Settings
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.SearchView
import io.github.libxposed.service.XposedService
import io.github.nexalloy.AppPatchInfo
import io.github.nexalloy.Patch
import io.github.nexalloy.R
import io.github.nexalloy.appPatchInfoOf
import io.github.nexalloy.isInstalled
import io.github.nexalloy.selectablePatches

class AppPatchSettingsActivity : Activity() {

    companion object {
        const val ARGUMENT_APP_NAME = "app_name_key"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_patch_settings)

        actionBar?.setDisplayHomeAsUpEnabled(true)

        val appName = intent.getStringExtra(ARGUMENT_APP_NAME)
        actionBar?.title = appName

        if (savedInstanceState != null) return
        val fragment = AppPatchSettingsFragment().apply {
            arguments = Bundle().apply {
                putString(ARGUMENT_APP_NAME, appName)
            }
        }
        fragmentManager.beginTransaction()
            .replace(R.id.app_patch_settings_container, fragment)
            .commit()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            onBackPressed()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    @Suppress("OVERRIDE_DEPRECATION")
    class AppPatchSettingsFragment : PreferenceFragment(),
        SettingApplication.ServiceStateListener {

        private var mService: XposedService? = null

        private var appPatchInfo: AppPatchInfo? = null
        private var remotePrefs: SharedPreferences? = null

        /** Every checkbox, in display order, regardless of the active search filter. */
        private val patchPreferences = mutableListOf<CheckBoxPreference>()
        /** Preferences pinned above the list; hidden while a search filter is active. */
        private val headerPreferences = mutableListOf<Preference>()
        private var searchQuery: String = ""

        /** The service the current screen was built against; null until the first build. */
        private var builtForService: XposedService? = null
        private var built = false

        @Deprecated("Deprecated in Java")
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            appPatchInfo = appPatchInfoOf(arguments?.getString(ARGUMENT_APP_NAME))
            setHasOptionsMenu(true)
        }

        override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
            if (appPatchInfo == null || patchPreferences.isEmpty()) return
            inflater.inflate(R.menu.app_patch_settings_menu, menu)

            val searchItem = menu.findItem(R.id.menu_search_patches)
            val searchView = searchItem.actionView as? SearchView ?: return
            searchView.queryHint = getString(R.string.search_patches_hint)
            searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String?) = true

                override fun onQueryTextChange(newText: String?): Boolean {
                    applyFilter(newText.orEmpty())
                    return true
                }
            })
            searchItem.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
                override fun onMenuItemActionExpand(item: MenuItem) = true

                override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                    applyFilter("")
                    return true
                }
            })
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
            // This arrives on a binder thread; every path below touches the view hierarchy.
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                // onStart re-dispatches the current state; rebuilding then would drop the
                // active search and the scroll position for no reason.
                if (built && builtForService === service) return@runOnUiThread
                buildScreen(service)
            }
        }

        private fun buildScreen(service: XposedService?) {
            val context = activity ?: return
            val screen = preferenceManager.createPreferenceScreen(context)
            preferenceScreen = screen
            built = true
            builtForService = service
            patchPreferences.clear()
            headerPreferences.clear()
            remotePrefs = null

            val info = appPatchInfo
            if (info == null) {
                screen.addInfo(R.string.unknown_app_title, R.string.unknown_app_summary)
                updateSubtitle()
                return
            }

            val prefs = service?.let {
                runCatching { it.getRemotePreferences(info.packageName) }.getOrNull()
            }
            if (prefs == null) {
                // Without the Xposed service nothing can be read or written, so say so instead of
                // rendering an empty screen (or writing the failure into the action bar title).
                screen.addInfo(
                    R.string.service_unavailable_title,
                    R.string.service_unavailable_summary
                )
                updateSubtitle()
                return
            }
            remotePrefs = prefs

            val selectable = info.selectablePatches
            if (selectable.isEmpty()) {
                screen.addInfo(null, R.string.no_patches_available)
                updateSubtitle()
                return
            }

            headerPreferences += buildHeaderPreference(context, info)

            if (!info.isInstalled(context.packageManager)) {
                headerPreferences += Preference(context).apply {
                    summary = getString(R.string.app_not_installed_summary, info.appName)
                    isSelectable = false
                }
            }

            selectable.sortedBy { it.name }.forEach { patch ->
                patchPreferences += CheckBoxPreference(context).apply {
                    key = patch.name // Pref Key
                    title = patch.name
                    summary = patch.description.takeIf { it.isNotBlank() }
                    // The only store of record is the host app's remote preferences. Leaving
                    // persistence on made the framework mirror each toggle into the module's own
                    // SharedPreferences and then prefer that stale copy when rebuilding the list.
                    isPersistent = false
                    isChecked = prefs.getBoolean(patch.name, patch.use)
                    setOnPreferenceChangeListener { _, newValue ->
                        prefs.edit().putBoolean(key, newValue as Boolean).apply()
                        // isChecked is applied by the framework after this returns.
                        updateSubtitle(pendingChange = patch.name to newValue)
                        performHaptic(context)
                        true
                    }
                }
            }

            applyFilter(searchQuery)
            activity?.invalidateOptionsMenu()
        }

        private fun buildHeaderPreference(context: Context, info: AppPatchInfo) =
            object : Preference(context) {
                @Deprecated("Deprecated in Java")
                override fun onBindView(view: View) {
                    super.onBindView(view)
                    view.findViewById<Button>(R.id.button_default).setOnClickListener {
                        setAll { patch -> patch.use }
                    }
                    view.findViewById<Button>(R.id.button_all).setOnClickListener {
                        setAll { true }
                    }
                    view.findViewById<Button>(R.id.button_none).setOnClickListener {
                        setAll { false }
                    }
                    view.findViewById<Button>(R.id.button_app_info).apply {
                        val installed = info.isInstalled(context.packageManager)
                        visibility = if (installed) View.VISIBLE else View.GONE
                        setOnClickListener {
                            runCatching {
                                startActivity(
                                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                        .setData(Uri.parse("package:${info.packageName}"))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                        }
                    }
                }
            }.apply {
                layoutResource = R.layout.preference_header_buttons
                isSelectable = false
            }

        /** Re-populates the screen with the checkboxes matching [query]. */
        private fun applyFilter(query: String) {
            searchQuery = query
            val screen = preferenceScreen ?: return
            val context = activity ?: return
            if (patchPreferences.isEmpty()) return

            val needle = query.trim()
            val matches = if (needle.isEmpty()) patchPreferences else patchPreferences.filter {
                it.title?.contains(needle, ignoreCase = true) == true ||
                        it.summary?.contains(needle, ignoreCase = true) == true
            }

            screen.removeAll()
            // Bulk actions apply to every patch, not just the visible ones, so the buttons only
            // make sense while the list is unfiltered.
            if (needle.isEmpty()) headerPreferences.forEach { screen.addPreference(it) }
            matches.forEach { screen.addPreference(it) }

            if (matches.isEmpty()) {
                screen.addPreference(Preference(context).apply {
                    setTitle(R.string.no_patches_match_title)
                    summary = getString(R.string.no_patches_match_summary, needle)
                    isSelectable = false
                })
            }
            updateSubtitle()
        }

        /**
         * @param pendingChange the toggle the framework has not applied yet, so the counter can
         * reflect the tap the user just made rather than the state before it.
         */
        private fun updateSubtitle(pendingChange: Pair<String, Boolean>? = null) {
            val actionBar = activity?.actionBar ?: return
            val info = appPatchInfo
            actionBar.title = info?.appName ?: getString(R.string.unknown_app_title)

            if (patchPreferences.isEmpty()) {
                actionBar.subtitle = null
                return
            }
            val enabled = patchPreferences.count {
                if (it.key == pendingChange?.first) pendingChange.second else it.isChecked
            }
            actionBar.subtitle = getString(
                R.string.patch_screen_subtitle, enabled, patchPreferences.size
            )
        }

        private fun setAll(newState: (Patch) -> Boolean) {
            if (!isAdded) return
            val prefs = remotePrefs ?: return
            val byName = appPatchInfo?.selectablePatches?.associateBy { it.name } ?: return

            val editor = prefs.edit()
            patchPreferences.forEach { preference ->
                val patch = byName[preference.key] ?: return@forEach
                val enabled = newState(patch)
                preference.isChecked = enabled
                editor.putBoolean(preference.key, enabled)
            }
            editor.apply()
            updateSubtitle()
        }

        private fun performHaptic(context: Context) {
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            if (!vibrator.hasVibrator()) return
            vibrator.vibrate(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
                } else {
                    VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE)
                }
            )
        }

        private fun PreferenceScreen.addInfo(titleRes: Int?, summaryRes: Int) =
            addInfo(titleRes, getString(summaryRes))

        private fun PreferenceScreen.addInfo(titleRes: Int?, summaryText: String) {
            addPreference(Preference(activity).apply {
                titleRes?.let { setTitle(it) }
                summary = summaryText
                isSelectable = false
            })
        }
    }
}
