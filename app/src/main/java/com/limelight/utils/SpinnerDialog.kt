package com.limelight.utils

import android.app.Activity
import android.app.ProgressDialog
import android.content.DialogInterface
import java.util.ArrayList

@Suppress("DEPRECATION")
class SpinnerDialog private constructor(
    private val activity: Activity,
    private val title: String,
    private val message: String,
    private val finish: Boolean
) : Runnable, DialogInterface.OnCancelListener {
    private var progress: ProgressDialog? = null

    companion object {
        private val rundownDialogs = ArrayList<SpinnerDialog>()

        @JvmStatic
        fun displayDialog(activity: Activity, title: String, message: String, finish: Boolean): SpinnerDialog {
            val spinner = SpinnerDialog(activity, title, message, finish)
            activity.runOnUiThread(spinner)
            return spinner
        }

        @JvmStatic
        fun closeDialogs(activity: Activity) {
            synchronized(rundownDialogs) {
                val i = rundownDialogs.iterator()
                while (i.hasNext()) {
                    val dialog = i.next()
                    if (dialog.activity === activity) {
                        i.remove()
                        if (dialog.progress?.isShowing == true) {
                            dialog.progress?.dismiss()
                        }
                    }
                }
            }
        }
    }

    fun dismiss() {
        activity.runOnUiThread(this)
    }

    fun setMessage(message: String) {
        activity.runOnUiThread { progress?.setMessage(message) }
    }

    override fun run() {
        if (activity.isFinishing) {
            return
        }

        if (progress == null) {
            progress = ProgressDialog(activity).apply {
                setTitle(this@SpinnerDialog.title)
                setMessage(this@SpinnerDialog.message)
                setProgressStyle(ProgressDialog.STYLE_SPINNER)
                setOnCancelListener(this@SpinnerDialog)

                if (finish) {
                    setCancelable(true)
                    setCanceledOnTouchOutside(false)
                } else {
                    setCancelable(false)
                }
            }

            synchronized(rundownDialogs) {
                rundownDialogs.add(this)
                progress?.show()
            }
        } else {
            synchronized(rundownDialogs) {
                if (rundownDialogs.remove(this) && progress?.isShowing == true) {
                    progress?.dismiss()
                }
            }
        }
    }

    override fun onCancel(dialog: DialogInterface) {
        synchronized(rundownDialogs) {
            rundownDialogs.remove(this)
        }
        if (finish) {
            activity.finish()
        }
    }
}
