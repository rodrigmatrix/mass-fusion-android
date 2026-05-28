package com.limelight.utils

import android.app.Activity
import android.app.AlertDialog
import android.app.GameManager
import android.app.GameState
import android.app.LocaleManager
import android.app.UiModeManager
import android.content.Context
import android.content.DialogInterface
import android.content.res.Configuration
import android.os.Build
import android.text.Html
import android.text.method.LinkMovementMethod
import android.util.TypedValue
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.TextView
import com.limelight.R
import com.limelight.nvstream.http.ComputerDetails
import com.limelight.preferences.PreferenceConfiguration
import java.util.Locale

object UiHelper {

    private const val TV_VERTICAL_PADDING_DP = 15
    private const val TV_HORIZONTAL_PADDING_DP = 15

    private fun setGameModeStatus(context: Context, streaming: Boolean, interruptible: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val gameManager = context.getSystemService(GameManager::class.java)

            if (gameManager == null) {
                com.limelight.LimeLog.warning("GameManager is null, maybe your system does not support it?")
                return
            }

            if (streaming) {
                gameManager.setGameState(
                    GameState(
                        false,
                        if (interruptible) GameState.MODE_GAMEPLAY_INTERRUPTIBLE else GameState.MODE_GAMEPLAY_UNINTERRUPTIBLE
                    )
                )
            } else {
                gameManager.setGameState(GameState(false, GameState.MODE_NONE))
            }
        }
    }

    @JvmStatic
    fun notifyStreamConnecting(context: Context) {
        setGameModeStatus(context, true, true)
    }

    @JvmStatic
    fun notifyStreamConnected(context: Context) {
        setGameModeStatus(context, true, false)
    }

    @JvmStatic
    fun notifyStreamEnteringPiP(context: Context) {
        setGameModeStatus(context, true, true)
    }

    @JvmStatic
    fun notifyStreamExitingPiP(context: Context) {
        setGameModeStatus(context, true, false)
    }

    @JvmStatic
    fun notifyStreamEnded(context: Context) {
        setGameModeStatus(context, false, false)
    }

    @JvmStatic
    fun setLocale(activity: Activity) {
        val localeStr = PreferenceConfiguration.readPreferences(activity).language
        val config = Configuration(activity.resources.configuration)
        if (localeStr == PreferenceConfiguration.DEFAULT_LANGUAGE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // On Android 13, migrate this non-default language setting into the OS native API
                val localeManager = activity.getSystemService(LocaleManager::class.java)
                val systemLocales = localeManager.systemLocales
                if (!systemLocales.isEmpty) {
                    config.setLocale(systemLocales.get(0))
                }
            }
        } else {
            // We're handling some nasty non-standard devices which cannot set locale using system config correctly
            // Some locales include both language and country which must be separated
            // before calling the Locale constructor.
            if (localeStr.contains("-")) {
                config.setLocale(
                    Locale(
                        localeStr.substring(0, localeStr.indexOf('-')),
                        localeStr.substring(localeStr.indexOf('-') + 1)
                    )
                )
            } else {
                config.setLocale(Locale(localeStr))
            }
        }

        @Suppress("DEPRECATION")
        activity.resources.updateConfiguration(config, activity.resources.displayMetrics)
    }

    @JvmStatic
    fun applyStatusBarPadding(view: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // This applies the padding that we omitted in notifyNewRootView() on Q
            view.setOnApplyWindowInsetsListener { v, windowInsets ->
                v.setPadding(
                    v.paddingLeft,
                    v.paddingTop,
                    v.paddingRight,
                    windowInsets.tappableElementInsets.bottom
                )
                windowInsets
            }
            view.requestApplyInsets()
        }
    }

    @JvmStatic
    fun notifyNewRootView(activity: Activity) {
        val rootView = activity.findViewById<View>(android.R.id.content)
        val modeMgr = activity.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager

        // Set GameState.MODE_NONE initially for all activities
        setGameModeStatus(activity, false, false)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // Allow this non-streaming activity to layout under notches.
            //
            // We should NOT do this for the Game activity unless
            // the user specifically opts in, because it can obscure
            // parts of the streaming surface.
            activity.window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        if (modeMgr.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) {
            // Increase view padding on TVs
            val scale = activity.resources.displayMetrics.density
            val verticalPaddingPixels = (TV_VERTICAL_PADDING_DP * scale + 0.5f).toInt()
            val horizontalPaddingPixels = (TV_HORIZONTAL_PADDING_DP * scale + 0.5f).toInt()

            rootView.setPadding(
                horizontalPaddingPixels, verticalPaddingPixels,
                horizontalPaddingPixels, verticalPaddingPixels
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Draw under the status bar on Android Q devices

            // Using getDecorView() here breaks the translucent status/navigation bar when gestures are disabled
            activity.findViewById<View>(android.R.id.content).setOnApplyWindowInsetsListener { v, windowInsets ->
                // Use the tappable insets so we can draw under the status bar in gesture mode
                val tappableInsets = windowInsets.tappableElementInsets
                v.setPadding(
                    tappableInsets.left,
                    tappableInsets.top,
                    tappableInsets.right,
                    tappableInsets.bottom
                )

                // Show a translucent navigation bar if we can't tap there
                if (tappableInsets.bottom != 0) {
                    activity.window.addFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
                } else {
                    activity.window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
                }

                windowInsets
            }

            @Suppress("DEPRECATION")
            activity.window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
    }

    @JvmStatic
    fun showDecoderCrashDialog(activity: Activity) {
        val prefs = activity.getSharedPreferences("DecoderTombstone", 0)
        val crashCount = prefs.getInt("CrashCount", 0)
        val lastNotifiedCrashCount = prefs.getInt("LastNotifiedCrashCount", 0)

        // Remember the last crash count we notified at, so we don't
        // display the crash dialog every time the app is started until
        // they stream again
        if (crashCount != 0 && crashCount != lastNotifiedCrashCount) {
            if (crashCount % 3 == 0) {
                // At 3 consecutive crashes, we'll forcefully reset their settings
                PreferenceConfiguration.resetStreamingSettings(activity)
                Dialog.displayDialog(
                    activity,
                    activity.resources.getString(R.string.title_decoding_reset),
                    activity.resources.getString(R.string.message_decoding_reset)
                ) {
                    // Mark notification as acknowledged on dismissal
                    prefs.edit().putInt("LastNotifiedCrashCount", crashCount).apply()
                }
            } else {
                Dialog.displayDialog(
                    activity,
                    activity.resources.getString(R.string.title_decoding_error),
                    activity.resources.getString(R.string.message_decoding_error)
                ) {
                    // Mark notification as acknowledged on dismissal
                    prefs.edit().putInt("LastNotifiedCrashCount", crashCount).apply()
                }
            }
        }
    }

    @JvmStatic
    fun displayConfirmationDialog(
        parent: Activity,
        title: String?,
        message: String?,
        btnYesText: String?,
        btnNoText: String?,
        onYes: Runnable?,
        onNo: Runnable?
    ) {
        val dialogClickListener = DialogInterface.OnClickListener { _, which ->
            when (which) {
                DialogInterface.BUTTON_POSITIVE -> onYes?.run()
                DialogInterface.BUTTON_NEGATIVE -> onNo?.run()
            }
        }

        val builder = AlertDialog.Builder(parent)
        if (message != null) {
            @Suppress("DEPRECATION")
            builder.setMessage(Html.fromHtml(message))
        }
        if (title != null) {
            builder.setTitle(title)
        }
        if (btnYesText != null) {
            builder.setPositiveButton(btnYesText, dialogClickListener)
        }
        if (btnNoText != null) {
            builder.setNegativeButton(btnNoText, dialogClickListener)
        }
        val dialog = builder.create()
        dialog.show()
        dialog.findViewById<TextView>(android.R.id.message)?.movementMethod =
            LinkMovementMethod.getInstance()
    }

    @JvmStatic
    fun displayVdisplayConfirmationDialog(
        parent: Activity,
        computer: ComputerDetails,
        onYes: Runnable?,
        onNo: Runnable?
    ) {
        val message = if (computer.vDisplaySupported) {
            parent.resources.getString(R.string.vdisplay_not_ready)
        } else {
            parent.resources.getString(R.string.vdisplay_not_supported)
        }
        displayConfirmationDialog(
            parent,
            null,
            message,
            parent.resources.getString(R.string.proceed),
            parent.resources.getString(R.string.cancel),
            onYes,
            onNo
        )
    }

    @JvmStatic
    fun displayQuitConfirmationDialog(parent: Activity, onYes: Runnable?, onNo: Runnable?) {
        displayConfirmationDialog(
            parent,
            null,
            parent.resources.getString(R.string.applist_quit_confirmation),
            parent.resources.getString(R.string.yes),
            parent.resources.getString(R.string.no),
            onYes,
            onNo
        )
    }

    @JvmStatic
    fun displayDeletePcConfirmationDialog(
        parent: Activity,
        computer: ComputerDetails,
        onYes: Runnable?,
        onNo: Runnable?
    ) {
        displayConfirmationDialog(
            parent,
            computer.name,
            parent.resources.getString(R.string.delete_pc_msg),
            parent.resources.getString(R.string.yes),
            parent.resources.getString(R.string.no),
            onYes,
            onNo
        )
    }

    @JvmStatic
    fun dpToPx(context: Context, dp: Float): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            context.resources.displayMetrics
        )
    }
}
