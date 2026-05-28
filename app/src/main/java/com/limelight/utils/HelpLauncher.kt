package com.limelight.utils

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.limelight.HelpActivity

object HelpLauncher {
    @JvmStatic
    fun launchUrl(context: Context, url: String) {
        var finalUrl = url
        if (finalUrl.startsWith("@")) {
            try {
                val resId = finalUrl.substring(1).toInt()
                finalUrl = context.getString(resId)
            } catch (ignored: Exception) {
            }
        }
        
        try {
            val i = Intent(Intent.ACTION_VIEW)
            i.data = Uri.parse(finalUrl)

            if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) {
                context.startActivity(i)
                return
            }
        } catch (e: Exception) {
            // Fall through
        }

        val i = Intent(context, HelpActivity::class.java)
        i.data = Uri.parse(finalUrl)
        context.startActivity(i)
    }

    @JvmStatic
    fun launchSetupGuide(context: Context) {
        launchUrl(context, "https://github.com/moonlight-stream/moonlight-docs/wiki/Setup-Guide")
    }

    @JvmStatic
    fun launchTroubleshooting(context: Context) {
        launchUrl(context, "https://github.com/moonlight-stream/moonlight-docs/wiki/Troubleshooting")
    }

    @JvmStatic
    fun launchGameStreamEolFaq(context: Context) {
        launchUrl(context, "https://github.com/moonlight-stream/moonlight-docs/wiki/NVIDIA-GameStream-End-Of-Service-Announcement-FAQ")
    }
}
