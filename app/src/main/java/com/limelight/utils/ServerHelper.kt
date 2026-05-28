package com.limelight.utils

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import android.widget.Toast
import androidx.annotation.RequiresApi
import com.limelight.AppView
import com.limelight.Game
import com.limelight.LimeLog
import com.limelight.R
import com.limelight.ShortcutTrampoline
import com.limelight.binding.PlatformBinding
import com.limelight.computers.ComputerManagerService
import com.limelight.nvstream.http.ComputerDetails
import com.limelight.nvstream.http.HostHttpResponseException
import com.limelight.nvstream.http.NvApp
import com.limelight.nvstream.http.NvHTTP
import com.limelight.nvstream.jni.MoonBridge
import com.limelight.preferences.PreferenceConfiguration
import org.xmlpull.v1.XmlPullParserException
import java.io.FileNotFoundException
import java.io.IOException
import java.net.UnknownHostException
import java.security.cert.CertificateEncodingException
import java.util.ArrayList

object ServerHelper {
    const val CONNECTION_TEST_SERVER = "android.conntest.moonlight-stream.org"

    @JvmStatic
    @Throws(IOException::class)
    fun getCurrentAddressFromComputer(computer: ComputerDetails): ComputerDetails.AddressTuple {
        return computer.activeAddress ?: throw IOException("No active address for " + computer.name)
    }

    @JvmStatic
    fun createPcShortcutIntent(parent: Activity, computer: ComputerDetails): Intent {
        val i = Intent(parent, ShortcutTrampoline::class.java)
        i.putExtra(AppView.NAME_EXTRA, computer.name)
        i.putExtra(AppView.UUID_EXTRA, computer.uuid)
        i.action = Intent.ACTION_DEFAULT
        return i
    }

    @JvmStatic
    fun createAppShortcutIntent(parent: Activity, computer: ComputerDetails, app: NvApp): Intent {
        val i = Intent(parent, ShortcutTrampoline::class.java)
        i.putExtra(AppView.NAME_EXTRA, computer.name)
        i.putExtra(AppView.UUID_EXTRA, computer.uuid)
        i.putExtra(Game.EXTRA_APP_NAME, app.appName)
        i.putExtra(Game.EXTRA_APP_UUID, app.appUUID)
        i.putExtra(Game.EXTRA_APP_ID, "" + app.appId)
        i.putExtra(Game.EXTRA_APP_HDR, app.isHdrSupported)
        i.action = Intent.ACTION_DEFAULT
        return i
    }

