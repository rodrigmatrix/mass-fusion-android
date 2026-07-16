package com.limelight

import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.commit
import androidx.preference.Preference
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceManager
import com.limelight.preferences.PreferenceConfiguration
import com.limelight.preferences.StreamSettings
import com.limelight.profiles.ProfilesManager
import com.limelight.profiles.SettingsProfile
import com.limelight.utils.UiHelper
import java.util.HashMap
import java.util.UUID

class EditProfileActivity : AppCompatActivity() {
    private var profileUuid: String? = null
    private var currentProfile: SettingsProfile? = null
    var inMemoryPrefs: InMemorySharedPreferences? = null
        private set
    private var prefsFragment: ProfilePreferenceFragment? = null
    private var pendingProfileName: String? = null

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UiHelper.setLocale(this)

        profileUuid = intent.getStringExtra("profileUuid")

        if (profileUuid != null) {
            val profiles = ProfilesManager.getInstance().profiles
            for (profile in profiles) {
                if (profile.uuid.toString() == profileUuid) {
                    currentProfile = profile
                    break
                }
            }

            if (currentProfile != null) {
                title = getString(R.string.profile_manager_edit_profile) + currentProfile!!.name
                inMemoryPrefs = InMemorySharedPreferences(currentProfile!!.options)
            } else {
                Toast.makeText(this, R.string.profile_manager_profile_not_found, Toast.LENGTH_SHORT).show()
                finish()
                return
            }
        } else {
            title = getString(R.string.profile_manager_new_profile)
            inMemoryPrefs = InMemorySharedPreferences(PreferenceManager.getDefaultSharedPreferences(this).all)
        }

        prefsFragment = ProfilePreferenceFragment(this, inMemoryPrefs!!)

