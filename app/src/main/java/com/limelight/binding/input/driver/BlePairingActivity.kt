package com.limelight.binding.input.driver

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.limelight.LimeLog

class BlePairingActivity : ComponentActivity() {
    private var uiMessage by mutableStateOf("Initializing...")
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothManager: BluetoothManager? = null
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var scanning by mutableStateOf(false)
    private lateinit var handler: Handler
    private var pairedControllers by mutableStateOf(listOf<String>())
    private var connectedControllers by mutableStateOf(setOf<String>())

    private val receiver = object : android.content.BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            if (BluetoothDevice.ACTION_FOUND == action) {
                val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                }
                if (device != null) {
                    val deviceName = device.name
                    LimeLog.info("Classic BT Scanner saw device: ${device.address} Name: $deviceName")
                    
                    val productId = productIdFromDeviceName(deviceName)
                    if (productId != null) {
                        val controllerName = Switch2ControllerMappings.controllerNameForProduct(productId)
                        LimeLog.info("Found Classic BT Controller: ${device.address} product=0x${productId.toString(16)} name=$deviceName")
                        stopBleScan()

                        // For Classic BT devices (like original Joy-Cons), we must bond natively
                        try {
                            if (device.bondState == BluetoothDevice.BOND_NONE) {
                                device.createBond()
                                Toast.makeText(this@BlePairingActivity, "Pairing with $controllerName...", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(this@BlePairingActivity, "$controllerName is already paired!", Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: SecurityException) {
                            LimeLog.warning("SecurityException creating bond: ${e.message}")
                        }

                        // Add to UI so user can see it paired in the app
                        Switch2ControllerMappings.addPairedController(
                            this@BlePairingActivity,
                            device.address,
                            deviceName ?: controllerName,
                            productId,
                        )
                        refreshControllers()
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handler = Handler(Looper.getMainLooper())

        val filter = android.content.IntentFilter(BluetoothDevice.ACTION_FOUND)
        registerReceiver(receiver, filter)

        bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter

        if (bluetoothAdapter?.isEnabled != true) {
            Toast.makeText(this, "Bluetooth must be enabled to pair controllers", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        refreshControllers()

        setContent {
            com.limelight.ui.theme.MassFusionTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Top
                    ) {
                        Text("Switch 2 Controllers", style = MaterialTheme.typography.headlineMedium)
                        Spacer(modifier = Modifier.height(24.dp))
                        
                        if (pairedControllers.isEmpty()) {
                            Text("No controllers paired.", style = MaterialTheme.typography.bodyLarge)
                        } else {
                            LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f, fill = false)) {
                                items(pairedControllers) { mac ->
                                    val name = Switch2ControllerMappings.controllerName(this@BlePairingActivity, mac)
                                    val isConnected = connectedControllers.contains(mac)
                                    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Column {
                                                Text(name, style = MaterialTheme.typography.titleMedium)
                                                Text(mac, style = MaterialTheme.typography.bodyMedium)
                                                Text(
                                                    if (isConnected) "Connected" else "Disconnected",
                                                    color = if (isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                                    style = MaterialTheme.typography.labelLarge
                                                )
                                            }
                                            Button(
                                                onClick = { unpairController(mac) },
                                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                                            ) {
                                                Text("Disconnect")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(32.dp))
                        
                        if (scanning) {
                            CircularProgressIndicator()
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(uiMessage, style = MaterialTheme.typography.bodyMedium)
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(onClick = { stopBleScan() }) {
                                Text("Stop Scanning")
                            }
                        } else {
                            Button(onClick = { 
                                if (checkPermissions()) {
                                    startBleScan()
                                } else {
                                    requestPermissions()
                                }
                            }) {
                                Text("Scan for New Controller")
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(onClick = { finish() }) {
                                Text("Close")
                            }
                        }
                    }
                }
            }
        }
        
        // Auto-start scan if no controllers paired
        if (pairedControllers.isEmpty()) {
            if (checkPermissions()) {
                startBleScan()
            } else {
                requestPermissions()
            }
        }
    }
    
    private fun unpairController(mac: String) {
        Switch2ControllerMappings.removePairedController(this, mac)
        
        // Restart the BleDriverService so it drops the connection
        val intent = Intent(this, BleDriverService::class.java)
        stopService(intent)
        val remaining = Switch2ControllerMappings.getPairedControllers(this)
        if (remaining.isNotEmpty()) {
            startService(intent)
        }
        
        refreshControllers()
    }

    private fun refreshControllers() {
        pairedControllers = Switch2ControllerMappings.getPairedControllers(this)
        
        // Check connection status
        val connected = mutableSetOf<String>()
        if (checkPermissions()) {
            try {
                val devices = bluetoothManager?.getConnectedDevices(BluetoothProfile.GATT) ?: emptyList()
                for (device in devices) {
                    connected.add(device.address)
                }
            } catch (e: SecurityException) {
                // Ignore
            }
        }
        connectedControllers = connected
    }

    private fun checkPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        ActivityCompat.requestPermissions(this, permissions, PERMISSION_REQUEST_CODE)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                refreshControllers()
                if (pairedControllers.isEmpty()) {
                    startBleScan()
                }
            } else {
                Toast.makeText(this, "Permissions required to scan for BLE controllers", Toast.LENGTH_SHORT).show()
            }
            return
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    private fun startBleScan() {
        val adapter = bluetoothAdapter ?: return
        val scanner = adapter.bluetoothLeScanner
        bluetoothLeScanner = scanner

        if (scanner == null) {
            Toast.makeText(this, "BLE scanning is not supported on this device", Toast.LENGTH_LONG).show()
            return
        }

        uiMessage = "Scanning for Switch 2 and Joy-Con controllers..."

        handler.postDelayed({
            if (scanning) {
                stopBleScan()
                Toast.makeText(
                    this,
                    "Could not find controller. Make sure it is in pairing mode.",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }, SCAN_PERIOD)

        scanning = true
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            scanner.startScan(null, settings, leScanCallback)
            // Start classic discovery for Joy-Con 1
            adapter.startDiscovery()
        } catch (e: SecurityException) {
            LimeLog.warning("SecurityException starting scan: ${e.message}")
            Toast.makeText(this, "Permission error starting scan", Toast.LENGTH_SHORT).show()
            scanning = false
        }
    }
    
    private fun stopBleScan() {
        if (scanning) {
            scanning = false
            try {
                bluetoothLeScanner?.stopScan(leScanCallback)
                bluetoothAdapter?.cancelDiscovery()
            } catch (e: SecurityException) {
                LimeLog.warning("SecurityException stopping scan: ${e.message}")
            }
        }
    }

    private val leScanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!scanning) return

            var deviceName = result.device.name
            if (deviceName == null) {
                deviceName = result.scanRecord?.deviceName
            }

            LimeLog.info("BLE Scanner saw device: ${result.device.address} Name: $deviceName")

            val productId = supportedControllerProductId(result)
                ?: productIdFromDeviceName(deviceName)

            if (productId != null) {
                val controllerName = Switch2ControllerMappings.controllerNameForProduct(productId)
                LimeLog.info(
                    "Found Nintendo Switch BLE Controller: ${result.device.address} " +
                        "product=0x${productId.toString(16)} name=$deviceName",
                )
                stopBleScan()

                Switch2ControllerMappings.addPairedController(
                    this@BlePairingActivity,
                    result.device.address,
                    deviceName ?: controllerName,
                    productId,
                )

                LimeLog.info("BlePairingActivity: Starting BleDriverService!")
                startService(Intent(this@BlePairingActivity, BleDriverService::class.java))

                Toast.makeText(this@BlePairingActivity, "Paired successfully with $controllerName!", Toast.LENGTH_SHORT).show()
                refreshControllers()
            }
        }

        override fun onScanFailed(errorCode: Int) {
            LimeLog.warning("BLE Scan failed with code $errorCode")
            if (scanning) {
                scanning = false
                Toast.makeText(this@BlePairingActivity, "Scan failed: $errorCode", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun supportedControllerProductId(result: ScanResult): Int? {
        val manufacturerData = result.scanRecord?.manufacturerSpecificData ?: return null
        val data = manufacturerData[NINTENDO_BLUETOOTH_MANUFACTURER_ID]
        if (data == null || data.size < 7) return null

        val vendorId = readLe16(data, 3)
        val productId = readLe16(data, 5)
        if (vendorId == Switch2ControllerMappings.NINTENDO_VENDOR_ID &&
            Switch2ControllerMappings.isSupportedProductId(productId)
        ) {
            LimeLog.info(
                "BLE Scanner saw ${Switch2ControllerMappings.controllerNameForProduct(productId)} " +
                    "manufacturer data from ${result.device.address}",
            )
            return productId
        }

        return null
    }

    private fun productIdFromDeviceName(deviceName: String?): Int? {
        val name = deviceName?.lowercase() ?: return null
        return when {
            "joy-con 2" in name && ("left" in name || "(l)" in name) -> Switch2ControllerMappings.PRODUCT_JOYCON_2_LEFT
            "joy-con 2" in name && ("right" in name || "(r)" in name) -> Switch2ControllerMappings.PRODUCT_JOYCON_2_RIGHT
            "joy-con" in name && ("left" in name || "(l)" in name) -> Switch2ControllerMappings.PRODUCT_JOYCON_L
            "joy-con" in name && ("right" in name || "(r)" in name) -> Switch2ControllerMappings.PRODUCT_JOYCON_R
            "gamecube" in name -> Switch2ControllerMappings.PRODUCT_NSO_GAMECUBE_CONTROLLER
            "pro controller" in name -> Switch2ControllerMappings.PRODUCT_PRO_CONTROLLER_2
            else -> null
        }
    }

    private fun readLe16(data: ByteArray, offset: Int): Int {
        return (data[offset].toInt() and 0xff) or ((data[offset + 1].toInt() and 0xff) shl 8)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopBleScan()
        try {
            unregisterReceiver(receiver)
        } catch (e: IllegalArgumentException) {
            // Ignore if not registered
        }
    }

    companion object {
        const val PREF_PAIRED_BLE_CONTROLLER = "paired_ble_controller_mac"
        private const val PERMISSION_REQUEST_CODE = 1001
        private const val SCAN_PERIOD = 30000L
        private const val NINTENDO_BLUETOOTH_MANUFACTURER_ID = 0x0553
    }
}
