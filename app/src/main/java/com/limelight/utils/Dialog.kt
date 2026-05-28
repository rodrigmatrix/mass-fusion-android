package com.limelight.utils

import android.app.Activity
import android.app.AlertDialog
import android.content.DialogInterface
import com.limelight.R
import java.util.ArrayList

class Dialog private constructor(
    private val activity: Activity,
    private val title: String,
    private val message: String,
    private val runOnDismiss: Runnable
) : Runnable {
    private var alert: AlertDialog? = null

    companion object {
        private val rundownDialogs = ArrayList<Dialog>()

        @JvmStatic
        fun closeDialogs() {
            synchronized(rundownDialogs) {
                for (d in rundownDialogs) {
                    if (d.alert?.isShowing == true) {
                        d.alert?.dismiss()
                    }
                }
                rundownDialogs.clear()
            }
        }

        @JvmStatic
        fun displayDialog(activity: Activity, title: String, message: String, endAfterDismiss: Boolean) {
            activity.runOnUiThread(Dialog(activity, title, message, Runnable {
                if (endAfterDismiss) {
                    activity.finish()
                }
            }))
        }

        @JvmStatic
        fun displayDialog(activity: Activity, title: String, message: String, runOnDismiss: Runnable) {
            activity.runOnUiThread(Dialog(activity, title, message, runOnDismiss))
        }
    }

    override fun run() {
        if (activity.isFinishing) return

        alert = AlertDialog.Builder(activity).create().apply {
            setTitle(this@Dialog.title)
            setMessage(this@Dialog.message)
            setCancelable(false)
            setCanceledOnTouchOutside(false)

            setButton(AlertDialog.BUTTON_POSITIVE, activity.resources.getText(android.R.string.ok)) { _, _ ->
                synchronized(rundownDialogs) {
                    rundownDialogs.remove(this@Dialog)
                    dismiss()
                }
                runOnDismiss.run()
            }

            setButton(AlertDialog.BUTTON_NEUTRAL, activity.resources.getText(R.string.help)) { _, _ ->
                synchronized(rundownDialogs) {
                    rundownDialogs.remove(this@Dialog)
                    dismiss()
                }
                runOnDismiss.run()
                HelpLauncher.launchTroubleshooting(activity)
            }

            setOnShowListener {
                val button = getButton(AlertDialog.BUTTON_POSITIVE)
                button?.isFocusable = true
                button?.isFocusableInTouchMode = true
                button?.requestFocus()
            }
        }

        synchronized(rundownDialogs) {
            rundownDialogs.add(this)
            alert?.show()
        }
    }
}
