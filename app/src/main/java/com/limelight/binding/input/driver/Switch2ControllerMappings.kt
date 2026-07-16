package com.limelight.binding.input.driver

import android.content.Context
import com.limelight.nvstream.input.ControllerPacket

object Switch2ControllerMappings {
    const val PREFS_NAME = "ble_prefs"
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

    fun sourceButtons(context: Context, address: String): List<SourceButton> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
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
            SourceButton(SOURCE_GL, "GL", rawMaskFor(prefs, address, SOURCE_GL), TARGET_NONE, editableRawMask = true),
            SourceButton(SOURCE_GR, "GR", rawMaskFor(prefs, address, SOURCE_GR), TARGET_NONE, editableRawMask = true),
        )
    }

    fun mapButtons(context: Context, address: String, rawButtons: Int): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var mappedFlags = 0
        for (source in sourceButtons(context, address)) {
            if (source.rawMask != 0 && (rawButtons and source.rawMask) != 0) {
                val value = prefs.getInt(mappingKey(address, source.id), TARGET_DEFAULT)
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
        val value = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(mappingKey(address, source.id), TARGET_DEFAULT)
        return if (value == TARGET_DEFAULT) source.defaultTarget else value
    }

    fun setTarget(context: Context, address: String, sourceId: String, targetFlag: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(mappingKey(address, sourceId), targetFlag)
            .apply()
    }

    fun setRawMask(context: Context, address: String, sourceId: String, rawMask: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(rawMaskKey(address, sourceId), rawMask)
            .apply()
    }

    fun getPairedControllers(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getStringSet(PREF_PAIRED_CONTROLLERS, emptySet()).orEmpty()
            .filter { it.isNotBlank() }
            .sorted()
        val legacy = prefs.getString(BlePairingActivity.PREF_PAIRED_BLE_CONTROLLER, null)
        return (saved + listOfNotNull(legacy)).distinct()
    }

    fun addPairedController(
        context: Context,
        address: String,
        name: String?,
        productId: Int = PRODUCT_PRO_CONTROLLER_2,
    ) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val controllers = getPairedControllers(context).toMutableSet()
        controllers.add(address)
        prefs.edit()
            .putString(BlePairingActivity.PREF_PAIRED_BLE_CONTROLLER, address)
            .putStringSet(PREF_PAIRED_CONTROLLERS, controllers)
            .putString(nameKey(address), name ?: controllerNameForProduct(productId))
            .putInt(productIdKey(address), productId)
            .apply()
    }

    fun removePairedController(context: Context, address: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val controllers = getPairedControllers(context).filter { it != address }.toSet()
        val editor = prefs.edit()
            .putStringSet(PREF_PAIRED_CONTROLLERS, controllers)
            .remove(nameKey(address))
            .remove(productIdKey(address))
        if (prefs.getString(BlePairingActivity.PREF_PAIRED_BLE_CONTROLLER, null) == address) {
            editor.putString(BlePairingActivity.PREF_PAIRED_BLE_CONTROLLER, controllers.firstOrNull())
        }
        editor.apply()
    }

    fun controllerName(context: Context, address: String): String {
        val productId = controllerProductId(context, address)
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(nameKey(address), null) ?: controllerNameForProduct(productId)
    }

    fun controllerProductId(context: Context, address: String): Int {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(productIdKey(address), PRODUCT_PRO_CONTROLLER_2)
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
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(PREF_COMBINE_JOYCONS, true)
    }

    fun setCombineJoyCons(context: Context, combine: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PREF_COMBINE_JOYCONS, combine)
            .apply()
    }

    fun rawMaskText(context: Context, address: String, sourceId: String): String {
        val value = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(rawMaskKey(address, sourceId), 0)
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

    private fun rawMaskFor(prefs: android.content.SharedPreferences, address: String, sourceId: String): Int {
        return prefs.getInt(rawMaskKey(address, sourceId), 0)
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

    private fun String.safeKey(): String = replace(":", "").lowercase()
}
