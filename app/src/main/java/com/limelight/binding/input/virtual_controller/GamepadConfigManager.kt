package com.limelight.binding.input.virtual_controller

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.limelight.binding.input.KeyboardTranslator
import com.limelight.binding.input.virtual_controller.models.ButtonAction
import com.limelight.binding.input.virtual_controller.models.CustomButton
import com.limelight.binding.input.virtual_controller.models.GestureBehavior
import com.limelight.binding.input.virtual_controller.models.OscProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

data class GamepadElementConfig(
    val id: String,
    var x: Float = 0f,
    var y: Float = 0f,
    var scale: Float = 1.0f,
    var opacity: Float = 0.7f,
    var isEnabled: Boolean = true
)

class GamepadConfigManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("GamepadProfiles", Context.MODE_PRIVATE)
    private val gson = Gson()

    private val _profiles = MutableStateFlow<List<OscProfile>>(emptyList())
    val profiles: StateFlow<List<OscProfile>> = _profiles.asStateFlow()

    private val _activeProfile = MutableStateFlow<OscProfile?>(null)
    val activeProfile: StateFlow<OscProfile?> = _activeProfile.asStateFlow()

    init {
        loadProfiles()
    }

    private fun loadProfiles() {
        val profilesJson = prefs.getString("profiles", null)
        var loadedProfiles: MutableList<OscProfile> = mutableListOf()
        
        if (profilesJson != null) {
            try {
                val type = object : TypeToken<MutableList<OscProfile>>() {}.type
                val parsed: MutableList<OscProfile>? = gson.fromJson(profilesJson, type)
                
                // Force ClassCastException here if Gson parsed as LinkedTreeMap
                parsed?.forEach { profile ->
                    profile.standardElements.values.forEach { it.x }
                    profile.customButtons.forEach { it.x }
                }
                
                if (parsed != null) {
                    loadedProfiles = parsed
                }
            } catch (e: Exception) {
                // If the saved data was corrupted (e.g. from an old version), start fresh
                e.printStackTrace()
                prefs.edit().remove("profiles").apply()
            }
        }

        loadedProfiles = ensureBuiltInProfiles(loadedProfiles)

        _profiles.value = loadedProfiles

        val activeProfileId = prefs.getString("activeProfileId", null)
        val active = loadedProfiles.find { it.id == activeProfileId } ?: loadedProfiles.first()
        _activeProfile.value = active
    }

    private fun saveProfiles() {
        val profilesJson = gson.toJson(_profiles.value)
        prefs.edit().putString("profiles", profilesJson).apply()
    }

    fun setActiveProfile(profileId: String) {
        val profile = _profiles.value.find { it.id == profileId }
        if (profile != null) {
            _activeProfile.value = profile
            prefs.edit().putString("activeProfileId", profileId).apply()
        }
    }

    fun addProfile(name: String): OscProfile {
        val newProfile = OscProfile(name = name)
        val updatedProfiles = _profiles.value.toMutableList()
        updatedProfiles.add(newProfile)
        _profiles.value = updatedProfiles
        saveProfiles()
        return newProfile
    }

    fun duplicateProfile(sourceProfileId: String, name: String? = null): OscProfile? {
        val source = _profiles.value.find { it.id == sourceProfileId } ?: return null
        val copy = source.copy(
            id = UUID.randomUUID().toString(),
            name = name ?: "${source.name} Copy",
            isDefault = false,
            standardElements = source.standardElements.mapValues { (_, config) -> config.copy() }.toMutableMap(),
            customButtons = source.customButtons.map { it.copy(id = UUID.randomUUID().toString()) }.toMutableList()
        )
        val updatedProfiles = _profiles.value.toMutableList()
        updatedProfiles.add(copy)
        _profiles.value = updatedProfiles
        _activeProfile.value = copy
        prefs.edit().putString("activeProfileId", copy.id).apply()
        saveProfiles()
        return copy
    }

    fun deleteProfile(profileId: String) {
        val updatedProfiles = _profiles.value.toMutableList()
        val profileToRemove = updatedProfiles.find { it.id == profileId }
        if (profileToRemove != null && !profileToRemove.isDefault) {
            updatedProfiles.remove(profileToRemove)
            _profiles.value = updatedProfiles
            saveProfiles()

            if (_activeProfile.value?.id == profileId) {
                setActiveProfile(updatedProfiles.first().id)
            }
        }
    }

    fun updateActiveProfile(profile: OscProfile) {
        val updatedProfiles = _profiles.value.map {
            if (it.id == profile.id) profile else it
        }
        _profiles.value = updatedProfiles
        _activeProfile.value = profile
        saveProfiles()
    }

    fun loadConfig(id: String, defaultX: Float = 0f, defaultY: Float = 0f, defaultScale: Float = 1.0f): GamepadElementConfig {
        val active = _activeProfile.value
        if (active != null) {
            val rawConfig = active.standardElements[id]
            if (rawConfig != null) {
                return rawConfig
            }
        }
        return GamepadElementConfig(id, defaultX, defaultY, defaultScale)
    }

    fun saveConfig(config: GamepadElementConfig) {
        val active = _activeProfile.value
        if (active != null) {
            active.standardElements[config.id] = config
            updateActiveProfile(active)
        }
    }

    fun resetAll() {
        val active = _activeProfile.value
        if (active != null) {
            val builtIn = builtInProfiles().find { it.name == active.name }
            if (builtIn != null) {
                updateActiveProfile(
                    builtIn.copy(
                        id = active.id,
                        isDefault = active.isDefault
                    )
                )
            } else {
                active.standardElements.clear()
                active.customButtons.clear()
                updateActiveProfile(active)
            }
        }
    }

    private fun ensureBuiltInProfiles(existingProfiles: MutableList<OscProfile>): MutableList<OscProfile> {
        val profiles = existingProfiles.toMutableList()
        val existingNames = profiles.map { it.name }.toSet()
        builtInProfiles().forEach { builtIn ->
            if (!existingNames.contains(builtIn.name)) {
                profiles.add(builtIn)
            }
        }
        if (profiles.isEmpty()) {
            profiles.add(builtInProfiles().first())
        }
        return profiles
    }

    private fun builtInProfiles(): List<OscProfile> {
        return listOf(
            OscProfile(name = "Default Gamepad", isDefault = true),
            OscProfile(
                name = "MOBA / Wild Rift",
                isDefault = true,
                customButtons = mutableListOf(
                    actionButton("Q", "Bolt", -20f, 80f, KeyboardTranslator.VK_Q.toShort(), 68f),
                    actionButton("W", "Security", 52f, 34f, KeyboardTranslator.VK_W.toShort(), 64f),
                    actionButton("E", "Star", 122f, 80f, KeyboardTranslator.VK_E.toShort(), 64f),
                    actionButton("R", "FlashOn", 68f, -38f, KeyboardTranslator.VK_R.toShort(), 76f),
                    actionButton("D", "DirectionsRun", -8f, -12f, KeyboardTranslator.VK_D.toShort(), 48f),
                    actionButton("F", "Favorite", 140f, -12f, VK_F_TEMPLATE, 48f),
                    actionButton("Atk", "Send", 212f, 78f, VK_MOUSE_LEFT_TEMPLATE, 86f),
                    actionButton("B", "Home", -258f, 160f, KeyboardTranslator.VK_B.toShort(), 46f)
                )
            ),
            OscProfile(
                name = "Shooter / Red Dead",
                isDefault = true,
                customButtons = mutableListOf(
                    lookArea(118f, 10f, 190f),
                    actionButton("Aim", "MyLocation", 96f, -120f, VK_MOUSE_RIGHT_TEMPLATE, 76f, hold = true),
                    actionButton("Fire", "FlashOn", 210f, -70f, VK_MOUSE_LEFT_TEMPLATE, 82f),
                    actionButton("Reload", "Refresh", 52f, 78f, KeyboardTranslator.VK_R.toShort(), 56f),
                    actionButton("Use", "PanTool", 142f, 112f, KeyboardTranslator.VK_E.toShort(), 56f),
                    actionButton("Crouch", "KeyboardArrowDown", -246f, 72f, KeyboardTranslator.VK_C.toShort(), 54f),
                    actionButton("Sprint", "DirectionsRun", -178f, 144f, KeyboardTranslator.VK_LSHIFT.toShort(), 62f, hold = true),
                    actionButton("Wheel", "Build", 10f, -38f, KeyboardTranslator.VK_TAB.toShort(), 58f, hold = true)
                )
            ),
            OscProfile(
                name = "Stealth Action / Assassin",
                isDefault = true,
                customButtons = mutableListOf(
                    lookArea(116f, 2f, 180f),
                    actionButton("Atk", "Gavel", 200f, 36f, VK_MOUSE_LEFT_TEMPLATE, 74f),
                    actionButton("Parry", "Security", 118f, -42f, KeyboardTranslator.VK_Q.toShort(), 62f),
                    actionButton("Dodge", "DirectionsRun", 48f, 86f, KeyboardTranslator.VK_SPACE.toShort(), 64f),
                    actionButton("Use", "PanTool", 130f, 120f, KeyboardTranslator.VK_E.toShort(), 56f),
                    actionButton("Crouch", "KeyboardArrowDown", -232f, 72f, KeyboardTranslator.VK_C.toShort(), 54f),
                    actionButton("Vision", "Visibility", -28f, -48f, KeyboardTranslator.VK_V.toShort(), 58f),
                    actionButton("Tools", "Build", 44f, -112f, KeyboardTranslator.VK_TAB.toShort(), 58f, hold = true)
                )
            ),
            OscProfile(
                name = "Keyboard QWER",
                isDefault = true,
                customButtons = mutableListOf(
                    actionButton("Q", "Bolt", -46f, 82f, KeyboardTranslator.VK_Q.toShort(), 66f),
                    actionButton("W", "Security", 28f, 36f, KeyboardTranslator.VK_W.toShort(), 66f),
                    actionButton("E", "Star", 102f, 82f, KeyboardTranslator.VK_E.toShort(), 66f),
                    actionButton("R", "FlashOn", 176f, 36f, KeyboardTranslator.VK_R.toShort(), 74f),
                    actionButton("1", "Filter1", -12f, -42f, VK_1_TEMPLATE, 48f),
                    actionButton("2", "Filter2", 58f, -82f, VK_2_TEMPLATE, 48f),
                    actionButton("3", "Filter3", 128f, -42f, VK_3_TEMPLATE, 48f),
                    actionButton("Space", "DirectionsRun", -220f, 132f, KeyboardTranslator.VK_SPACE.toShort(), 70f)
                )
            )
        )
    }

    private fun actionButton(
        label: String,
        iconName: String,
        x: Float,
        y: Float,
        key: Short,
        sizeDp: Float,
        hold: Boolean = false
    ): CustomButton {
        return CustomButton(
            x = x,
            y = y,
            sizeDp = sizeDp,
            label = label,
            iconName = iconName,
            opacity = 0.72f,
            onTapAction = if (hold) null else ButtonAction(listOf(key)),
            onHoldAction = if (hold) ButtonAction(listOf(key)) else null
        )
    }

    private fun lookArea(x: Float, y: Float, sizeDp: Float): CustomButton {
        return CustomButton(
            x = x,
            y = y,
            sizeDp = sizeDp,
            opacity = 0.28f,
            label = "Look",
            iconName = "Visibility",
            isTransparentArea = true,
            gestureBehavior = GestureBehavior.MOUSE_LOOK
        )
    }

    companion object {
        private const val VK_MOUSE_LEFT_TEMPLATE: Short = 0x1001
        private const val VK_MOUSE_RIGHT_TEMPLATE: Short = 0x1002
        private const val VK_1_TEMPLATE: Short = 0x31
        private const val VK_2_TEMPLATE: Short = 0x32
        private const val VK_3_TEMPLATE: Short = 0x33
        private const val VK_F_TEMPLATE: Short = 0x46
    }
}