        setContent {
            com.limelight.ui.theme.MassFusionTheme {
                Column(modifier = Modifier.fillMaxSize()) {
                    TopAppBar(
                        title = { Text(title.toString()) },
                        navigationIcon = {
                            IconButton(onClick = { finish() }) {
                                Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                            }
                        },
                        actions = {
                            TextButton(onClick = { showRenameDialog() }) {
                                Text("Rename", color = MaterialTheme.colorScheme.onSurface)
                            }
                            TextButton(onClick = { saveProfile() }) {
                                Text("Save", color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    )

                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { context ->
                            val container = FragmentContainerView(context)
                            container.id = View.generateViewId()
                            supportFragmentManager.commit {
                                replace(container.id, prefsFragment!!)
                            }
                            container
                        },
                        update = { container ->
                            // Update logic if needed
                        }
                    )
                }
            }
        }

        UiHelper.notifyNewRootView(this)
    }

    fun reloadSettings() {
        prefsFragment = ProfilePreferenceFragment(this, prefsFragment!!.getPrefs())
        // Since we are using AndroidView which inflated the fragment, replacing it requires knowing the container ID.
        // It's tricky to get the generated ID here, so we will recreate the activity or use a fixed ID.
        // For simplicity, we just restart the activity if needed, but since it's an in-memory fragment,
        // we might not actually need to reload settings often.
        // Let's just do a recreate() to keep it simple for now, or use a fixed ID in AndroidView.
        recreate()
    }

    private fun saveProfile() {
        val profileOptions = HashMap(inMemoryPrefs!!.all)
        val displayName: String

        if (currentProfile != null) {
            currentProfile!!.options = profileOptions
            currentProfile!!.modifiedUtc = System.currentTimeMillis()
            displayName = currentProfile!!.name
            ProfilesManager.getInstance().update(currentProfile)
        } else {
            var profileName = pendingProfileName?.trim()
            if (profileName.isNullOrEmpty()) {
                profileName = getString(R.string.profile_manager_profile) + (ProfilesManager.getInstance().profiles.size + 1)
            }
            val now = System.currentTimeMillis()
            val newProfile = SettingsProfile(
                UUID.randomUUID(),
                profileName,
                now,
                now,
                profileOptions
            )
            displayName = profileName
            ProfilesManager.getInstance().add(newProfile)
        }

        if (ProfilesManager.getInstance().save(this)) {
            Toast.makeText(
                this,
                getString(R.string.profile_manager_profile_saved, displayName),
                Toast.LENGTH_SHORT
            ).show()
        } else {
            Toast.makeText(this, R.string.profile_manager_failed_to_save, Toast.LENGTH_LONG).show()
        }
        finish()
    }

    private fun showRenameDialog() {
        val input = EditText(this)
        val initial = currentProfile?.name ?: (pendingProfileName ?: "")
        input.setText(initial)
        input.setSelection(initial.length)

        AlertDialog.Builder(this)
            .setTitle(R.string.profile_manager_edit_profile_name)
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isEmpty()) {
                    Toast.makeText(this, R.string.profile_manager_name_cannot_be_blank, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                if (currentProfile != null) {
                    currentProfile!!.name = newName
                    currentProfile!!.modifiedUtc = System.currentTimeMillis()
                    ProfilesManager.getInstance().update(currentProfile)
                    title = getString(R.string.profile_manager_edit_profile_with, newName)
                } else {
                    pendingProfileName = newName
                    title = getString(R.string.profile_manager_new_profile_with, newName)
                }
                
                // Re-setContent to update title
                setContent {
                    com.limelight.ui.theme.MassFusionTheme {
                        Column(modifier = Modifier.fillMaxSize()) {
                            @OptIn(ExperimentalMaterial3Api::class)
                            TopAppBar(
                                title = { Text(title.toString()) },
                                navigationIcon = {
                                    IconButton(onClick = { finish() }) {
                                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                                    }
                                },
                                actions = {
                                    TextButton(onClick = { showRenameDialog() }) {
                                        Text("Rename", color = MaterialTheme.colorScheme.onSurface)
                                    }
                                    TextButton(onClick = { saveProfile() }) {
                                        Text("Save", color = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            )
        
                            AndroidView(
                                modifier = Modifier.fillMaxSize(),
                                factory = { context ->
                                    val container = FragmentContainerView(context)
                                    container.id = View.generateViewId()
                                    supportFragmentManager.commit {
                                        replace(container.id, prefsFragment!!)
                                    }
                                    container
                                }
                            )
                        }
                    }
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    class ProfilePreferenceFragment(context: EditProfileActivity, prefs: SharedPreferences) : StreamSettings.SettingsFragment(PreferenceConfiguration.readPreferences(context, prefs)) {

        private class InMemoryPreferenceDataStore(val prefs: SharedPreferences) : androidx.preference.PreferenceDataStore() {
            override fun putString(key: String, value: String?) { prefs.edit().putString(key, value).apply() }
            override fun putStringSet(key: String, values: Set<String>?) { prefs.edit().putStringSet(key, values).apply() }
            override fun putInt(key: String, value: Int) { prefs.edit().putInt(key, value).apply() }
            override fun putBoolean(key: String, value: Boolean) { prefs.edit().putBoolean(key, value).apply() }
            override fun putFloat(key: String, value: Float) { prefs.edit().putFloat(key, value).apply() }
            override fun putLong(key: String, value: Long) { prefs.edit().putLong(key, value).apply() }

            override fun getString(key: String, defValue: String?): String? = prefs.getString(key, defValue)
            override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? = prefs.getStringSet(key, defValues)
            override fun getInt(key: String, defValue: Int): Int {
                val value = prefs.all[key]
                return if (value is Number) value.toInt() else defValue
            }
            override fun getBoolean(key: String, defValue: Boolean): Boolean = prefs.getBoolean(key, defValue)
            override fun getFloat(key: String, defValue: Float): Float = prefs.getFloat(key, defValue)
            override fun getLong(key: String, defValue: Long): Long {
                val value = prefs.all[key]
                return if (value is Number) value.toLong() else defValue
            }
        }

        public override fun getPrefs(): SharedPreferences {
            return (preferenceManager.preferenceDataStore as InMemoryPreferenceDataStore).prefs
        }

        override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
            return super.onCreateView(inflater, container, savedInstanceState, true)
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            val act = requireActivity() as EditProfileActivity
            val memPrefs = act.inMemoryPrefs!!
            preferenceManager.preferenceDataStore = InMemoryPreferenceDataStore(memPrefs)

            super.onCreatePreferences(savedInstanceState, rootKey)

            val prefScreen = preferenceScreen

            var pref = findPreference<Preference>("option_reset_osc_preference")
            pref?.isVisible = false
            pref = findPreference("import_keyboard_file")
            pref?.isVisible = false
            pref = findPreference("export_keyboard_file")
            pref?.isVisible = false
            pref = findPreference("import_special_button_file")
            pref?.isVisible = false
            pref = findPreference("option_help_custom_keys")
            pref?.isVisible = false

            val patch = diff(PreferenceManager.getDefaultSharedPreferences(act).all, memPrefs.all)
            highlightPreferences(prefScreen, patch.keys)
        }

        override fun reloadSettings() {
            (requireActivity() as EditProfileActivity).reloadSettings()
        }

        private fun highlightPreferences(pref: Preference?, changedKeys: Set<String>) {
            if (pref == null) return

            if (pref is PreferenceGroup) {
                for (i in 0 until pref.preferenceCount) {
                    highlightPreferences(pref.getPreference(i), changedKeys)
                }
            } else {
                val key = pref.key
                if (key != null && changedKeys.contains(key)) {
                    pref.title = "*" + pref.title
                }
            }
        }

        companion object {
            private fun diff(target: Map<String, *>, newPrefs: Map<String, *>): Map<String, Any> {
                val patch = HashMap<String, Any>()
                for ((k, v) in target) {
                    if (newPrefs.containsKey(k)) {
                        val def = newPrefs[k]
                        if (v == null || v != def) {
                            if (v != null) patch[k] = v
                        }
                    } else {
                        if (v != null) patch[k] = v
                    }
                }
                return patch
            }
        }
    }

    class InMemorySharedPreferences(initialValues: Map<String, *>) : SharedPreferences {
        private val values = HashMap<String, Any>()

        init {
            for ((k, v) in initialValues) {
                if (v != null) values[k] = v
            }
        }

        override fun getAll(): Map<String, *> = HashMap(values)

        override fun getString(key: String, defValue: String?): String? {
            val value = values[key]
            return if (value is String) value else defValue
        }

        override fun getInt(key: String, defValue: Int): Int {
            val value = values[key]
            return if (value is Number) value.toInt() else defValue
        }

        override fun getLong(key: String, defValue: Long): Long {
            val value = values[key]
            return if (value is Number) value.toLong() else defValue
        }

        override fun getFloat(key: String, defValue: Float): Float {
            val value = values[key]
            return if (value is Float) value else defValue
        }

        override fun getBoolean(key: String, defValue: Boolean): Boolean {
            val value = values[key]
            return if (value is Boolean) value else defValue
        }

        override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? {
            val value = values[key]
            return if (value is Set<*>) value as Set<String> else defValues
        }

        override fun contains(key: String): Boolean = values.containsKey(key)

        override fun edit(): SharedPreferences.Editor = InMemoryEditor()

        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        private inner class InMemoryEditor : SharedPreferences.Editor {
            private val changes = HashMap<String, Any?>()

            override fun putString(key: String, value: String?): SharedPreferences.Editor {
                changes[key] = value
                return this
            }

            override fun putInt(key: String, value: Int): SharedPreferences.Editor {
                changes[key] = value
                return this
            }

            override fun putLong(key: String, value: Long): SharedPreferences.Editor {
                changes[key] = value
                return this
            }

            override fun putFloat(key: String, value: Float): SharedPreferences.Editor {
                changes[key] = value
                return this
            }

            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor {
                changes[key] = value
                return this
            }

            override fun putStringSet(key: String, values: Set<String>?): SharedPreferences.Editor {
                changes[key] = values
                return this
            }

            override fun remove(key: String): SharedPreferences.Editor {
                changes[key] = null
                return this
            }

            override fun clear(): SharedPreferences.Editor {
                values.clear()
                return this
            }

            override fun commit(): Boolean {
                apply()
                return true
            }

            override fun apply() {
                for ((key, value) in changes) {
                    if (value == null) {
                        values.remove(key)
                    } else {
                        values[key] = value
                    }
                }
                changes.clear()
            }
        }
    }
}
