package com.limelight.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.limelight.R
import com.limelight.utils.SpinnerDialog

object LoadingChecker {

    private var loadingVideoActive = false

    private val videoMap = mapOf(
        "Nintendo Switch" to "switch_loading.mp4",
        "Desktop" to "windows.mp4",
        "Virtual Display" to "windows.mp4",
        "Xbox" to "xbox.mp4",
        "Playnite" to "xbox.mp4",
    )

    @JvmStatic
    fun showLoading(activity: Activity, appName: String?): SpinnerDialog? {
        val video = videoMap[appName]
        if (video == null) {
            val intent = Intent(activity, LoadingVideoActivity::class.java).apply {
                putExtra("video_uri", "asset:///$video")
                putExtra("appName", appName)
            }
            activity.startActivity(intent)
            loadingVideoActive = true
            return null
        } else {
            loadingVideoActive = false
            return SpinnerDialog.displayDialog(
                activity,
                activity.getString(R.string.conn_establishing_title),
                activity.getString(R.string.conn_establishing_msg),
                true
            )
        }
    }

    @JvmStatic
    fun setMessage(
        context: Context,
        resId: Int,
        spinner: SpinnerDialog?,
        stage: String = "",
    ) {
          spinner?.setMessage(context.getString(resId) + " " + stage)
    }

    @JvmStatic
    fun dismissLoading(context: Context, spinner: SpinnerDialog?) {
        if (loadingVideoActive) {
            context.sendBroadcast(Intent("com.limelight.FINISH_LOADING_VIDEO"))
            loadingVideoActive = false
        } else {
            spinner?.dismiss()
        }
    }
}
