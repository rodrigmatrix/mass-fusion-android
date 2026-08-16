package com.limelight.binding.input.driver

import android.content.Context
import android.content.SharedPreferences
import com.limelight.nvstream.input.ControllerPacket

object Switch2ControllerMappings {
    const val PREFS_NAME = "switch2_controller_prefs"
    const val PREF_PAIRED_CONTROLLERS = "paired_ble_controller_macs"
    const val NINTENDO_VENDOR_ID = 0x057e
    const val PRODUCT_JOYCON_2_RIGHT = 0x2066
    const val PRODUCT_JOYCON_2_LEFT = 0x2067
    const val PRODUCT_JOYCON_L = 0x2006
    const val PRODUCT_JOYCON_R = 0x2007
    const val PRODUCT_PRO_CONTROLLER_2 = 0x2069
    const val PRODUCT_NSO_GAMECUBE_CONTROLLER = 0x2073
    private const val PREF_COMBINE_JOYCONS = "combine_joycons"

    private const val TARGET_DEFAULT = -1
    private const val TARGET_NONE = 0

    private const val SOURCE_GL = "gl"
    private const val SOURCE_GR = "gr"

    data class SourceButton(
        val id: String,
        val label: String,
        val rawMask: Int,
        val defaultTarget: Int,
        val editableRawMask: Boolean = false,
    )

    data class TargetButton(
        val label: String,
        val flag: Int,
    )

    val targetButtons = listOf(
        TargetButton("Default", TARGET_DEFAULT),
        TargetButton("Unmapped", TARGET_NONE),
        TargetButton("A", ControllerPacket.A_FLAG),
        TargetButton("B", ControllerPacket.B_FLAG),
        TargetButton("X", ControllerPacket.X_FLAG),
        TargetButton("Y", ControllerPacket.Y_FLAG),
        TargetButton("D-pad Up", ControllerPacket.UP_FLAG),
        TargetButton("D-pad Down", ControllerPacket.DOWN_FLAG),
        TargetButton("D-pad Left", ControllerPacket.LEFT_FLAG),
        TargetButton("D-pad Right", ControllerPacket.RIGHT_FLAG),
        TargetButton("L", ControllerPacket.LB_FLAG),
        TargetButton("R", ControllerPacket.RB_FLAG),
        TargetButton("L3", ControllerPacket.LS_CLK_FLAG),
        TargetButton("R3", ControllerPacket.RS_CLK_FLAG),
        TargetButton("Minus / Back", ControllerPacket.BACK_FLAG),
        TargetButton("Plus / Start", ControllerPacket.PLAY_FLAG),
        TargetButton("Xbox / PS / Home", ControllerPacket.SPECIAL_BUTTON_FLAG),
        TargetButton("Touchpad", ControllerPacket.TOUCHPAD_FLAG),
        TargetButton("Misc / Capture", ControllerPacket.MISC_FLAG),
        TargetButton("Paddle 1", ControllerPacket.PADDLE1_FLAG),
        TargetButton("Paddle 2", ControllerPacket.PADDLE2_FLAG),
        TargetButton("Paddle 3", ControllerPacket.PADDLE3_FLAG),
        TargetButton("Paddle 4", ControllerPacket.PADDLE4_FLAG),
    )

    private fun getS2Prefs(context: Context): SharedPreferences {
        return context.getSharedPreferences("switch2_controller_prefs", Context.MODE_PRIVATE)
    }

