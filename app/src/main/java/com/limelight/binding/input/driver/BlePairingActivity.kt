package com.limelight.binding.input.driver

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
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
import com.limelight.LimeLog

class BlePairingActivity : Activity() {
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var scanning = false
    private lateinit var handler: Handler

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handler = Handler(Looper.getMainLooper())

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter

        if (bluetoothAdapter?.isEnabled != true) {
            Toast.makeText(this, "Bluetooth must be enabled to pair controllers", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        if (checkPermissions()) {
            startBleScan()
        } else {
            requestPermissions()
        }
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

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                startBleScan()
            } else {
                Toast.makeText(this, "Permissions required to scan for BLE controllers", Toast.LENGTH_SHORT).show()
                finish()
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
            finish()
            return
        }

        Toast.makeText(this, "Scanning for Switch Pro Controller...", Toast.LENGTH_LONG).show()

        handler.postDelayed({
            if (scanning) {
                scanning = false
                try {
                    scanner.stopScan(leScanCallback)
                } catch (e: SecurityException) {
                    LimeLog.warning("SecurityException stopping scan: ${e.message}")
                }
                Toast.makeText(
                    this,
                    "Could not find controller. Make sure it is in pairing mode.",
                    Toast.LENGTH_LONG,
                ).show()
                finish()
            }
        }, SCAN_PERIOD)

        scanning = true
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            scanner.startScan(null, settings, leScanCallback)
        } catch (e: SecurityException) {
            LimeLog.warning("SecurityException starting scan: ${e.message}")
            Toast.makeText(this, "Permission error starting scan", Toast.LENGTH_SHORT).show()
            finish()
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

            val isProController = isProController2Advertisement(result) ||
                deviceName?.lowercase()?.contains("pro controller") == true

            if (isProController) {
                LimeLog.info("Found Nintendo Switch BLE Controller: ${result.device.address} name: $deviceName")
                scanning = false
                try {
                    bluetoothLeScanner?.stopScan(this)
                } catch (_: SecurityException) {
                }

                Switch2ControllerMappings.addPairedController(this@BlePairingActivity, result.device.address, deviceName)

                LimeLog.info("BlePairingActivity: Starting BleDriverService!")
                startService(Intent(this@BlePairingActivity, BleDriverService::class.java))

                Toast.makeText(this@BlePairingActivity, "Paired successfully with Pro Controller 2!", Toast.LENGTH_SHORT).show()
                finish()
            }
        }

        override fun onScanFailed(errorCode: Int) {
            LimeLog.warning("BLE Scan failed with code $errorCode")
            if (scanning) {
                scanning = false
                Toast.makeText(this@BlePairingActivity, "Scan failed: $errorCode", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun isProController2Advertisement(result: ScanResult): Boolean {
        val manufacturerData = result.scanRecord?.manufacturerSpecificData ?: return false
        val data = manufacturerData[NINTENDO_BLUETOOTH_MANUFACTURER_ID]
        if (data == null || data.size < 7) return false

        val vendorId = readLe16(data, 3)
        val productId = readLe16(data, 5)
        if (vendorId == NINTENDO_VENDOR_ID && productId == PRO_CONTROLLER_2_PRODUCT_ID) {
            LimeLog.info("BLE Scanner saw Pro Controller 2 manufacturer data from ${result.device.address}")
            return true
        }

        return false
    }

    private fun readLe16(data: ByteArray, offset: Int): Int {
        return (data[offset].toInt() and 0xff) or ((data[offset + 1].toInt() and 0xff) shl 8)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (scanning) {
            try {
                bluetoothLeScanner?.stopScan(leScanCallback)
            } catch (_: SecurityException) {
            }
            scanning = false
        }
    }

    companion object {
        const val PREF_PAIRED_BLE_CONTROLLER = "paired_ble_controller_mac"
        private const val PERMISSION_REQUEST_CODE = 1001
        private const val SCAN_PERIOD = 30000L
        private const val NINTENDO_BLUETOOTH_MANUFACTURER_ID = 0x0553
        private const val NINTENDO_VENDOR_ID = 0x057e
        private const val PRO_CONTROLLER_2_PRODUCT_ID = 0x2069
    }
}
