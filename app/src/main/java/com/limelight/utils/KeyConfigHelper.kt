package com.limelight.utils

import com.google.gson.Gson

object KeyConfigHelper {
    class ShortcutFile(
        @JvmField var data: List<Shortcut> = ArrayList()
    )

    class Shortcut(
        @JvmField var id: String? = null,
        @JvmField var name: String? = null,
        @JvmField var sticky: Boolean = false,
        @JvmField var keys: List<String> = ArrayList()
    )

    @JvmStatic
    fun parseShortcutFile(json: String): ShortcutFile {
        val gson = Gson()
        return gson.fromJson(json, ShortcutFile::class.java)
    }
}
