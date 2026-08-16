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
    private val physicalBleControllersById = HashMap<Int, ProConBleDriver>()
    private val latestStatesById = HashMap<Int, ControllerState>()
    private var listener: UsbDriverListener? = null
    private var nextDeviceId = 100 // Start at 100 to avoid conflict with USB devices
    private val connectedAddresses = HashSet<String>()
    private var combineJoyCons = true
    private var virtualJoyConPair: VirtualJoyConPairController? = null

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
        val physical = physicalBleControllersById[controllerId]
        if (combineJoyCons && physical != null && physical.isJoyCon()) {
            latestStatesById[controllerId] = ControllerState(
                buttonFlags,
                leftStickX,
                leftStickY,
                rightStickX,
                rightStickY,
                leftTrigger,
                rightTrigger,
            )
            reportCombinedJoyConState()
            return
        }

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
        val physical = physicalBleControllersById[controllerId]
        val pair = virtualJoyConPair
        if (combineJoyCons && physical != null && physical.isJoyCon() && pair != null) {
            val shouldUseMotion = physical.isJoyConRight() || !hasConnectedJoyConRight()
            if (shouldUseMotion) {
                listener?.reportControllerMotion(pair.getControllerId(), motionType, motionX, motionY, motionZ)
            }
            return
        }

        listener?.reportControllerMotion(controllerId, motionType, motionX, motionY, motionZ)
    }

    override fun deviceRemoved(controller: AbstractController) {
        controllers.remove(controller)
        if (controller is ProConBleDriver) {
            connectedAddresses.remove(controller.address)
            physicalBleControllersById.remove(controller.getControllerId())
            latestStatesById.remove(controller.getControllerId())
            if (combineJoyCons && controller.isJoyCon()) {
                removeVirtualJoyConPair()
                if (physicalBleControllersById.values.any { it.isJoyCon() }) {
                    ensureVirtualJoyConPair()
                }
                if (controllers.isEmpty()) {
                    started = false
                }
                return
            }
        }
        if (controllers.isEmpty()) {
            started = false
        }
        listener?.deviceRemoved(controller)
    }

    override fun deviceAdded(controller: AbstractController) {
        if (controller is ProConBleDriver) {
            physicalBleControllersById[controller.getControllerId()] = controller
            if (combineJoyCons && controller.isJoyCon()) {
                ensureVirtualJoyConPair()
                return
            }
        }
        listener?.deviceAdded(controller)
    }

    inner class BleDriverBinder : Binder() {
        fun setListener(listener: UsbDriverListener?) {
            this@BleDriverService.listener = listener
            if (listener != null) {
                if (combineJoyCons && virtualJoyConPair != null) {
                    listener.deviceAdded(virtualJoyConPair)
                    controllers
                        .filterNot { it is ProConBleDriver && it.isJoyCon() }
                        .forEach(listener::deviceAdded)
                } else {
                    controllers.forEach(listener::deviceAdded)
                }
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

        try {
            stopService(Intent().setComponent(android.content.ComponentName(this, "com.switch2.controllers.service.Switch2BleDriverService")))
        } catch (_: Exception) {}

        if (bluetoothAdapter == null) {
            val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            bluetoothAdapter = bluetoothManager?.adapter
        }

        val pairedControllers = Switch2ControllerMappings.getPairedControllers(this)
        val adapter = bluetoothAdapter
        combineJoyCons = Switch2ControllerMappings.combineJoyCons(this)

        LimeLog.info(
            "BleDriverService: start() - controllers=$pairedControllers " +
                "combineJoyCons=$combineJoyCons btAdapter=${if (adapter != null) "ok" else "null"}",
        )

        if (pairedControllers.isNotEmpty() && adapter != null && adapter.isEnabled) {
            for (mac in pairedControllers) {
                if (connectedAddresses.contains(mac)) {
                    continue
                }
                val device = adapter.getRemoteDevice(mac)
                val productId = Switch2ControllerMappings.controllerProductId(this, mac)
                val controllerName = Switch2ControllerMappings.controllerNameForProduct(productId)
                
                if (productId == Switch2ControllerMappings.PRODUCT_JOYCON_L || productId == Switch2ControllerMappings.PRODUCT_JOYCON_R) {
                    LimeLog.info("BleDriverService skipping $controllerName $mac (handled natively by Android)")
                    continue
                }

                LimeLog.info(
                    "BleDriverService attempting to connect to $controllerName $mac " +
                        "product=0x${productId.toString(16)}",
                )
                val controller = ProConBleDriver(this, device, nextDeviceId++, this, productId)
                if (controller.start()) {
                    controllers.add(controller)
                    connectedAddresses.add(mac)
                    started = true
                } else {
                    LimeLog.warning("BleDriverService: Switch2 BLE driver start() returned false for $mac")
                }
            }
        } else {
            LimeLog.warning("BleDriverService: cannot connect - controllers=$pairedControllers btEnabled=${adapter?.isEnabled == true}")
        }
    }

    private fun stop() {
        if (!started) return
        started = false

        removeVirtualJoyConPair()
        while (controllers.isNotEmpty()) {
            controllers.removeAt(0).stop()
        }
        connectedAddresses.clear()
        physicalBleControllersById.clear()
        latestStatesById.clear()
    }

    private fun ensureVirtualJoyConPair() {
        if (virtualJoyConPair != null) return
        val pair = VirtualJoyConPairController(nextDeviceId++, this)
        virtualJoyConPair = pair
        LimeLog.info("BleDriverService: exposing paired Joy-Con 2 virtual controller id=${pair.getControllerId()}")
        listener?.deviceAdded(pair)
    }

    private fun removeVirtualJoyConPair() {
        val pair = virtualJoyConPair ?: return
        LimeLog.info("BleDriverService: removing paired Joy-Con 2 virtual controller id=${pair.getControllerId()}")
        listener?.deviceRemoved(pair)
        virtualJoyConPair = null
    }

    private fun reportCombinedJoyConState() {
        val pair = virtualJoyConPair ?: return
        var buttonFlags = 0
        var leftStickX = 0f
        var leftStickY = 0f
        var rightStickX = 0f
        var rightStickY = 0f
        var leftTrigger = 0f
        var rightTrigger = 0f

        for ((controllerId, state) in latestStatesById) {
            val controller = physicalBleControllersById[controllerId] ?: continue
            if (!controller.isJoyCon()) continue

            buttonFlags = buttonFlags or state.buttonFlags
            leftStickX = maxByMagnitude(leftStickX, state.leftStickX)
            leftStickY = maxByMagnitude(leftStickY, state.leftStickY)
            rightStickX = maxByMagnitude(rightStickX, state.rightStickX)
            rightStickY = maxByMagnitude(rightStickY, state.rightStickY)
            leftTrigger = maxOf(leftTrigger, state.leftTrigger)
            rightTrigger = maxOf(rightTrigger, state.rightTrigger)
        }

        listener?.reportControllerState(
            pair.getControllerId(),
            buttonFlags,
            leftStickX,
            leftStickY,
            rightStickX,
            rightStickY,
            leftTrigger,
            rightTrigger,
        )
    }

    private fun maxByMagnitude(a: Float, b: Float): Float {
        return if (kotlin.math.abs(b) > kotlin.math.abs(a)) b else a
    }

    private fun hasConnectedJoyConRight(): Boolean {
        return physicalBleControllersById.values.any { it.isJoyConRight() }
    }

    private fun rumbleConnectedJoyCons(lowFreqMotor: Short, highFreqMotor: Short) {
        physicalBleControllersById.values
            .filter { it.isJoyCon() }
            .forEach { it.rumble(lowFreqMotor, highFreqMotor) }
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

    private data class ControllerState(
        val buttonFlags: Int,
        val leftStickX: Float,
        val leftStickY: Float,
        val rightStickX: Float,
        val rightStickY: Float,
        val leftTrigger: Float,
        val rightTrigger: Float,
    )

    private inner class VirtualJoyConPairController(
        deviceId: Int,
        listener: UsbDriverListener,
    ) : AbstractController(
        deviceId,
        listener,
        Switch2ControllerMappings.NINTENDO_VENDOR_ID,
        Switch2ControllerMappings.PRODUCT_PRO_CONTROLLER_2,
    ) {
        init {
            type = com.limelight.nvstream.jni.MoonBridge.LI_CTYPE_NINTENDO
            capabilities = (
                com.limelight.nvstream.jni.MoonBridge.LI_CCAP_GYRO.toInt() or
                    com.limelight.nvstream.jni.MoonBridge.LI_CCAP_ACCEL.toInt() or
                    com.limelight.nvstream.jni.MoonBridge.LI_CCAP_RUMBLE.toInt()
                ).toShort()
            supportedButtonFlags = Switch2ControllerMappings.supportedButtonFlags()
        }

        override fun start(): Boolean = true

        override fun stop() = Unit

        override fun rumble(lowFreqMotor: Short, highFreqMotor: Short) {
            rumbleConnectedJoyCons(lowFreqMotor, highFreqMotor)
        }

        override fun rumbleTriggers(leftTrigger: Short, rightTrigger: Short) {
            rumbleConnectedJoyCons(leftTrigger, rightTrigger)
        }
    }
}
