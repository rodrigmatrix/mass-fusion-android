package com.limelight.preferences

import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.limelight.AppView
import com.limelight.Game
import com.limelight.PcView
import com.limelight.R
import com.limelight.ShortcutTrampoline
import com.limelight.computers.ComputerManagerService
import com.limelight.nvstream.http.ComputerDetails
import com.limelight.nvstream.http.NvHTTP
import com.limelight.nvstream.jni.MoonBridge
import com.limelight.utils.Dialog
import com.limelight.utils.ServerHelper
import com.limelight.utils.SpinnerDialog
import com.limelight.utils.UiHelper
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InterfaceAddress
import java.net.NetworkInterface
import java.util.Collections
import java.util.Objects
import java.util.concurrent.LinkedBlockingQueue

class AddComputerManually : AppCompatActivity() {

    private var managerBinder: ComputerManagerService.ComputerManagerBinder? = null
    private val computersToAdd = LinkedBlockingQueue<String>()
    private var addThread: Thread? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, binder: IBinder) {
            managerBinder = binder as ComputerManagerService.ComputerManagerBinder
            startAddThread()
        }

        override fun onServiceDisconnected(className: ComponentName) {
            managerBinder = null
        }
    }

    private var editUuid: String? = null

    private fun isWrongSubnetSiteLocalAddress(address: String): Boolean {
        try {
            val targetAddress = InetAddress.getByName(address)
            if (targetAddress !is Inet4Address || !targetAddress.isSiteLocalAddress) {
                return false
            }

            for (iface in Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (addr in iface.interfaceAddresses) {
                    val a = addr.address
                    if (a !is Inet4Address || !a.isSiteLocalAddress) {
                        continue
                    }

                    val targetAddrBytes = targetAddress.address
                    val ifaceAddrBytes = a.address

                    var addressMatches = true
                    for (i in 0 until addr.networkPrefixLength) {
                        if ((ifaceAddrBytes[i / 8].toInt() and (1 shl (i % 8))) !=
                            (targetAddrBytes[i / 8].toInt() and (1 shl (i % 8)))
                        ) {
                            addressMatches = false
                            break
                        }
                    }

                    if (addressMatches) {
                        return false
                    }
                }
            }

            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    private fun parseRawUserInputToUri(rawUserInput: String): Uri? {
        var uri = Uri.parse("art://$rawUserInput")
        if (!uri.host.isNullOrEmpty()) {
            return uri
        }

        uri = Uri.parse("art://[$rawUserInput]")
        if (!uri.host.isNullOrEmpty()) {
            return uri
        }

        return null
    }

    private fun doAddOrEditPc(localIp: String, tailscaleIp: String) {
        val mb = managerBinder
        if (mb == null) {
            runOnUiThread {
                Toast.makeText(this, R.string.error_manager_not_running, Toast.LENGTH_LONG).show()
            }
            return
        }
        
        val localUri = if (localIp.isNotEmpty()) parseRawUserInputToUri(localIp) else null
        val tailscaleUri = if (tailscaleIp.isNotEmpty()) parseRawUserInputToUri(tailscaleIp) else null
        
        if (localUri == null && tailscaleUri == null) {
            runOnUiThread {
                Toast.makeText(this, R.string.addpc_enter_ip, Toast.LENGTH_LONG).show()
            }
            return
        }

        Thread {
            try {
                if (editUuid != null) {
                    val computer = mb.getComputer(editUuid)
                    if (computer != null) {
                        if (localUri != null) {
                            computer.manualAddress = ComputerDetails.AddressTuple(localUri.host, localUri.port.takeIf { it != -1 } ?: NvHTTP.DEFAULT_HTTP_PORT)
                        } else {
                            computer.manualAddress = null
                        }
                        if (tailscaleUri != null) {
                            computer.tailscaleAddress = ComputerDetails.AddressTuple(tailscaleUri.host, tailscaleUri.port.takeIf { it != -1 } ?: NvHTTP.DEFAULT_HTTP_PORT)
                        } else {
                            computer.tailscaleAddress = null
                        }
                        mb.updateComputer(computer)
                        runOnUiThread {
                            Toast.makeText(this, "PC Updated", Toast.LENGTH_LONG).show()
                            finish()
                        }
                    } else {
                        runOnUiThread {
                            Toast.makeText(this, "PC not found", Toast.LENGTH_LONG).show()
                        }
                    }
                } else {
                    val details = ComputerDetails()
                    if (localUri != null) {
                        details.manualAddress = ComputerDetails.AddressTuple(localUri.host, localUri.port.takeIf { it != -1 } ?: NvHTTP.DEFAULT_HTTP_PORT)
                    }
                    if (tailscaleUri != null) {
                        details.tailscaleAddress = ComputerDetails.AddressTuple(tailscaleUri.host, tailscaleUri.port.takeIf { it != -1 } ?: NvHTTP.DEFAULT_HTTP_PORT)
                    }
                    val success = mb.addComputerBlocking(details)
                    runOnUiThread {
                        if (success) {
                            Toast.makeText(this, R.string.addpc_success, Toast.LENGTH_LONG).show()
                            finish()
                        } else {
                            Dialog.displayDialog(this, resources.getString(R.string.conn_error_title), resources.getString(R.string.addpc_fail), false)
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    // Add/Edit PC uses the same workflow now directly communicating with ComputerManagerService

    private fun doAddPc(address: String) {
        val mb = managerBinder
        if (mb == null) {
            return
        }

        val uri = Uri.parse("art://$address")
        val details = ComputerDetails()
        details.manualAddress = ComputerDetails.AddressTuple(uri.host, uri.port.takeIf { it != -1 } ?: NvHTTP.DEFAULT_HTTP_PORT)

        if (mb.addComputerBlocking(details)) {
            runOnUiThread {
                Toast.makeText(this, R.string.addpc_success, Toast.LENGTH_LONG).show()
                finish()
            }
        } else {
            runOnUiThread {
                Dialog.displayDialog(this, resources.getString(R.string.conn_error_title), resources.getString(R.string.addpc_fail), false)
            }
        }
    }

    private fun startAddThread() {
        addThread = object : Thread() {
            override fun run() {
                while (!isInterrupted) {
                    try {
                        val computer = computersToAdd.take()
                        doAddPc(computer)
                    } catch (e: InterruptedException) {
                        return
                    }
                }
            }
        }
        addThread?.name = "UI - AddComputerManually"
        addThread?.start()
    }

    private fun joinAddThread() {
        addThread?.let {
            it.interrupt()
            try {
                it.join()
            } catch (e: InterruptedException) {
                e.printStackTrace()
                Thread.currentThread().interrupt()
            }
            addThread = null
        }
    }

    override fun onStop() {
        super.onStop()
        Dialog.closeDialogs()
        SpinnerDialog.closeDialogs(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (managerBinder != null) {
            joinAddThread()
            unbindService(serviceConnection)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        editUuid = intent.getStringExtra("edit_uuid")
        val editName = intent.getStringExtra("edit_name")
        val editLocal = intent.getStringExtra("edit_local")
        val editTailscale = intent.getStringExtra("edit_tailscale")

        val action = intent.action
        var server: String? = null
        var query: String? = null
        val data = intent.data

        if (Intent.ACTION_VIEW == action && data != null) {
            val port = data.port

            if (port == -1) {
                val urlAction = data.host
                if (urlAction == "launch") {
                    val hostUUID = data.getQueryParameter("host_uuid")
                    val hostName = data.getQueryParameter("host_name")
                    val appUUID = data.getQueryParameter("app_uuid")
                    val appName = data.getQueryParameter("app_name")
                    val appID = data.getQueryParameter("app_id")

                    val newIntent = Intent(this@AddComputerManually, ShortcutTrampoline::class.java)
                    newIntent.flags = Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK
                    newIntent.putExtra(AppView.UUID_EXTRA, hostUUID)
                    newIntent.putExtra(AppView.NAME_EXTRA, hostName)
                    newIntent.putExtra(Game.EXTRA_APP_UUID, appUUID)
                    newIntent.putExtra(Game.EXTRA_APP_NAME, appName)
                    newIntent.putExtra(Game.EXTRA_APP_ID, appID)

                    finish()
                    startActivity(newIntent)
                    return
                }
            }

            server = data.authority
            query = data.query
        }

        UiHelper.setLocale(this)

        // Set up the Compose UI
        setContent {
            com.limelight.ui.theme.MassFusionTheme {
                AddComputerScreen(
                    initialLocal = editLocal ?: server ?: "",
                    initialTailscale = editTailscale ?: "",
                    isEdit = editUuid != null,
                    editName = editName,
                    onSavePc = { local, tailscale ->
                        doAddOrEditPc(local, tailscale)
                    }
                )
            }
        }

        UiHelper.notifyNewRootView(this)

        bindService(
            Intent(this, ComputerManagerService::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE
        )

        if (data == null || server == null || query == null) {
            return
        }

        if (query.isNotEmpty()) {
            var hostName = data.getQueryParameter("name")
            if (!hostName.isNullOrEmpty()) {
                hostName = "$hostName ($server)"
            } else {
                hostName = server
            }

            val builder = AlertDialog.Builder(this)
            builder.setTitle(R.string.pair_pc_confirm_title)
            builder.setMessage(getString(R.string.pair_pc_confirm_message, hostName))

            builder.setPositiveButton(getString(R.string.proceed)) { dialog, _ ->
                dialog.dismiss()
                finish()
                computersToAdd.add("$server?$query")
            }

            builder.setNegativeButton(getString(R.string.cancel)) { dialog, _ ->
                dialog.dismiss()
            }

            builder.create().show()
        }
    }

    // Removed handleDoneEvent as logic is integrated into doAddOrEditPc

}

@Composable
fun AddComputerScreen(
    initialLocal: String,
    initialTailscale: String,
    isEdit: Boolean,
    editName: String?,
    onSavePc: (String, String) -> Unit
) {
    var localAddress by remember { mutableStateOf(initialLocal) }
    var tailscaleAddress by remember { mutableStateOf(initialTailscale) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1A1A1A))
            .padding(16.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = if (isEdit) "Edit PC: ${editName ?: ""}" else stringResource(id = R.string.title_add_pc),
                fontSize = 22.sp,
                color = Color.White,
                modifier = Modifier.padding(bottom = 25.dp)
            )

            OutlinedTextField(
                value = localAddress,
                onValueChange = { localAddress = it },
                label = { Text("Local Stream IP") },
                placeholder = { Text(stringResource(id = R.string.ip_hint)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Next
                ),
                colors = TextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent
                )
            )
            
            OutlinedTextField(
                value = tailscaleAddress,
                onValueChange = { tailscaleAddress = it },
                label = { Text("Tailscale Streaming IP") },
                placeholder = { Text(stringResource(id = R.string.ip_hint)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        onSavePc(localAddress, tailscaleAddress)
                    }
                ),
                colors = TextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent
                )
            )

            Button(
                onClick = { onSavePc(localAddress, tailscaleAddress) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = if (isEdit) "Save" else stringResource(id = android.R.string.ok))
            }
        }
    }
}
