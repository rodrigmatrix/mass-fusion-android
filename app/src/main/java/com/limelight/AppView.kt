package com.limelight

import android.app.Activity
import android.app.AlertDialog
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
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
import com.limelight.preferences.PreferenceConfiguration
import com.limelight.profiles.ProfilesManager
import com.limelight.utils.*
import org.xmlpull.v1.XmlPullParserException
import java.io.IOException
import java.io.StringReader

class AppView : AppCompatActivity() {

    class AppObject(val app: NvApp) {
        var isRunning: Boolean = false
        var isHidden: Boolean = false

        override fun toString(): String {
            return app.appName
        }
    }

    companion object {
        const val HIDDEN_APPS_PREF_FILENAME = "HiddenApps"
        const val NAME_EXTRA = "Name"
        const val UUID_EXTRA = "UUID"
        const val NEW_PAIR_EXTRA = "NewPair"
        const val SHOW_HIDDEN_APPS_EXTRA = "ShowHiddenApps"
    }

    private var uuidString: String? = null
    private lateinit var shortcutHelper: ShortcutHelper
    private var computer: ComputerDetails? = null
    private var poller: ComputerManagerService.ApplistPoller? = null
    private var blockingLoadSpinner: SpinnerDialog? = null
    private var lastRawApplist: String? = null
    private var lastRunningAppId: Int = 0
    private var suspendGridUpdates = false
    private var inForeground = false
    private var showHiddenApps = false
    private val hiddenAppIds = mutableSetOf<Int>()

    private var prefConfig: PreferenceConfiguration? = null
    private var managerBinder: ComputerManagerService.ComputerManagerBinder? = null

    // Compose State
    private var appsList by mutableStateOf<List<AppObject>>(emptyList())
    private var activeProfileName by mutableStateOf("")

