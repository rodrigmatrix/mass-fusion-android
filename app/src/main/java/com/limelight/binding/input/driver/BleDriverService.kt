package com.limelight.binding.input.driver

import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import com.limelight.LimeLog

class BleDriverService : Service(), UsbDriverListener {
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var started = false
    private val binder = BleDriverBinder()
    private val controllers = ArrayList<AbstractController>()
    private var listener: UsbDriverListener? = null
    private var nextDeviceId = 100 // Start at 100 to avoid conflict with USB devices

    override fun reportControllerState(
        controllerId: Int,
        buttonFlags: Int,
        leftStickX: Float,
        leftStickY: Float,
        rightStickX: Float,
        rightStickY: Float,
        leftTrigger: Float,
        rightTrigger: Float,
    ) {
        listener?.reportControllerState(
            controllerId,
            buttonFlags,
            leftStickX,
            leftStickY,
            rightStickX,
            rightStickY,
            leftTrigger,
            rightTrigger,
        )
    }

    override fun reportControllerMotion(
        controllerId: Int,
        motionType: Byte,
        motionX: Float,
        motionY: Float,
        motionZ: Float,
    ) {
        listener?.reportControllerMotion(controllerId, motionType, motionX, motionY, motionZ)
    }

    override fun deviceRemoved(controller: AbstractController) {
        controllers.remove(controller)
        if (controllers.isEmpty()) {
            started = false
        }
        listener?.deviceRemoved(controller)
    }

    override fun deviceAdded(controller: AbstractController) {
        listener?.deviceAdded(controller)
    }

    inner class BleDriverBinder : Binder() {
        fun setListener(listener: UsbDriverListener?) {
            this@BleDriverService.listener = listener
            if (listener != null) {
                controllers.forEach(listener::deviceAdded)
            }
        }

        fun start() {
            this@BleDriverService.start()
        }

        fun stop() {
            this@BleDriverService.stop()
        }
    }

    private fun start() {
        if (started && controllers.isNotEmpty()) {
            LimeLog.info("BleDriverService: start() called but already started, ignoring.")
            return
        }
        if (started) {
            LimeLog.info("BleDriverService: recovering from stale started state.")
            started = false
        }

        val mac = getSharedPreferences("ble_prefs", Context.MODE_PRIVATE)
            .getString(BlePairingActivity.PREF_PAIRED_BLE_CONTROLLER, null)
        val adapter = bluetoothAdapter

        LimeLog.info("BleDriverService: start() - mac=$mac btAdapter=${if (adapter != null) "ok" else "null"}")

        if (mac != null && adapter != null && adapter.isEnabled) {
            val device = adapter.getRemoteDevice(mac)
            LimeLog.info("BleDriverService attempting to connect to $mac")
            val controller = ProConBleDriver(this, device, nextDeviceId++, this)
            if (controller.start()) {
                controllers.add(controller)
                started = true
            } else {
                LimeLog.warning("BleDriverService: ProConBleDriver.start() returned false for $mac")
            }
        } else {
            LimeLog.warning("BleDriverService: cannot connect - mac=$mac btEnabled=${adapter?.isEnabled == true}")
        }
    }

    private fun stop() {
        if (!started) return
        started = false

        while (controllers.isNotEmpty()) {
            controllers.removeAt(0).stop()
        }
    }

    override fun onCreate() {
        super.onCreate()
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        LimeLog.info("BleDriverService: onStartCommand executed!")
        start()
        return START_STICKY
    }

    override fun onDestroy() {
        stop()
        listener = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder = binder
}