    private fun getBlePrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences("ble_prefs", Context.MODE_PRIVATE)
    }

    fun sourceButtons(context: Context, address: String): List<SourceButton> {
        val s2 = getS2Prefs(context)
        val ble = getBlePrefs(context)
        val glMask = when {
            s2.contains(rawMaskKey(address, SOURCE_GL)) -> s2.getInt(rawMaskKey(address, SOURCE_GL), 0)
            else -> ble.getInt(rawMaskKey(address, SOURCE_GL), 0)
        }
        val grMask = when {
            s2.contains(rawMaskKey(address, SOURCE_GR)) -> s2.getInt(rawMaskKey(address, SOURCE_GR), 0)
            else -> ble.getInt(rawMaskKey(address, SOURCE_GR), 0)
        }
        return listOf(
            SourceButton("a", "A", 0x00000008, ControllerPacket.A_FLAG),
            SourceButton("b", "B", 0x00000004, ControllerPacket.B_FLAG),
            SourceButton("x", "X", 0x00000002, ControllerPacket.X_FLAG),
            SourceButton("y", "Y", 0x00000001, ControllerPacket.Y_FLAG),
            SourceButton("l", "L", 0x00400000, ControllerPacket.LB_FLAG),
            SourceButton("r", "R", 0x00000040, ControllerPacket.RB_FLAG),
            SourceButton("zl", "ZL", 0x00800000, TARGET_NONE),
            SourceButton("zr", "ZR", 0x00000080, TARGET_NONE),
            SourceButton("minus", "Minus", 0x00000100, ControllerPacket.BACK_FLAG),
            SourceButton("plus", "Plus", 0x00000200, ControllerPacket.PLAY_FLAG),
            SourceButton("left_stick", "Left stick click", 0x00000800, ControllerPacket.LS_CLK_FLAG),
            SourceButton("right_stick", "Right stick click", 0x00000400, ControllerPacket.RS_CLK_FLAG),
            SourceButton("home", "Home", 0x00001000, ControllerPacket.SPECIAL_BUTTON_FLAG),
            SourceButton("capture", "Capture", 0x00002000, ControllerPacket.MISC_FLAG),
            SourceButton("gamechat", "GameChat", 0x00004000, ControllerPacket.MISC_FLAG),
            SourceButton("dpad_up", "D-pad Up", 0x00020000, ControllerPacket.UP_FLAG),
            SourceButton("dpad_down", "D-pad Down", 0x00010000, ControllerPacket.DOWN_FLAG),
            SourceButton("dpad_left", "D-pad Left", 0x00080000, ControllerPacket.LEFT_FLAG),
            SourceButton("dpad_right", "D-pad Right", 0x00040000, ControllerPacket.RIGHT_FLAG),
            SourceButton(SOURCE_GL, "GL", glMask, TARGET_NONE, editableRawMask = true),
            SourceButton(SOURCE_GR, "GR", grMask, TARGET_NONE, editableRawMask = true),
        )
    }

    fun mapButtons(context: Context, address: String, rawButtons: Int): Int {
        val s2 = getS2Prefs(context)
        val ble = getBlePrefs(context)
        var mappedFlags = 0
        for (source in sourceButtons(context, address)) {
            if (source.rawMask != 0 && (rawButtons and source.rawMask) != 0) {
                val value = when {
                    s2.contains(mappingKey(address, source.id)) -> s2.getInt(mappingKey(address, source.id), TARGET_DEFAULT)
                    else -> ble.getInt(mappingKey(address, source.id), TARGET_DEFAULT)
                }
                mappedFlags = mappedFlags or if (value == TARGET_DEFAULT) source.defaultTarget else value
            }
        }
        return mappedFlags
    }

    fun supportedButtonFlags(): Int {
        return targetButtons
            .filter { it.flag != TARGET_DEFAULT && it.flag != TARGET_NONE }
            .fold(0) { flags, target -> flags or target.flag }
    }

    fun targetFor(context: Context, address: String, source: SourceButton): Int {
        val s2 = getS2Prefs(context)
        val ble = getBlePrefs(context)
        val value = when {
            s2.contains(mappingKey(address, source.id)) -> s2.getInt(mappingKey(address, source.id), TARGET_DEFAULT)
            else -> ble.getInt(mappingKey(address, source.id), TARGET_DEFAULT)
        }
        return if (value == TARGET_DEFAULT) source.defaultTarget else value
    }

    fun setTarget(context: Context, address: String, sourceId: String, targetFlag: Int) {
        getS2Prefs(context).edit().putInt(mappingKey(address, sourceId), targetFlag).apply()
        getBlePrefs(context).edit().putInt(mappingKey(address, sourceId), targetFlag).apply()
    }

    fun setRawMask(context: Context, address: String, sourceId: String, rawMask: Int) {
        getS2Prefs(context).edit().putInt(rawMaskKey(address, sourceId), rawMask).apply()
        getBlePrefs(context).edit().putInt(rawMaskKey(address, sourceId), rawMask).apply()
    }

    const val PREF_PAIRED_BLE_CONTROLLER = "paired_ble_controller_mac"

    fun getPairedControllers(context: Context): List<String> {
        val s2 = getS2Prefs(context)
        val ble = getBlePrefs(context)
        val result = mutableListOf<String>()

        // 1. Check comma-separated string or Set in switch2_controller_prefs
        try {
            val str = s2.getString(PREF_PAIRED_CONTROLLERS, null)
            if (!str.isNullOrBlank()) {
                result.addAll(str.split(",").map { it.trim() }.filter { it.isNotEmpty() })
            }
        } catch (_: Exception) {
            try {
                val set = s2.getStringSet(PREF_PAIRED_CONTROLLERS, null)
                if (set != null) result.addAll(set.filter { it.isNotBlank() })
            } catch (_: Exception) {}
        }

        // 2. Check comma-separated string or Set in ble_prefs
        try {
            val str = ble.getString(PREF_PAIRED_CONTROLLERS, null)
            if (!str.isNullOrBlank()) {
                result.addAll(str.split(",").map { it.trim() }.filter { it.isNotEmpty() })
            }
        } catch (_: Exception) {
            try {
                val set = ble.getStringSet(PREF_PAIRED_CONTROLLERS, null)
                if (set != null) result.addAll(set.filter { it.isNotBlank() })
            } catch (_: Exception) {}
        }

        // 3. Check legacy key
        val legacy1 = s2.getString(PREF_PAIRED_BLE_CONTROLLER, null)
        val legacy2 = ble.getString(PREF_PAIRED_BLE_CONTROLLER, null)
        if (!legacy1.isNullOrBlank()) result.add(legacy1.trim())
        if (!legacy2.isNullOrBlank()) result.add(legacy2.trim())

        return result.distinct().filter { it.isNotBlank() }
    }

    fun addPairedController(
        context: Context,
        address: String,
        name: String?,
        productId: Int = PRODUCT_PRO_CONTROLLER_2,
    ) {
        val s2 = getS2Prefs(context)
        val ble = getBlePrefs(context)
        val current = getPairedControllers(context).toMutableList()
        if (!current.contains(address)) {
            current.add(address)
        }
        val csv = current.joinToString(",")
        val ctrlName = name ?: controllerNameForProduct(productId)

        s2.edit()
            .putString(PREF_PAIRED_CONTROLLERS, csv)
            .putString(nameKey(address), ctrlName)
            .putInt(productIdKey(address), productId)
            .apply()

        ble.edit()
            .putString(PREF_PAIRED_BLE_CONTROLLER, address)
            .putStringSet(PREF_PAIRED_CONTROLLERS, current.toSet())
            .putString(nameKey(address), ctrlName)
            .putInt(productIdKey(address), productId)
            .apply()
    }

    fun removePairedController(context: Context, address: String) {
        val current = getPairedControllers(context).filter { it != address }
        val csv = current.joinToString(",")
        getS2Prefs(context).edit()
            .putString(PREF_PAIRED_CONTROLLERS, csv)
            .remove(nameKey(address))
            .remove(productIdKey(address))
            .apply()

        val bleEditor = getBlePrefs(context).edit()
            .putStringSet(PREF_PAIRED_CONTROLLERS, current.toSet())
            .remove(nameKey(address))
            .remove(productIdKey(address))
        if (getBlePrefs(context).getString(PREF_PAIRED_BLE_CONTROLLER, null) == address) {
            bleEditor.putString(PREF_PAIRED_BLE_CONTROLLER, current.firstOrNull())
        }
        bleEditor.apply()
    }

    fun controllerName(context: Context, address: String): String {
        val productId = controllerProductId(context, address)
        val s2 = getS2Prefs(context)
        val ble = getBlePrefs(context)
        return when {
            s2.contains(nameKey(address)) -> s2.getString(nameKey(address), null)
            ble.contains(nameKey(address)) -> ble.getString(nameKey(address), null)
            else -> null
        } ?: controllerNameForProduct(productId)
    }

    fun controllerProductId(context: Context, address: String): Int {
        val s2 = getS2Prefs(context)
        val ble = getBlePrefs(context)
        return when {
            s2.contains(productIdKey(address)) -> s2.getInt(productIdKey(address), PRODUCT_PRO_CONTROLLER_2)
            ble.contains(productIdKey(address)) -> ble.getInt(productIdKey(address), PRODUCT_PRO_CONTROLLER_2)
            else -> PRODUCT_PRO_CONTROLLER_2
        }
    }

    fun isSupportedProductId(productId: Int): Boolean {
        return productId == PRODUCT_PRO_CONTROLLER_2 ||
            productId == PRODUCT_JOYCON_2_LEFT ||
            productId == PRODUCT_JOYCON_2_RIGHT ||
            productId == PRODUCT_JOYCON_L ||
            productId == PRODUCT_JOYCON_R ||
            productId == PRODUCT_NSO_GAMECUBE_CONTROLLER
    }

    fun isJoyConLeft(productId: Int): Boolean = productId == PRODUCT_JOYCON_2_LEFT || productId == PRODUCT_JOYCON_L

    fun isJoyConRight(productId: Int): Boolean = productId == PRODUCT_JOYCON_2_RIGHT || productId == PRODUCT_JOYCON_R

    fun controllerNameForProduct(productId: Int): String {
        return when (productId) {
            PRODUCT_JOYCON_2_LEFT -> "Joy-Con 2 (Left)"
            PRODUCT_JOYCON_2_RIGHT -> "Joy-Con 2 (Right)"
            PRODUCT_JOYCON_L -> "Joy-Con (L)"
            PRODUCT_JOYCON_R -> "Joy-Con (R)"
            PRODUCT_NSO_GAMECUBE_CONTROLLER -> "NSO GameCube Controller"
            PRODUCT_PRO_CONTROLLER_2 -> "Pro Controller 2"
            else -> "Switch 2 Controller"
        }
    }

    fun combineJoyCons(context: Context): Boolean {
        val s2 = getS2Prefs(context)
        val ble = getBlePrefs(context)
        return when {
            s2.contains(PREF_COMBINE_JOYCONS) -> s2.getBoolean(PREF_COMBINE_JOYCONS, true)
            ble.contains(PREF_COMBINE_JOYCONS) -> ble.getBoolean(PREF_COMBINE_JOYCONS, true)
            else -> true
        }
    }

    fun setCombineJoyCons(context: Context, combine: Boolean) {
        getS2Prefs(context).edit().putBoolean(PREF_COMBINE_JOYCONS, combine).apply()
        getBlePrefs(context).edit().putBoolean(PREF_COMBINE_JOYCONS, combine).apply()
    }

    fun stickSensitivity(context: Context, address: String): Float {
        val s2 = getS2Prefs(context)
        val ble = getBlePrefs(context)
        return when {
            s2.contains(sensitivityKey(address)) -> s2.getFloat(sensitivityKey(address), 1.30f)
            ble.contains(sensitivityKey(address)) -> ble.getFloat(sensitivityKey(address), 1.30f)
            else -> 1.30f
        }
    }

    fun setStickSensitivity(context: Context, address: String, sensitivity: Float) {
        getS2Prefs(context).edit().putFloat(sensitivityKey(address), sensitivity).apply()
        getBlePrefs(context).edit().putFloat(sensitivityKey(address), sensitivity).apply()
    }

    fun rawMaskText(context: Context, address: String, sourceId: String): String {
        val s2 = getS2Prefs(context)
        val ble = getBlePrefs(context)
        val value = when {
            s2.contains(rawMaskKey(address, sourceId)) -> s2.getInt(rawMaskKey(address, sourceId), 0)
            else -> ble.getInt(rawMaskKey(address, sourceId), 0)
        }
        return if (value == 0) "" else "0x${value.toUInt().toString(16)}"
    }

    fun parseRawMask(text: String): Int {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return 0
        return if (trimmed.startsWith("0x", ignoreCase = true)) {
            trimmed.substring(2).toUInt(16).toInt()
        } else {
            trimmed.toUInt(16).toInt()
        }
    }

    private fun mappingKey(address: String, sourceId: String): String {
        return "switch2_${address.safeKey()}_${sourceId}_mapping"
    }

    private fun rawMaskKey(address: String, sourceId: String): String {
        return "switch2_${address.safeKey()}_${sourceId}_raw_mask"
    }

    private fun nameKey(address: String): String {
        return "switch2_${address.safeKey()}_name"
    }

    private fun productIdKey(address: String): String {
        return "switch2_${address.safeKey()}_product_id"
    }

    private fun sensitivityKey(address: String): String {
        return "switch2_${address.safeKey()}_stick_sensitivity"
    }

    private fun String.safeKey(): String = replace(":", "").lowercase()
}