    private var assetLoader: CachedAppAssetLoader? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, binder: IBinder) {
            val localBinder = binder as ComputerManagerService.ComputerManagerBinder
            Thread {
                localBinder.waitForReady()
                computer = localBinder.getComputer(uuidString)
                if (computer == null) {
                    finish()
                    return@Thread
                }
                
                shortcutHelper.createAppViewShortcut(computer!!, true, intent.getBooleanExtra(NEW_PAIR_EXTRA, false))
                shortcutHelper.reportComputerShortcutUsed(computer!!)

                runOnUiThread {
                    managerBinder = localBinder
                    initializeAssetLoader(localBinder.uniqueId)
                    populateAppGridWithCache()
                    startComputerUpdates()
                }
            }.start()
        }

        override fun onServiceDisconnected(className: ComponentName) {
            managerBinder = null
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        prefConfig = PreferenceConfiguration.readPreferences(this)
        managerBinder?.let { initializeAssetLoader(it.uniqueId) }
    }

    private fun initializeAssetLoader(uniqueId: String) {
        prefConfig?.let { prefs ->
            val dpi = resources.displayMetrics.densityDpi
            val dp = if (prefs.smallIconMode) 110 else 170
            var scalingDivisor = 300.0 / (dp * (dpi / 160.0))
            if (scalingDivisor < 1.0) scalingDivisor = 1.0

            assetLoader?.apply {
                cancelForegroundLoads()
                cancelBackgroundLoads()
                freeCacheMemory()
            }
            
            assetLoader = CachedAppAssetLoader(
                computer, scalingDivisor,
                NetworkAssetLoader(this, uniqueId),
                MemoryAssetLoader(),
                DiskAssetLoader(this),
                BitmapFactory.decodeResource(resources, R.drawable.no_app_image)
            )
        }
    }

    private fun startComputerUpdates() {
        if (managerBinder == null || !inForeground) return

        managerBinder?.startPolling(object : ComputerManagerListener {
            override fun notifyComputerUpdated(details: ComputerDetails) {
                if (suspendGridUpdates || !details.uuid.equals(uuidString, ignoreCase = true)) return

                if (details.state == ComputerDetails.State.OFFLINE) {
                    runOnUiThread {
                        Toast.makeText(this@AppView, R.string.lost_connection, Toast.LENGTH_SHORT).show()
                        finish()
                    }
                    return
                }

                if (details.state == ComputerDetails.State.ONLINE && details.pairState != PairingManager.PairState.PAIRED) {
                    runOnUiThread {
                        shortcutHelper.disableComputerShortcut(details, getString(R.string.scut_not_paired))
                        Toast.makeText(this@AppView, R.string.scut_not_paired, Toast.LENGTH_SHORT).show()
                        finish()
                    }
                    return
                }

                if (details.rawAppList == null || details.rawAppList == lastRawApplist) {
                    if (details.runningGameId != lastRunningAppId) {
                        lastRunningAppId = details.runningGameId
                        updateUiWithServerinfo(details)
                    }
                    return
                }

                lastRunningAppId = details.runningGameId
                lastRawApplist = details.rawAppList

                try {
                    val appList = NvHTTP.getAppListByReader(StringReader(details.rawAppList))
                    updateUiWithAppList(appList)
                    updateUiWithServerinfo(details)

                    runOnUiThread {
                        blockingLoadSpinner?.dismiss()
                        blockingLoadSpinner = null
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        })

        if (poller == null) {
            poller = managerBinder?.createAppListPoller(computer)
        }
        poller?.start()
    }

    private fun stopComputerUpdates() {
        poller?.stop()
        managerBinder?.stopPolling()
        assetLoader?.apply {
            cancelForegroundLoads()
            cancelBackgroundLoads()
            freeCacheMemory()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        inForeground = true
        shortcutHelper = ShortcutHelper(this)
        UiHelper.setLocale(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setShouldDockBigOverlays(false)
        }
        UiHelper.notifyNewRootView(this)

        showHiddenApps = intent.getBooleanExtra(SHOW_HIDDEN_APPS_EXTRA, false)
        uuidString = intent.getStringExtra(UUID_EXTRA)

        val hiddenAppsPrefs = getSharedPreferences(HIDDEN_APPS_PREF_FILENAME, MODE_PRIVATE)
        hiddenAppsPrefs.getStringSet(uuidString, emptySet())?.forEach { hiddenAppIds.add(it.toInt()) }

        val computerName = intent.getStringExtra(NAME_EXTRA) ?: ""
        title = computerName

        prefConfig = PreferenceConfiguration.readPreferences(this)

        bindService(Intent(this, ComputerManagerService::class.java), serviceConnection, Service.BIND_AUTO_CREATE)

        setContent {
            com.limelight.ui.theme.MassFusionTheme {
                AppScreen(
                    computerName = computerName,
                    apps = appsList,
                    activeProfileName = activeProfileName,
                    onProfilesClick = { startActivity(Intent(this, ProfilesActivity::class.java)) },
                    onAppClick = { app, showMenuCb -> handleAppClick(app, showMenuCb) },
                    assetLoader = assetLoader,
                    onAppAction = { app, action -> handleAppAction(app, action) }
                )
            }
        }
    }

    private fun handleAppClick(app: AppObject, showMenuCallback: () -> Unit) {
        val comp = computer ?: return
        val binder = managerBinder ?: return
        if (lastRunningAppId != 0) {
            if (lastRunningAppId == app.app.appId) {
                ServerHelper.doStart(this, app.app, comp, binder, prefConfig?.useVirtualDisplay ?: false)
            } else {
                showMenuCallback()
            }
        } else {
            if (prefConfig?.useVirtualDisplay == true && comp.vDisplaySupported && !comp.vDisplayDriverReady) {
                UiHelper.displayVdisplayConfirmationDialog(this, comp,
                    { ServerHelper.doStart(this, app.app, comp, binder, true) }, null)
            } else {
                ServerHelper.doStart(this, app.app, comp, binder, prefConfig?.useVirtualDisplay ?: false)
            }
        }
    }

    enum class AppAction {
        START_RESUME, QUIT, START_QUIT, START_VDISPLAY, START_QUIT_VDISPLAY, 
        PAIR_CONTROLLERS, VIEW_DETAILS, TOGGLE_HIDE, EXPORT_LAUNCHER
    }

    private fun handleAppAction(app: AppObject, action: AppAction) {
        val comp = computer ?: return
        val binder = managerBinder ?: return
        when (action) {
            AppAction.START_QUIT, AppAction.START_QUIT_VDISPLAY -> {
                val withVDisplay = action == AppAction.START_QUIT_VDISPLAY
                if (withVDisplay && comp.vDisplaySupported && !comp.vDisplayDriverReady) {
                    UiHelper.displayVdisplayConfirmationDialog(this, comp,
                        { ServerHelper.doStart(this@AppView, app.app, comp, binder, true) }, null)
                } else {
                    ServerHelper.doStart(this@AppView, app.app, comp, binder, withVDisplay)
                }
            }
            AppAction.START_RESUME, AppAction.START_VDISPLAY -> {
                val withVDisplay = action == AppAction.START_VDISPLAY
                if (withVDisplay && comp.vDisplaySupported && !comp.vDisplayDriverReady) {
                    UiHelper.displayVdisplayConfirmationDialog(this, comp,
                        { ServerHelper.doStart(this@AppView, app.app, comp, binder, true) }, null)
                } else {
                    ServerHelper.doStart(this, app.app, comp, binder, withVDisplay)
                }
            }
            AppAction.PAIR_CONTROLLERS -> ServerHelper.doStartPairControllers(this, app.app, comp, binder)
            AppAction.QUIT -> {
                suspendGridUpdates = true
                ServerHelper.doQuit(this, comp, app.app, binder) {
                    suspendGridUpdates = false
                    poller?.pollNow()
                }
            }
            AppAction.VIEW_DETAILS -> Dialog.displayDialog(this, getString(R.string.title_details), app.app.toString(), false)
            AppAction.TOGGLE_HIDE -> {
                if (app.isHidden) {
                    hiddenAppIds.remove(app.app.appId)
                    app.isHidden = false
                } else {
                    hiddenAppIds.add(app.app.appId)
                    app.isHidden = true
                }
                updateHiddenApps()
                // Trigger recompose
                appsList = appsList.toList()
            }
            AppAction.EXPORT_LAUNCHER -> {
                if (app.app.appUUID.isNullOrEmpty()) {
                    UiHelper.displayConfirmationDialog(this, getString(R.string.title_export_sunshine_launcher_file),
                        getString(R.string.message_export_sunshine_launcher_file), getString(R.string.proceed), getString(R.string.cancel),
                        { shortcutHelper.exportLauncherFile(comp, app.app) }, null)
                } else {
                    shortcutHelper.exportLauncherFile(comp, app.app)
                }
            }
        }
    }

    private fun updateHiddenApps() {
        val prefs = getSharedPreferences(HIDDEN_APPS_PREF_FILENAME, MODE_PRIVATE)
        prefs.edit().putStringSet(uuidString ?: "", hiddenAppIds.map { it.toString() }.toSet()).apply()
        appsList = appsList.filter { !it.isHidden || showHiddenApps }
    }

    private fun populateAppGridWithCache() {
        try {
            lastRawApplist = CacheHelper.readInputStreamToString(CacheHelper.openCacheFileForInput(cacheDir, "applist", uuidString ?: ""))
            val applist = NvHTTP.getAppListByReader(StringReader(lastRawApplist))
            updateUiWithAppList(applist)
        } catch (e: Exception) {
            runOnUiThread {
                blockingLoadSpinner = SpinnerDialog.displayDialog(this, getString(R.string.applist_refresh_title), getString(R.string.applist_refresh_msg), true)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        SpinnerDialog.closeDialogs(this)
        Dialog.closeDialogs()
        managerBinder?.let { unbindService(serviceConnection) }
    }

    override fun onResume() {
        super.onResume()
        UiHelper.showDecoderCrashDialog(this)
        inForeground = true
        startComputerUpdates()
        activeProfileName = ProfilesManager.getInstance().activeName
    }

    override fun onPause() {
        super.onPause()
        inForeground = false
        stopComputerUpdates()
    }

    private fun updateUiWithServerinfo(details: ComputerDetails) {
        runOnUiThread {
            var updated = false
            for (app in appsList) {
                if (app.app.appId == details.runningGameId) {
                    if (!app.isRunning) {
                        app.isRunning = true
                        updated = true
                    }
                } else {
                    if (app.isRunning) {
                        app.isRunning = false
                        updated = true
                    }
                }
            }
            if (updated) appsList = appsList.toList()
        }
    }

    private fun updateUiWithAppList(appList: List<NvApp>) {
        runOnUiThread {
            val newList = appsList.toMutableList()
            var updated = false
            
            for (app in appList) {
                val existing = newList.find { it.app.appId == app.appId }
                if (existing != null) {
                    if (existing.app.appName != app.appName) {
                        existing.app.appName = app.appName
                        updated = true
                    }
                } else {
                    val newApp = AppObject(app)
                    newApp.isHidden = hiddenAppIds.contains(app.appId)
                    newList.add(newApp)
                    computer?.let { shortcutHelper.enableAppShortcut(it, app) }
                    updated = true
                }
            }
            
            val it = newList.iterator()
            while (it.hasNext()) {
                val existingApp = it.next()
                if (appList.none { it.appId == existingApp.app.appId }) {
                    computer?.let { shortcutHelper.disableAppShortcut(it, existingApp.app, getString(R.string.app_removed_from_pc)) }
                    it.remove()
                    updated = true
                }
            }
            
            if (updated) {
                newList.sortBy { it.app.appName.lowercase() }
                newList.sortBy { it.app.appIndex }
                appsList = newList.filter { !it.isHidden || showHiddenApps }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun AppScreen(
    computerName: String,
    apps: List<AppView.AppObject>,
    activeProfileName: String,
    onProfilesClick: () -> Unit,
    onAppClick: (AppView.AppObject, () -> Unit) -> Unit,
    assetLoader: CachedAppAssetLoader?,
    onAppAction: (AppView.AppObject, AppView.AppAction) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(title = { Text(computerName) })
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onProfilesClick,
                text = { Text(if (activeProfileName.isEmpty()) "Profiles" else activeProfileName) },
                icon = {}
            )
        }
    ) { paddingValues ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 120.dp),
            contentPadding = paddingValues,
            modifier = Modifier.fillMaxSize().padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(apps) { app ->
                AppGridItem(app, assetLoader, onAppClick, onAppAction)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppGridItem(
    appObj: AppView.AppObject,
    assetLoader: CachedAppAssetLoader?,
    onClick: (AppView.AppObject, () -> Unit) -> Unit,
    onAppAction: (AppView.AppObject, AppView.AppAction) -> Unit,
    aspectRatio: Float = 1.0f
) {
    var showMenu by remember { mutableStateOf(false) }
    
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(aspectRatio)
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
            )
            .combinedClickable(
                onClick = { onClick(appObj) { showMenu = true } },
                onLongClick = { showMenu = true }
            ),
        contentAlignment = Alignment.Center
    ) {
        val context = LocalContext.current
        AndroidView(
            modifier = Modifier.fillMaxSize().clip(
                RoundedCornerShape(16.dp)
            ),
            factory = { ctx ->
                val frame = android.widget.FrameLayout(ctx)
                val img = ImageView(ctx).apply {
                    layoutParams = android.widget.FrameLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    scaleType = ImageView.ScaleType.CENTER_CROP
                }
                val txt = TextView(ctx).apply {
                    layoutParams = android.widget.FrameLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        gravity = android.view.Gravity.CENTER
                    }
                    setTextColor(android.graphics.Color.WHITE)
                    visibility = View.GONE
                }
                frame.addView(img)
                frame.addView(txt)
                frame.tag = Pair(img, txt)
                frame
            },
            update = { frame ->
                val pair = frame.tag as Pair<ImageView, TextView>
                val img = pair.first
                val txt = pair.second
                assetLoader?.populateImageView(appObj.app, img, txt)
                if (appObj.isHidden) {
                    frame.alpha = 0.4f
                } else {
                    frame.alpha = 1.0f
                }
            }
        )
        
        if (appObj.isRunning) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                    .clip(RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painterResource(R.drawable.ic_play),
                    contentDescription = "Running",
                    modifier = Modifier.size(60.dp)
                )
            }
        }
        
        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
            if (appObj.isRunning) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.applist_menu_resume)) },
                    onClick = { showMenu = false; onAppAction(appObj, AppView.AppAction.START_RESUME) }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.applist_menu_quit)) },
                    onClick = { showMenu = false; onAppAction(appObj, AppView.AppAction.QUIT) }
                )
            } else {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.applist_menu_quit)) },
                    onClick = { showMenu = false; onAppAction(appObj, AppView.AppAction.START_QUIT) }
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.applist_menu_details)) },
                onClick = { showMenu = false; onAppAction(appObj, AppView.AppAction.VIEW_DETAILS) }
            )
            DropdownMenuItem(
                text = { Text(if (appObj.isHidden) "Unhide App" else stringResource(R.string.applist_menu_hide_app)) },
                onClick = { showMenu = false; onAppAction(appObj, AppView.AppAction.TOGGLE_HIDE) }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.applist_menu_export_launcher)) },
                onClick = { showMenu = false; onAppAction(appObj, AppView.AppAction.EXPORT_LAUNCHER) }
            )
        }
    }
}
