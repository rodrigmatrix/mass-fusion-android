package com.limelight.utils

import android.annotation.TargetApi
import android.app.Activity
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.widget.Toast
import com.limelight.LimeLog
import com.limelight.R
import com.limelight.nvstream.http.ComputerDetails
import com.limelight.nvstream.http.NvApp
import java.io.IOException
import java.util.*

class ShortcutHelper(private val context: Activity) {
    private val sm: ShortcutManager? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
        context.getSystemService(ShortcutManager::class.java)
    } else {
        null
    }
    private val tvChannelHelper: TvChannelHelper = TvChannelHelper(context)

    companion object {
        const val REQUEST_CODE_EXPORT_ART_FILE: Int = 778
        @JvmField
        var artFileContentToExport: String? = null

        const val KEY_HOST_UUID: String = "host_uuid"
        const val KEY_HOST_NAME: String = "host_name"
        const val KEY_APP_UUID: String = "app_uuid"
        const val KEY_APP_NAME: String = "app_name"
        const val KEY_APP_ID: String = "app_id"

        @JvmStatic
        fun writeArtFileToUri(activityContext: Activity, uri: Uri?) {
            if (uri == null) {
                LimeLog.warning("writeArtFileToUri: URI is null.")
                Toast.makeText(activityContext, R.string.file_export_failed_no_location_selected, Toast.LENGTH_LONG).show()
                artFileContentToExport = null
                return
            }

            if (artFileContentToExport.isNullOrEmpty()) {
                LimeLog.warning("writeArtFileToUri: No content to export.")
                Toast.makeText(activityContext, R.string.file_export_failed_no_content_to_write, Toast.LENGTH_LONG).show()
                return
            }

            try {
                activityContext.contentResolver.openOutputStream(uri)?.use { outputStream ->
                    outputStream.write(artFileContentToExport!!.toByteArray())
                    outputStream.flush()
                    LimeLog.info("Successfully wrote .art file to: $uri")
                    Toast.makeText(activityContext, R.string.file_exported_successfully, Toast.LENGTH_SHORT).show()
                } ?: run {
                    LimeLog.severe("Failed to open output stream for URI: $uri")
                    Toast.makeText(activityContext, R.string.failed_to_open_file_for_writing, Toast.LENGTH_LONG).show()
                }
            } catch (e: IOException) {
                LimeLog.severe("Error writing .art file to URI: $uri - ${e.message}")
                Toast.makeText(activityContext, activityContext.getString(R.string.error_writing_file, e.message), Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                LimeLog.severe("Unexpected error writing .art file to URI: $uri - ${e.message}")
                Toast.makeText(activityContext, R.string.unexpected_error_during_file_export, Toast.LENGTH_LONG).show()
            } finally {
                artFileContentToExport = null
            }
        }
    }

    @TargetApi(Build.VERSION_CODES.N_MR1)
    private fun reapShortcutsForDynamicAdd() {
        sm ?: return
        val dynamicShortcuts = sm.dynamicShortcuts
        while (dynamicShortcuts.isNotEmpty() && dynamicShortcuts.size >= sm.maxShortcutCountPerActivity) {
            var maxRankShortcut = dynamicShortcuts[0]
            for (scut in dynamicShortcuts) {
                if (maxRankShortcut.rank < scut.rank) {
                    maxRankShortcut = scut
                }
            }
            sm.removeDynamicShortcuts(listOf(maxRankShortcut.id))
        }
    }

    @TargetApi(Build.VERSION_CODES.N_MR1)
    private fun getAllShortcuts(): List<ShortcutInfo> {
        if (sm == null) return emptyList()
        val list = LinkedList<ShortcutInfo>()
        list.addAll(sm.dynamicShortcuts)
        list.addAll(sm.pinnedShortcuts)
        return list
    }

    @TargetApi(Build.VERSION_CODES.N_MR1)
    private fun getInfoForId(id: String): ShortcutInfo? {
        val shortcuts = getAllShortcuts()
        for (info in shortcuts) {
            if (info.id == id) {
                return info
            }
        }
        return null
    }

    @TargetApi(Build.VERSION_CODES.N_MR1)
    private fun isExistingDynamicShortcut(id: String): Boolean {
        sm ?: return false
        for (si in sm.dynamicShortcuts) {
            if (si.id == id) {
                return true
            }
        }
        return false
    }

    fun reportComputerShortcutUsed(computer: ComputerDetails) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            if (getInfoForId(computer.uuid) != null) {
                sm?.reportShortcutUsed(computer.uuid)
            }
        }
    }

    fun reportGameLaunched(computer: ComputerDetails, app: NvApp) {
        tvChannelHelper.createTvChannel(computer)
        tvChannelHelper.addGameToChannel(computer, app)
    }

    fun createAppViewShortcut(computer: ComputerDetails, forceAdd: Boolean, newlyPaired: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1 && sm != null) {
            val sinfo = ShortcutInfo.Builder(context, computer.uuid)
                .setIntent(ServerHelper.createPcShortcutIntent(context, computer))
                .setShortLabel(computer.name)
                .setLongLabel(computer.name)
                .setIcon(Icon.createWithResource(context, R.mipmap.ic_pc_scut))
                .build()

            val existingSinfo = getInfoForId(computer.uuid)
            if (existingSinfo != null) {
                sm.updateShortcuts(listOf(sinfo))
                sm.enableShortcuts(listOf(computer.uuid))
            }

            if (!isExistingDynamicShortcut(computer.uuid)) {
                if (forceAdd) {
                    reapShortcutsForDynamicAdd()
                }

                if (sm.dynamicShortcuts.size < sm.maxShortcutCountPerActivity) {
                    sm.addDynamicShortcuts(listOf(sinfo))
                }
            }
        }

        if (newlyPaired) {
            tvChannelHelper.createTvChannel(computer)
            tvChannelHelper.requestChannelOnHomeScreen(computer)
        }
    }

    fun createAppViewShortcutForOnlineHost(details: ComputerDetails) {
        createAppViewShortcut(details, forceAdd = false, newlyPaired = false)
    }

    private fun getShortcutIdForGame(computer: ComputerDetails, app: NvApp): String {
        return computer.uuid + app.appId
    }

    @TargetApi(Build.VERSION_CODES.O)
    fun createPinnedGameShortcut(computer: ComputerDetails, app: NvApp, iconBits: Bitmap?): Boolean {
        if (sm?.isRequestPinShortcutSupported == true) {
            val appIcon: Icon = if (iconBits != null) {
                Icon.createWithAdaptiveBitmap(iconBits)
            } else {
                Icon.createWithResource(context, R.mipmap.ic_pc_scut)
            }

            val sInfo = ShortcutInfo.Builder(context, getShortcutIdForGame(computer, app))
                .setIntent(ServerHelper.createAppShortcutIntent(context, computer, app))
                .setShortLabel("${app.appName} (${computer.name})")
                .setIcon(appIcon)
                .build()

            return sm.requestPinShortcut(sInfo, null)
        } else {
            return false
        }
    }

    fun disableComputerShortcut(computer: ComputerDetails, reason: CharSequence) {
        tvChannelHelper.deleteChannel(computer)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1 && sm != null) {
            if (getInfoForId(computer.uuid) != null) {
                sm.disableShortcuts(listOf(computer.uuid), reason)
            }

            val shortcuts = getAllShortcuts()
            val appShortcutIds = LinkedList<String>()
            for (info in shortcuts) {
                if (info.id.startsWith(computer.uuid)) {
                    appShortcutIds.add(info.id)
                }
            }
            sm.disableShortcuts(appShortcutIds, reason)
        }
    }

    fun disableAppShortcut(computer: ComputerDetails, app: NvApp, reason: CharSequence) {
        tvChannelHelper.deleteProgram(computer, app)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1 && sm != null) {
            val id = getShortcutIdForGame(computer, app)
            if (getInfoForId(id) != null) {
                sm.disableShortcuts(listOf(id), reason)
            }
        }
    }

    fun enableAppShortcut(computer: ComputerDetails, app: NvApp) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1 && sm != null) {
            val id = getShortcutIdForGame(computer, app)
            if (getInfoForId(id) != null) {
                sm.enableShortcuts(listOf(id))
            }
        }
    }

    fun exportLauncherFile(computer: ComputerDetails?, app: NvApp?) {
        if (computer?.uuid.isNullOrEmpty() || computer?.name.isNullOrEmpty()) {
            Toast.makeText(context, R.string.export_launcher_computer_details_incomplete, Toast.LENGTH_LONG).show()
            LimeLog.warning("exportLauncherFile: Computer details incomplete.")
            return
        }

        if (app?.appName.isNullOrEmpty() || app?.appUUID.isNullOrEmpty()) {
            Toast.makeText(context, R.string.export_launcher_app_details_incomplete, Toast.LENGTH_LONG).show()
            LimeLog.warning("exportLauncherFile: App details incomplete.")
            return
        }

        val sb = java.lang.StringBuilder()
        sb.append("# Artemis app entry\n")
        sb.append("# Generated by Artemis for Android\n\n")

        sb.append("[").append(KEY_HOST_UUID).append("] ").append(computer!!.uuid).append("\n")
        sb.append("[").append(KEY_HOST_NAME).append("] ").append(computer.name).append("\n")

        if (!app!!.appUUID.isNullOrEmpty()) {
            sb.append("[").append(KEY_APP_UUID).append("] ").append(app.appUUID).append("\n")
        }
        if (!app.appName.isNullOrEmpty()) {
            sb.append("[").append(KEY_APP_NAME).append("] ").append(app.appName).append("\n")
        } else {
            if (app.appId > 0) {
                sb.append("[").append(KEY_APP_ID).append("] ").append(app.appId).append("\n")
            }
        }

        artFileContentToExport = sb.toString()

        val fileName = app.appName.trim() + ".art"

        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.type = "application/octet-stream"
        intent.putExtra(Intent.EXTRA_TITLE, fileName)

        try {
            context.startActivityForResult(intent, REQUEST_CODE_EXPORT_ART_FILE)
        } catch (e: Exception) {
            LimeLog.severe("Failed to start activity for file export: " + e.message)
            Toast.makeText(context, context.getString(R.string.failed_to_initiate_file_export, e.message), Toast.LENGTH_LONG).show()
            artFileContentToExport = null
        }
    }
}
