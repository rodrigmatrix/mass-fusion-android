package com.limelight

import android.app.ActivityManager
import android.app.AlertDialog
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.preference.PreferenceManager
import com.limelight.binding.PlatformBinding
import com.limelight.binding.crypto.AndroidCryptoProvider
import com.limelight.computers.ComputerManagerListener
import com.limelight.computers.ComputerManagerService
import com.limelight.grid.assets.CachedAppAssetLoader
import com.limelight.grid.assets.DiskAssetLoader
import com.limelight.grid.assets.MemoryAssetLoader
import com.limelight.grid.assets.NetworkAssetLoader
import com.limelight.nvstream.http.ComputerDetails
import com.limelight.nvstream.http.NvApp
import com.limelight.nvstream.http.NvHTTP
import com.limelight.nvstream.http.PairingManager
import com.limelight.nvstream.http.PairingManager.PairState
import com.limelight.nvstream.wol.WakeOnLanSender
import com.limelight.preferences.AddComputerManually
import com.limelight.preferences.GlPreferences
import com.limelight.preferences.PreferenceConfiguration
import com.limelight.preferences.StreamSettings
import com.limelight.profiles.ProfilesManager
import com.limelight.utils.Dialog
import com.limelight.utils.CacheHelper
import com.limelight.utils.HelpLauncher
import com.limelight.LimeLog
import com.limelight.utils.ServerHelper
import com.limelight.utils.ShortcutHelper
import com.limelight.utils.UiHelper
import org.xmlpull.v1.XmlPullParserException
import java.io.FileNotFoundException
import java.io.IOException
import java.io.StringReader
import java.net.UnknownHostException
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import androidx.core.content.edit

class PcView : AppCompatActivity() {

    class ComputerObject(var details: ComputerDetails) {
        fun guessManagementUrl(): String? {
            if (details.activeAddress == null) return null
            return "https://" + details.activeAddress.address + ":" + (details.guessExternalPort() + 1)
        }
    }

    private var managerBinder: ComputerManagerService.ComputerManagerBinder? = null
    private var shortcutHelper: ShortcutHelper? = null
    private var freezeUpdates = false
    private var runningPolling = false
    private var inForeground = false
    private var completeOnCreateCalled = false
    private var pendingPairingAddress: ComputerDetails.AddressTuple? = null
    private var pendingPairingPin: String? = null
    private var pendingPairingPassphrase: String? = null
    private var lastHomeRefreshMs = 0L