    @JvmStatic
    fun getActiveDisplay(context: Context, prefs: PreferenceConfiguration): Display {
        val secondary = getSecondaryDisplay(context)
        return if (secondary != null && prefs.enableFullExDisplay) {
            secondary
        } else {
            (context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
                .getDisplay(Display.DEFAULT_DISPLAY)
        }
    }

    @JvmStatic
    fun getSecondaryDisplay(context: Context): Display? {
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        var display: Display? = null
        val displays = displayManager.displays
        val mainDisplayId = Display.DEFAULT_DISPLAY
        var secondaryDisplayId = -1
        for (displayVariant in displays) {
            LimeLog.info(displayVariant.toString())
            if (displayVariant.displayId != mainDisplayId) {
                secondaryDisplayId = displayVariant.displayId
                break
            }
        }

        if (secondaryDisplayId != -1) {
            display = displayManager.getDisplay(secondaryDisplayId)
        }
        return display
    }

    @JvmStatic
    fun createStartIntent(
        parent: Activity, app: NvApp, computer: ComputerDetails,
        managerBinder: ComputerManagerService.ComputerManagerBinder,
        withVDisplay: Boolean
    ): Intent {
        var gameIntent: Intent? = null
        val prefConfig = PreferenceConfiguration.readPreferences(parent)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && prefConfig.enableFullExDisplay && getSecondaryDisplay(parent) != null) {
            val displayContext = parent.createDisplayContext(getSecondaryDisplay(parent)!!)
            gameIntent = Intent(displayContext, Game::class.java)
            gameIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (gameIntent == null) gameIntent = Intent(parent, Game::class.java)
        gameIntent.putExtra(Game.EXTRA_HOST, computer.activeAddress.address)
        gameIntent.putExtra(Game.EXTRA_PORT, computer.activeAddress.port)
        gameIntent.putExtra(Game.EXTRA_HTTPS_PORT, computer.httpsPort)
        gameIntent.putExtra(Game.EXTRA_APP_NAME, app.appName)
        gameIntent.putExtra(Game.EXTRA_APP_UUID, app.appUUID)
        gameIntent.putExtra(Game.EXTRA_APP_ID, app.appId)
        gameIntent.putExtra(Game.EXTRA_APP_HDR, app.isHdrSupported)
        gameIntent.putExtra(Game.EXTRA_UNIQUEID, managerBinder.uniqueId)
        gameIntent.putExtra(Game.EXTRA_PC_UUID, computer.uuid)
        gameIntent.putExtra(Game.EXTRA_PC_NAME, computer.name)
        gameIntent.putExtra(Game.EXTRA_VDISPLAY, withVDisplay)
        gameIntent.putExtra(Game.EXTRA_SERVER_COMMANDS, computer.serverCommands as ArrayList<String>)

        try {
            if (computer.serverCert != null) {
                gameIntent.putExtra(Game.EXTRA_SERVER_CERT, computer.serverCert.encoded)
            }
        } catch (e: CertificateEncodingException) {
            e.printStackTrace()
        }

        if (prefConfig.enableFullExDisplay) {
            val secondaryDisplay = getSecondaryDisplay(parent)
            if (secondaryDisplay != null) {
                val secondaryDisplayId = secondaryDisplay.displayId
                gameIntent.putExtra(Game.EXTRA_DISPLAY_ID, secondaryDisplayId)
                val touchpadIntent = Intent(parent, ExternalDisplayControlActivity::class.java)
                touchpadIntent.putExtra(ExternalDisplayControlActivity.EXTRA_LAUNCH_INTENT, gameIntent)
                return touchpadIntent
            }
        }

        return gameIntent
    }

    @JvmStatic
    @RequiresApi(api = Build.VERSION_CODES.O)
    fun doStart(
        parent: Activity,
        app: NvApp,
        computer: ComputerDetails,
        managerBinder: ComputerManagerService.ComputerManagerBinder,
        withVDisplay: Boolean
    ) {
        if (computer.state == ComputerDetails.State.OFFLINE || computer.activeAddress == null) {
            Toast.makeText(parent, parent.getString(R.string.pair_pc_offline), Toast.LENGTH_SHORT).show()
            return
        }

        val intent = createStartIntent(parent, app, computer, managerBinder, withVDisplay)
        parent.startActivity(intent)
    }

    @JvmStatic
    @RequiresApi(api = Build.VERSION_CODES.O)
    fun doStartPairControllers(
        parent: Activity,
        app: NvApp,
        computer: ComputerDetails,
        managerBinder: ComputerManagerService.ComputerManagerBinder
    ) {
        if (computer.state == ComputerDetails.State.OFFLINE || computer.activeAddress == null) {
            Toast.makeText(parent, parent.getString(R.string.pair_pc_offline), Toast.LENGTH_SHORT).show()
            return
        }

        val intent = createStartIntent(parent, app, computer, managerBinder, false)
        intent.putExtra(Game.EXTRA_PAIR_CONTROLLERS, true)
        parent.startActivity(intent)
    }

    @JvmStatic
    fun doNetworkTest(parent: Activity) {
        Thread {
            val spinnerDialog = SpinnerDialog.displayDialog(
                parent,
                parent.resources.getString(R.string.nettest_title_waiting),
                parent.resources.getString(R.string.nettest_text_waiting),
                false
            )

            val ret = MoonBridge.testClientConnectivity(CONNECTION_TEST_SERVER, 443, MoonBridge.ML_PORT_FLAG_ALL)
            spinnerDialog.dismiss()

            var dialogSummary = if (ret == MoonBridge.ML_TEST_RESULT_INCONCLUSIVE) {
                parent.resources.getString(R.string.nettest_text_inconclusive)
            } else if (ret == 0) {
                parent.resources.getString(R.string.nettest_text_success)
            } else {
                parent.resources.getString(R.string.nettest_text_failure) + MoonBridge.stringifyPortFlags(ret, "\n")
            }

            Dialog.displayDialog(
                parent,
                parent.resources.getString(R.string.nettest_title_done),
                dialogSummary,
                false
            )
        }.start()
    }

    @JvmStatic
    fun doQuit(
        parent: Activity,
        httpConn: NvHTTP,
        appName: String,
        onComplete: Runnable?,
        onFail: Runnable?
    ) {
        parent.runOnUiThread {
            Toast.makeText(parent, parent.resources.getString(R.string.applist_quit_app) + " " + appName + "...", Toast.LENGTH_SHORT).show()
        }
        Thread {
            var message: String
            var failed = false
            try {
                if (httpConn.quitApp()) {
                    message = parent.resources.getString(R.string.applist_quit_success) + " " + appName
                } else {
                    message = parent.resources.getString(R.string.applist_quit_fail) + " " + appName
                }
            } catch (e: HostHttpResponseException) {
                failed = true
                if (e.errorCode == 599) {
                    message = "This session wasn't started by this device, so it cannot be quit. End streaming on the original device or the PC itself. (Error code: " + e.errorCode + ")"
                } else {
                    message = e.message ?: ""
                }
            } catch (e: UnknownHostException) {
                failed = true
                message = parent.resources.getString(R.string.error_unknown_host)
            } catch (e: FileNotFoundException) {
                failed = true
                message = parent.resources.getString(R.string.error_404)
            } catch (e: IOException) {
                failed = true
                message = e.message ?: ""
                e.printStackTrace()
            } catch (e: XmlPullParserException) {
                failed = true
                message = e.message ?: ""
                e.printStackTrace()
            } finally {
                if (failed) {
                    onFail?.run()
                } else {
                    onComplete?.run()
                }
            }

            val toastMessage = message
            parent.runOnUiThread { Toast.makeText(parent, toastMessage, Toast.LENGTH_LONG).show() }
        }.start()
    }

    @JvmStatic
    fun doQuit(
        parent: Activity,
        computer: ComputerDetails,
        app: NvApp,
        managerBinder: ComputerManagerService.ComputerManagerBinder,
        onComplete: Runnable?
    ) {
        try {
            val httpConn = NvHTTP(
                getCurrentAddressFromComputer(computer),
                computer.httpsPort,
                managerBinder.uniqueId,
                computer.serverCert,
                PlatformBinding.getCryptoProvider(parent)
            )
            doQuit(parent, httpConn, app.appName, onComplete, null)
        } catch (e: Exception) {
            e.printStackTrace()
            val toastMessage = e.message ?: ""
            parent.runOnUiThread { Toast.makeText(parent, toastMessage, Toast.LENGTH_LONG).show() }
        }
    }
}
