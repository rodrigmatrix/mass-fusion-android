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
    private val connectedAddresses = HashSet<String>()

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
        if (controller is ProConBleDriver) {
            connectedAddresses.remove(controller.address)
        }
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
        if (started && controllers.isEmpty()) {
            LimeLog.info("BleDriverService: recovering from stale started state.")
            started = false
        }

        val pairedControllers = Switch2ControllerMappings.getPairedControllers(this)
        val adapter = bluetoothAdapter

        LimeLog.info("BleDriverService: start() - controllers=$pairedControllers btAdapter=${if (adapter != null) "ok" else "null"}")

        if (pairedControllers.isNotEmpty() && adapter != null && adapter.isEnabled) {
            for (mac in pairedControllers) {
                if (connectedAddresses.contains(mac)) {
                    continue
                }
                val device = adapter.getRemoteDevice(mac)
                LimeLog.info("BleDriverService attempting to connect to $mac")
                val controller = ProConBleDriver(this, device, nextDeviceId++, this)
                if (controller.start()) {
                    controllers.add(controller)
                    connectedAddresses.add(mac)
                    started = true
                } else {
                    LimeLog.warning("BleDriverService: ProConBleDriver.start() returned false for $mac")
                }
            }
        } else {
            LimeLog.warning("BleDriverService: cannot connect - controllers=$pairedControllers btEnabled=${adapter?.isEnabled == true}")
        }
    }

    private fun stop() {
        if (!started) return
        started = false

        while (controllers.isNotEmpty()) {
            controllers.removeAt(0).stop()
        }
        connectedAddresses.clear()
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