    private var computers by mutableStateOf<List<ComputerObject>>(emptyList())
    private var homeApps by mutableStateOf<Map<String, List<AppView.AppObject>>>(emptyMap())
    private var activeProfileName by mutableStateOf("")
    private var prefConfig: PreferenceConfiguration? = null
    private val appPollers = HashMap<String, ComputerManagerService.ApplistPoller>()
    private val appAssetLoaders = HashMap<String, CachedAppAssetLoader>()
    private val lastRawApplists = HashMap<String, String?>()

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, binder: IBinder) {
            val localBinder = binder as ComputerManagerService.ComputerManagerBinder
            Thread {
                localBinder.waitForReady()
                managerBinder = localBinder
                runOnUiThread {
                    startComputerUpdates()
                    refreshHomeDataAfterResume()
                }
                AndroidCryptoProvider(this@PcView).clientCertificate
            }.start()
        }

        override fun onServiceDisconnected(className: ComponentName) {
            managerBinder = null
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (completeOnCreateCalled) {
            activeProfileName = ProfilesManager.getInstance().activeName
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        inForeground = true

        val intent = intent
        val hostname = intent.getStringExtra("hostname")
        val port = intent.getIntExtra("port", NvHTTP.DEFAULT_HTTP_PORT)
        pendingPairingPin = intent.getStringExtra("pin")
        pendingPairingPassphrase = intent.getStringExtra("passphrase")

        if (hostname != null && pendingPairingPin != null && pendingPairingPassphrase != null) {
            pendingPairingAddress = ComputerDetails.AddressTuple(hostname, port)
        } else {
            pendingPairingPin = null
            pendingPairingPassphrase = null
        }

        val glPrefs = GlPreferences.readPreferences(this)
        if (glPrefs.savedFingerprint != Build.FINGERPRINT || glPrefs.glRenderer.isEmpty()) {
            val surfaceView = GLSurfaceView(this)
            surfaceView.setRenderer(object : GLSurfaceView.Renderer {
                override fun onSurfaceCreated(gl10: GL10, eglConfig: EGLConfig) {
                    glPrefs.glRenderer = gl10.glGetString(GL10.GL_RENDERER)
                    glPrefs.savedFingerprint = Build.FINGERPRINT
                    glPrefs.writePreferences()
                    LimeLog.info("Fetched GL Renderer: " + glPrefs.glRenderer)
                    runOnUiThread { completeOnCreate() }
                }

                override fun onSurfaceChanged(gl10: GL10, i: Int, i1: Int) {}
                override fun onDrawFrame(gl10: GL10) {}
            })
            setContentView(surfaceView)
        } else {
            LimeLog.info("Cached GL Renderer: " + glPrefs.glRenderer)
            completeOnCreate()
        }
    }

    private fun completeOnCreate() {
        completeOnCreateCalled = true
        shortcutHelper = ShortcutHelper(this)
        UiHelper.setLocale(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setShouldDockBigOverlays(false)
        }

        PreferenceManager.setDefaultValues(this, R.xml.preferences, false)

        bindService(Intent(this, ComputerManagerService::class.java), serviceConnection, Service.BIND_AUTO_CREATE)
        prefConfig = PreferenceConfiguration.readPreferences(this)

        setContent {
            com.limelight.ui.theme.MassFusionTheme {
                PcScreen(
                    computers = computers,
                    appsByComputer = homeApps,
                    assetLoaders = appAssetLoaders,
                    activeProfileName = activeProfileName,
                    onProfilesClick = { startActivity(Intent(this, ProfilesActivity::class.java)) },
                    onSettingsClick = { startActivity(Intent(this, StreamSettings::class.java)) },
                    onControllerClick = {
                        startActivity(Intent().setComponent(ComponentName(this, "com.switch2.controllers.ui.Switch2ControllersActivity")))
                    },
                    onAddPcClick = { startActivity(Intent(this, AddComputerManually::class.java)) },
                    onScanClick = { managerBinder?.triggerDiscovery() },
                    onHelpClick = { HelpLauncher.launchSetupGuide(this) },
                    onPcClick = { pc -> handlePcClick(pc) },
                    onPcAction = { pc, action -> handlePcAction(pc, action) },
                    onAppClick = { pc, app, showMenu -> handleHomeAppClick(pc, app, showMenu) },
                    onAppAction = { pc, app, action -> handleHomeAppAction(pc, app, action) }
                )
            }
        }
    }

    private fun startComputerUpdates() {
        if (managerBinder != null && !runningPolling && inForeground) {
            freezeUpdates = false
            managerBinder?.startPolling(object : ComputerManagerListener {
                override fun notifyComputerUpdated(details: ComputerDetails) {
                    if (!freezeUpdates) {
                        runOnUiThread { updateComputer(details) }
                        if (details.pairState == PairState.PAIRED) {
                            shortcutHelper?.createAppViewShortcutForOnlineHost(details)
                        } else if (pendingPairingAddress != null) {
                            if (details.state == ComputerDetails.State.ONLINE && details.activeAddress == pendingPairingAddress) {
                                runOnUiThread {
                                    doPair(details, pendingPairingPin, pendingPairingPassphrase)
                                    pendingPairingAddress = null
                                    pendingPairingPin = null
                                    pendingPairingPassphrase = null
                                }
                            }
                        }
                    }
                }
            })
            runningPolling = true
        }
    }

    private fun stopComputerUpdates(wait: Boolean) {
        if (managerBinder != null) {
            if (!runningPolling) return
            freezeUpdates = true
            managerBinder?.stopPolling()
            if (wait) managerBinder?.waitForPollingStopped()
            runningPolling = false
        }
        stopAppPollers()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAppPollers()
        appAssetLoaders.values.forEach {
            it.cancelForegroundLoads()
            it.cancelBackgroundLoads()
            it.freeCacheMemory()
        }
        if (managerBinder != null) unbindService(serviceConnection)
    }

    override fun onStart() {
        super.onStart()
        inForeground = true
        startComputerUpdates()
    }

    override fun onStop() {
        super.onStop()
        inForeground = false
        stopComputerUpdates(false)
        Dialog.closeDialogs()
    }

    override fun onResume() {
        super.onResume()
        UiHelper.showDecoderCrashDialog(this)
        activeProfileName = ProfilesManager.getInstance().activeName
        refreshHomeDataAfterResume()
    }

    override fun onPause() {
        super.onPause()
    }

    enum class PcAction {
        WOL, EOL, PAIR_OTP, PAIR, OPEN_MANAGEMENT, RESUME, QUIT, APP_LIST, TEST_NETWORK, DELETE, DETAILS, EDIT
    }

    private fun handlePcClick(pc: ComputerObject) {
        if (pc.details.state == ComputerDetails.State.UNKNOWN || pc.details.state == ComputerDetails.State.OFFLINE) {
            // Usually opens context menu, Compose handles this via dropdown in long click, but if clicked we might want to do nothing or open details
            Toast.makeText(this, "PC is offline or unknown state", Toast.LENGTH_SHORT).show()
        } else if (pc.details.pairState != PairState.PAIRED) {
            doPair(pc.details, null, null)
        } else {
            doAppList(pc.details, false, false)
        }
    }

    private fun handlePcAction(pc: ComputerObject, action: PcAction) {
        when (action) {
            PcAction.PAIR -> doPair(pc.details, null, null)
            PcAction.PAIR_OTP -> doOTPPair(pc.details)
            PcAction.WOL -> doWakeOnLan(pc.details)
            PcAction.EDIT -> {
                val editIntent = Intent(this, AddComputerManually::class.java).apply {
                    putExtra("edit_uuid", pc.details.uuid)
                    putExtra("edit_name", pc.details.name)
                    putExtra("edit_local", pc.details.manualAddress?.address ?: pc.details.localAddress?.address)
                    putExtra("edit_tailscale", pc.details.tailscaleAddress?.address)
                }
                startActivity(editIntent)
            }
            PcAction.DELETE -> {
                if (!ActivityManager.isUserAMonkey()) {
                    UiHelper.displayDeletePcConfirmationDialog(this, pc.details, {
                        if (managerBinder == null) {
                            Toast.makeText(this, R.string.error_manager_not_running, Toast.LENGTH_LONG).show()
                        } else {
                            removeComputer(pc.details)
                        }
                    }, null)
                }
            }
            PcAction.APP_LIST -> doAppList(pc.details, false, true)
            PcAction.RESUME -> {
                if (managerBinder == null) {
                    Toast.makeText(this, R.string.error_manager_not_running, Toast.LENGTH_LONG).show()
                } else {
                    ServerHelper.doStart(this, NvApp("app", null, pc.details.runningGameId, false), pc.details, managerBinder!!, false)
                }
            }
            PcAction.QUIT -> {
                if (managerBinder == null) {
                    Toast.makeText(this, R.string.error_manager_not_running, Toast.LENGTH_LONG).show()
                } else {
                    ServerHelper.doQuit(this, pc.details, NvApp("app", null, 0, false), managerBinder!!, null)
                }
            }
            PcAction.DETAILS -> Dialog.displayDialog(this, getString(R.string.title_details), pc.details.toString(), false)
            PcAction.TEST_NETWORK -> ServerHelper.doNetworkTest(this)
            PcAction.EOL -> HelpLauncher.launchGameStreamEolFaq(this)
            PcAction.OPEN_MANAGEMENT -> {
                val url = pc.guessManagementUrl()
                if (url == null) {
                    Toast.makeText(this, R.string.pcview_error_no_management_url, Toast.LENGTH_LONG).show()
                } else {
                    HelpLauncher.launchUrl(this, url)
                }
            }
        }
    }

    private fun handleHomeAppClick(pc: ComputerObject, app: AppView.AppObject, showMenuCallback: () -> Unit) {
        val binder = managerBinder ?: run {
            Toast.makeText(this, R.string.error_manager_not_running, Toast.LENGTH_LONG).show()
            return
        }
        val prefs = prefConfig ?: PreferenceConfiguration.readPreferences(this).also { prefConfig = it }
        if (pc.details.runningGameId != 0) {
            if (pc.details.runningGameId == app.app.appId) {
                ServerHelper.doStart(this, app.app, pc.details, binder, prefs.useVirtualDisplay)
            } else {
                showMenuCallback()
            }
        } else if (prefs.useVirtualDisplay && pc.details.vDisplaySupported && !pc.details.vDisplayDriverReady) {
            UiHelper.displayVdisplayConfirmationDialog(this, pc.details, {
                ServerHelper.doStart(this, app.app, pc.details, binder, true)
            }, null)
        } else {
            ServerHelper.doStart(this, app.app, pc.details, binder, prefs.useVirtualDisplay)
        }
    }

    private fun handleHomeAppAction(pc: ComputerObject, app: AppView.AppObject, action: AppView.AppAction) {
        val binder = managerBinder ?: run {
            Toast.makeText(this, R.string.error_manager_not_running, Toast.LENGTH_LONG).show()
            return
        }
        when (action) {
            AppView.AppAction.START_QUIT, AppView.AppAction.START_QUIT_VDISPLAY -> {
                val withVDisplay = action == AppView.AppAction.START_QUIT_VDISPLAY
                if (withVDisplay && pc.details.vDisplaySupported && !pc.details.vDisplayDriverReady) {
                    UiHelper.displayVdisplayConfirmationDialog(this, pc.details, {
                        ServerHelper.doStart(this@PcView, app.app, pc.details, binder, true)
                    }, null)
                } else {
                    ServerHelper.doStart(this@PcView, app.app, pc.details, binder, withVDisplay)
                }
            }
            AppView.AppAction.START_RESUME, AppView.AppAction.START_VDISPLAY -> {
                val withVDisplay = action == AppView.AppAction.START_VDISPLAY
                if (withVDisplay && pc.details.vDisplaySupported && !pc.details.vDisplayDriverReady) {
                    UiHelper.displayVdisplayConfirmationDialog(this, pc.details, {
                        ServerHelper.doStart(this@PcView, app.app, pc.details, binder, true)
                    }, null)
                } else {
                    ServerHelper.doStart(this, app.app, pc.details, binder, withVDisplay)
                }
            }
            AppView.AppAction.PAIR_CONTROLLERS -> ServerHelper.doStartPairControllers(this, app.app, pc.details, binder)
            AppView.AppAction.QUIT -> {
                ServerHelper.doQuit(this, pc.details, app.app, binder) {
                    appPollers[pc.details.uuid]?.pollNow()
                }
            }
            AppView.AppAction.VIEW_DETAILS -> Dialog.displayDialog(this, getString(R.string.title_details), app.app.toString(), false)
            AppView.AppAction.TOGGLE_HIDE -> {
                val prefs = getSharedPreferences(AppView.HIDDEN_APPS_PREF_FILENAME, MODE_PRIVATE)
                val hiddenIds = prefs.getStringSet(pc.details.uuid, emptySet()).orEmpty().toMutableSet()
                if (app.isHidden) hiddenIds.remove(app.app.appId.toString()) else hiddenIds.add(app.app.appId.toString())
                prefs.edit().putStringSet(pc.details.uuid, hiddenIds).apply()
                homeApps = homeApps + (pc.details.uuid to homeApps[pc.details.uuid].orEmpty().filter { it.app.appId != app.app.appId })
            }
            AppView.AppAction.EXPORT_LAUNCHER -> {
                val helper = shortcutHelper ?: return
                if (app.app.appUUID.isNullOrEmpty()) {
                    UiHelper.displayConfirmationDialog(
                        this,
                        getString(R.string.title_export_sunshine_launcher_file),
                        getString(R.string.message_export_sunshine_launcher_file),
                        getString(R.string.proceed),
                        getString(R.string.cancel),
                        { helper.exportLauncherFile(pc.details, app.app) },
                        null,
                    )
                } else {
                    helper.exportLauncherFile(pc.details, app.app)
                }
            }
        }
    }

    private fun doPair(computer: ComputerDetails, otp: String?, passphrase: String?) {
        if (computer.state == ComputerDetails.State.OFFLINE || computer.activeAddress == null) {
            Toast.makeText(this, R.string.pair_pc_offline, Toast.LENGTH_SHORT).show()
            return
        }
        if (managerBinder == null) {
            Toast.makeText(this, R.string.error_manager_not_running, Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, R.string.pairing, Toast.LENGTH_SHORT).show()
        Thread {
            var message: String? = null
            var success = false
            try {
                val binder = managerBinder ?: return@Thread
                val httpConn = NvHTTP(ServerHelper.getCurrentAddressFromComputer(computer),
                    computer.httpsPort, binder.uniqueId, computer.serverCert,
                    PlatformBinding.getCryptoProvider(this))
                
                if (httpConn.pairState == PairState.PAIRED) {
                    message = null
                    success = true
                } else {
                    val pinStr = otp ?: PairingManager.generatePinString()
                    if (passphrase == null) {
                        Dialog.displayDialog(this, getString(R.string.pair_pairing_title),
                            getString(R.string.pair_pairing_msg) + " " + pinStr + "\n\n" + getString(R.string.pair_pairing_help), false)
                    } else {
                        Dialog.displayDialog(this, getString(R.string.pair_pairing_title),
                            getString(R.string.pair_otp_pairing_msg) + "\n\n" + getString(R.string.pair_otp_pairing_help), false)
                    }
                    val pm = httpConn.pairingManager
                    val pairState = pm.pair(httpConn.getServerInfo(true), pinStr, passphrase)
                    if (pairState == PairState.PIN_WRONG) {
                        message = getString(R.string.pair_incorrect_pin)
                    } else if (pairState == PairState.FAILED) {
                        message = if (computer.runningGameId != 0) getString(R.string.pair_pc_ingame) else getString(R.string.pair_fail)
                    } else if (pairState == PairState.ALREADY_IN_PROGRESS) {
                        message = getString(R.string.pair_already_in_progress)
                    } else if (pairState == PairState.PAIRED) {
                        message = null
                        success = true
                        binder.getComputer(computer.uuid)?.serverCert = pm.pairedCert
                        binder.invalidateStateForComputer(computer.uuid)
                    }
                }
            } catch (e: UnknownHostException) {
                message = getString(R.string.error_unknown_host)
            } catch (e: FileNotFoundException) {
                message = getString(R.string.error_404)
            } catch (e: Exception) {
                e.printStackTrace()
                message = e.message
            }
            Dialog.closeDialogs()
            runOnUiThread {
                message?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
                if (success) doAppList(computer, true, false)
                else startComputerUpdates()
            }
        }.start()
    }

    private fun doOTPPair(computer: ComputerDetails) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 40)
        }
        val otpInput = EditText(this).apply {
            hint = "PIN"
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(4))
        }
        val passphraseInput = EditText(this).apply {
            hint = getString(R.string.pair_passphrase_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        layout.addView(otpInput)
        layout.addView(passphraseInput)
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.pcview_menu_pair_pc_otp)
            .setView(layout)
            .setPositiveButton(R.string.proceed, null)
            .setNegativeButton(R.string.cancel) { d, _ -> d.dismiss() }
            .create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val pin = otpInput.text.toString()
            val passphrase = passphraseInput.text.toString()
            if (pin.length != 4) {
                Toast.makeText(this, R.string.pair_pin_length_msg, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (passphrase.length < 4) {
                Toast.makeText(this, R.string.pair_passphrase_length_msg, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            doPair(computer, pin, passphrase)
            dialog.dismiss()
        }
    }

    private fun doWakeOnLan(computer: ComputerDetails) {
        if (computer.state == ComputerDetails.State.ONLINE) {
            Toast.makeText(this, R.string.wol_pc_online, Toast.LENGTH_SHORT).show()
            return
        }
        if (computer.macAddress == null) {
            Toast.makeText(this, R.string.wol_no_mac, Toast.LENGTH_SHORT).show()
            return
        }
        Thread {
            var message: String
            try {
                WakeOnLanSender.sendWolPacket(computer)
                message = getString(R.string.wol_waking_msg)
            } catch (e: IOException) {
                message = getString(R.string.wol_fail)
            }
            runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
        }.start()
    }

    private fun doAppList(computer: ComputerDetails, newlyPaired: Boolean, showHiddenGames: Boolean) {
        if (computer.state == ComputerDetails.State.OFFLINE) {
            Toast.makeText(this, R.string.error_pc_offline, Toast.LENGTH_SHORT).show()
            return
        }
        if (managerBinder == null) {
            Toast.makeText(this, R.string.error_manager_not_running, Toast.LENGTH_LONG).show()
            return
        }
        val intent = Intent(this, AppView::class.java).apply {
            putExtra(AppView.NAME_EXTRA, computer.name)
            putExtra(AppView.UUID_EXTRA, computer.uuid)
            putExtra(AppView.NEW_PAIR_EXTRA, newlyPaired)
            putExtra(AppView.SHOW_HIDDEN_APPS_EXTRA, showHiddenGames)
        }
        startActivity(intent)
    }

    private fun removeComputer(details: ComputerDetails) {
        managerBinder?.removeComputer(details)
        DiskAssetLoader(this).deleteAssetsForComputer(details.uuid)
        getSharedPreferences(AppView.HIDDEN_APPS_PREF_FILENAME, MODE_PRIVATE).edit().remove(details.uuid).apply()
        stopAppPoller(details.uuid)
        appAssetLoaders.remove(details.uuid)?.let {
            it.cancelForegroundLoads()
            it.cancelBackgroundLoads()
            it.freeCacheMemory()
        }
        homeApps = homeApps - details.uuid
        
        val pcList = computers.toMutableList()
        val toRemove = pcList.find { it.details.uuid == details.uuid }
        if (toRemove != null) {
            shortcutHelper?.disableComputerShortcut(details, getString(R.string.scut_deleted_pc))
            pcList.remove(toRemove)
            computers = pcList
        }
    }

    private fun ensureHomeAppsLoading(details: ComputerDetails) {
        if (!inForeground || managerBinder == null || details.uuid == null) return

        val shouldLoadApps = details.state == ComputerDetails.State.ONLINE &&
            details.pairState == PairState.PAIRED
        if (!shouldLoadApps) {
            stopAppPoller(details.uuid)
            return
        }

        ensureAssetLoader(details)
        populateHomeAppsWithCache(details)

        if (!appPollers.containsKey(details.uuid)) {
            managerBinder?.createAppListPoller(details)?.let { poller ->
                appPollers[details.uuid] = poller
                poller.start()
            }
        }
    }

    private fun refreshHomeDataAfterResume() {
        val binder = managerBinder ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - lastHomeRefreshMs < HOME_RESUME_REFRESH_THROTTLE_MS) {
            return
        }
        lastHomeRefreshMs = now

        LimeLog.info("PcView: refreshing PC and app state after homescreen resume")
        binder.refreshAllComputers()
        appPollers.values.forEach { it.pollNow() }
    }

    private fun ensureAssetLoader(details: ComputerDetails) {
        if (appAssetLoaders.containsKey(details.uuid)) return

        val prefs = prefConfig ?: PreferenceConfiguration.readPreferences(this).also { prefConfig = it }
        val dpi = resources.displayMetrics.densityDpi
        val dp = if (prefs.smallIconMode) 110 else 170
        var scalingDivisor = 300.0 / (dp * (dpi / 160.0))
        if (scalingDivisor < 1.0) scalingDivisor = 1.0

        appAssetLoaders[details.uuid] = CachedAppAssetLoader(
            details,
            scalingDivisor,
            NetworkAssetLoader(this, managerBinder?.uniqueId ?: ""),
            MemoryAssetLoader(),
            DiskAssetLoader(this),
            BitmapFactory.decodeResource(resources, R.drawable.no_app_image),
        )
    }

    private fun populateHomeAppsWithCache(details: ComputerDetails) {
        if (homeApps.containsKey(details.uuid)) return
        try {
            val rawApplist = CacheHelper.readInputStreamToString(
                CacheHelper.openCacheFileForInput(cacheDir, "applist", details.uuid),
            )
            lastRawApplists[details.uuid] = rawApplist
            updateHomeApps(details, NvHTTP.getAppListByReader(StringReader(rawApplist)))
        } catch (_: Exception) {
        }
    }

    private fun stopAppPollers() {
        appPollers.values.forEach { it.stop() }
        appPollers.clear()
    }

    private fun stopAppPoller(uuid: String?) {
        if (uuid == null) return
        appPollers.remove(uuid)?.stop()
    }

    private fun updateHomeApps(details: ComputerDetails, appList: List<NvApp>) {
        val hiddenIds = getSharedPreferences(AppView.HIDDEN_APPS_PREF_FILENAME, MODE_PRIVATE)
            .getStringSet(details.uuid, emptySet())
            .orEmpty()
            .mapNotNull { it.toIntOrNull() }
            .toSet()

        val mappedApps = appList
            .map { app ->
                AppView.AppObject(app).apply {
                    isHidden = hiddenIds.contains(app.appId)
                    isRunning = app.appId == details.runningGameId
                }
            }
            .filter { !it.isHidden }
            .sortedWith(compareBy<AppView.AppObject> { it.app.appIndex }.thenBy { it.app.appName.lowercase() })

        homeApps = homeApps + (details.uuid to mappedApps)
        appAssetLoaders[details.uuid]?.let { loader ->
            mappedApps.forEach { loader.queueCacheLoad(it.app) }
        }
    }

    private fun updateComputer(details: ComputerDetails) {
        details.rawAppList?.let { rawApplist ->
            if (rawApplist != lastRawApplists[details.uuid]) {
                lastRawApplists[details.uuid] = rawApplist
                try {
                    updateHomeApps(details, NvHTTP.getAppListByReader(StringReader(rawApplist)))
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            } else if (details.uuid != null && homeApps.containsKey(details.uuid)) {
                homeApps = homeApps + (details.uuid to homeApps[details.uuid].orEmpty().map { app ->
                    app.apply { isRunning = app.app.appId == details.runningGameId }
                })
            }
        }
        ensureHomeAppsLoading(details)

        val pcList = computers.toMutableList()
        val existing = pcList.find { it.details.uuid == details.uuid }
        if (existing != null) {
            val index = pcList.indexOf(existing)
            pcList[index] = ComputerObject(details)
        } else {
            pcList.add(ComputerObject(details))
        }
        pcList.sortBy { it.details.name.lowercase() }
        computers = pcList
    }

    companion object {
        private const val HOME_RESUME_REFRESH_THROTTLE_MS = 2000L
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PcScreen(
    computers: List<PcView.ComputerObject>,
    appsByComputer: Map<String, List<AppView.AppObject>>,
    assetLoaders: Map<String, CachedAppAssetLoader>,
    activeProfileName: String,
    onProfilesClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onControllerClick: () -> Unit,
    onAddPcClick: () -> Unit,
    onScanClick: () -> Unit,
    onHelpClick: () -> Unit,
    onPcClick: (PcView.ComputerObject) -> Unit,
    onPcAction: (PcView.ComputerObject, PcView.PcAction) -> Unit,
    onAppClick: (PcView.ComputerObject, AppView.AppObject, () -> Unit) -> Unit,
    onAppAction: (PcView.ComputerObject, AppView.AppObject, AppView.AppAction) -> Unit
) {
    val context = LocalContext.current
    val sharedPrefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
    var useTailscale by remember { mutableStateOf(sharedPrefs.getBoolean(PreferenceConfiguration.USE_TAILSCALE_PREF_STRING, false)) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Image(
                        painter = painterResource(R.drawable.ic_mass_fusion),
                        contentDescription = "Mass Fusion Logo",
                        modifier = Modifier.size(44.dp)
                    )
                },
                actions = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Tailscale", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(end = 4.dp))
                        Switch(
                            checked = useTailscale,
                            onCheckedChange = { 
                                useTailscale = it
                                sharedPrefs.edit {
                                    putBoolean(
                                        PreferenceConfiguration.USE_TAILSCALE_PREF_STRING,
                                        it
                                    )
                                }
                            },
                            modifier = Modifier.scale(0.8f)
                        )
                    }
                    IconButton(onClick = onScanClick) {
                        Image(painterResource(android.R.drawable.ic_menu_search), "Scan Network")
                    }
                    IconButton(onClick = onAddPcClick) {
                        Image(painterResource(R.drawable.ic_add), "Add PC")
                    }
                    IconButton(onClick = onControllerClick) {
                        Image(painterResource(R.drawable.ic_controller), "Controllers")
                    }
                    IconButton(onClick = onSettingsClick) {
                        Image(painterResource(R.drawable.ic_settings), "Settings")
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onProfilesClick,
                text = { Text(if (activeProfileName.isEmpty()) "Profiles" else activeProfileName) },
                icon = {}
            )
        }
    ) { paddingValues ->
        if (computers.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(paddingValues), contentAlignment = Alignment.Center) {
                Text("Searching for PCs...", style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            LazyColumn(
                contentPadding = paddingValues,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(computers) { pc ->
                    PcHomeSection(
                        pc = pc,
                        apps = appsByComputer[pc.details.uuid].orEmpty(),
                        assetLoader = assetLoaders[pc.details.uuid],
                        onPcClick = onPcClick,
                        onPcAction = onPcAction,
                        onAppClick = onAppClick,
                        onAppAction = onAppAction
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PcHomeSection(
    pc: PcView.ComputerObject,
    apps: List<AppView.AppObject>,
    assetLoader: CachedAppAssetLoader?,
    onPcClick: (PcView.ComputerObject) -> Unit,
    onPcAction: (PcView.ComputerObject, PcView.PcAction) -> Unit,
    onAppClick: (PcView.ComputerObject, AppView.AppObject, () -> Unit) -> Unit,
    onAppAction: (PcView.ComputerObject, AppView.AppObject, AppView.AppAction) -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    val isOnline = pc.details.state == ComputerDetails.State.ONLINE
    val isOffline = pc.details.state == ComputerDetails.State.OFFLINE
    val isUnknown = pc.details.state == ComputerDetails.State.UNKNOWN

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = { onPcClick(pc) },
                        onLongClick = { showMenu = true }
                    )
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(pc.details.name, style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.width(8.dp))
                        val statusColor = when {
                            isOnline -> MaterialTheme.colorScheme.primary
                            isOffline -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(statusColor, shape = androidx.compose.foundation.shape.CircleShape)
                        )
                    }
                    val subtitle = when {
                        pc.details.state != ComputerDetails.State.ONLINE -> stringResource(R.string.pcview_menu_header_offline)
                        pc.details.pairState != PairState.PAIRED -> stringResource(R.string.scut_not_paired)
                        apps.isEmpty() -> stringResource(R.string.applist_refresh_msg)
                        else -> "${apps.size} apps"
                    }
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                if (isOffline || isUnknown) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.pcview_menu_send_wol)) }, onClick = { showMenu = false; onPcAction(pc, PcView.PcAction.WOL) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.pcview_menu_eol)) }, onClick = { showMenu = false; onPcAction(pc, PcView.PcAction.EOL) })
                } else if (pc.details.pairState != PairingManager.PairState.PAIRED) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.pcview_menu_pair_pc_otp)) }, onClick = { showMenu = false; onPcAction(pc, PcView.PcAction.PAIR_OTP) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.pcview_menu_pair_pc)) }, onClick = { showMenu = false; onPcAction(pc, PcView.PcAction.PAIR) })
                    if (pc.details.nvidiaServer) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.pcview_menu_eol)) }, onClick = { showMenu = false; onPcAction(pc, PcView.PcAction.EOL) })
                    } else {
                        DropdownMenuItem(text = { Text(stringResource(R.string.pcview_menu_open_management_page)) }, onClick = { showMenu = false; onPcAction(pc, PcView.PcAction.OPEN_MANAGEMENT) })
                    }
                } else {
                    if (pc.details.runningGameId != 0) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.applist_menu_resume)) }, onClick = { showMenu = false; onPcAction(pc, PcView.PcAction.RESUME) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.applist_menu_quit)) }, onClick = { showMenu = false; onPcAction(pc, PcView.PcAction.QUIT) })
                    }
                    if (pc.details.nvidiaServer) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.pcview_menu_eol)) }, onClick = { showMenu = false; onPcAction(pc, PcView.PcAction.EOL) })
                    } else {
                        DropdownMenuItem(text = { Text(stringResource(R.string.pcview_menu_open_management_page)) }, onClick = { showMenu = false; onPcAction(pc, PcView.PcAction.OPEN_MANAGEMENT) })
                    }
                    DropdownMenuItem(text = { Text(stringResource(R.string.pcview_menu_app_list)) }, onClick = { showMenu = false; onPcAction(pc, PcView.PcAction.APP_LIST) })
                }
                DropdownMenuItem(text = { Text(stringResource(R.string.pcview_menu_edit_pc)) }, onClick = { showMenu = false; onPcAction(pc, PcView.PcAction.EDIT) })
                DropdownMenuItem(text = { Text(stringResource(R.string.pcview_menu_delete_pc)) }, onClick = { showMenu = false; onPcAction(pc, PcView.PcAction.DELETE) })
                DropdownMenuItem(text = { Text(stringResource(R.string.pcview_menu_details)) }, onClick = { showMenu = false; onPcAction(pc, PcView.PcAction.DETAILS) })
            }
        }

        if (apps.isNotEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val chunkedApps = apps.take(12).chunked(6)
                chunkedApps.forEach { rowApps ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        rowApps.forEach { app ->
                            Box(modifier = Modifier.weight(1f)) {
                                AppGridItem(
                                    appObj = app,
                                    assetLoader = assetLoader,
                                    onClick = { selectedApp, showMenu -> onAppClick(pc, selectedApp, showMenu) },
                                    onAppAction = { selectedApp, action -> onAppAction(pc, selectedApp, action) },
                                    aspectRatio = 1.0f
                                )
                            }
                        }
                        if (rowApps.size < 6) {
                            for (i in 0 until (6 - rowApps.size)) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}
