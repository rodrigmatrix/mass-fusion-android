package com.limelight.binding.input.driver

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import com.limelight.LimeLog
import com.limelight.nvstream.jni.MoonBridge
import com.switch2.controllers.core.Switch2Controller
import com.switch2.controllers.core.Switch2ControllerListener
import com.switch2.controllers.core.Switch2ControllerState
import com.switch2.controllers.core.Switch2MotionState
import com.switch2.controllers.manager.Switch2Manager
import java.util.concurrent.ConcurrentHashMap

class BleDriverService : Service() {
    private val binder = BleDriverBinder()
    private var listener: UsbDriverListener? = null
    private lateinit var switch2Manager: Switch2Manager
    private val controllerWrappers = ConcurrentHashMap<Int, AbstractController>()

    private inner class Switch2ControllerWrapper(
        private val controller: Switch2Controller,
        listener: UsbDriverListener,
    ) : AbstractController(controller.controllerId, listener, controller.vendorId, controller.productId) {

        init {
            type = MoonBridge.LI_CTYPE_NINTENDO
            capabilities = (
                MoonBridge.LI_CCAP_GYRO.toInt() or
                MoonBridge.LI_CCAP_ACCEL.toInt() or
                MoonBridge.LI_CCAP_RUMBLE.toInt()
            ).toShort()
            supportedButtonFlags = controller.supportedButtonFlags
        }

        override fun start(): Boolean = true

        override fun stop() = Unit

        override fun rumble(lowFreqMotor: Short, highFreqMotor: Short) {
            switch2Manager.rumble(controller.controllerId, lowFreqMotor, highFreqMotor)
        }

        override fun rumbleTriggers(leftTrigger: Short, rightTrigger: Short) {
            switch2Manager.rumble(controller.controllerId, leftTrigger, rightTrigger)
        }
    }

    private val switch2Listener = object : Switch2ControllerListener {
        override fun onControllerAdded(controller: Switch2Controller) {
            LimeLog.info("BleDriverService: Switch2Controller added: ${controller.name} (id: ${controller.controllerId})")
            val currentListener = listener ?: return
            val wrapper = Switch2ControllerWrapper(controller, currentListener)
            controllerWrappers[controller.controllerId] = wrapper
            currentListener.deviceAdded(wrapper)
        }

        override fun onControllerRemoved(controller: Switch2Controller) {
            LimeLog.info("BleDriverService: Switch2Controller removed: ${controller.name} (id: ${controller.controllerId})")
            val wrapper = controllerWrappers.remove(controller.controllerId)
            val currentListener = listener ?: return
            if (wrapper != null) {
                currentListener.deviceRemoved(wrapper)
            }
        }

        override fun onControllerStateReported(state: Switch2ControllerState) {
            listener?.reportControllerState(
                state.controllerId,
                state.buttonFlags,
                state.leftStickX,
                state.leftStickY,
                state.rightStickX,
                state.rightStickY,
                state.leftTrigger,
                state.rightTrigger,
            )
        }

        override fun onControllerMotionReported(motion: Switch2MotionState) {
            listener?.reportControllerMotion(
                motion.controllerId,
                motion.motionType,
                motion.x,
                motion.y,
                motion.z,
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        switch2Manager = Switch2Manager.getInstance(this)
        switch2Manager.start()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    inner class BleDriverBinder : Binder() {
        fun setListener(listener: UsbDriverListener?) {
            this@BleDriverService.listener = listener
            if (listener != null) {
                val currentControllers = switch2Manager.getConnectedControllers()
                LimeLog.info("BleDriverService: setListener, current connected controllers: ${currentControllers.size}")
                for (ctrl in currentControllers) {
                    val wrapper = Switch2ControllerWrapper(ctrl, listener)
                    controllerWrappers[ctrl.controllerId] = wrapper
                    listener.deviceAdded(wrapper)
                }
            } else {
                controllerWrappers.clear()
            }
        }

        fun start() {
            LimeLog.info("BleDriverService: start() connecting Switch2Manager")
            switch2Manager.start()
            switch2Manager.addListener(switch2Listener)
        }

        fun stop() {
            LimeLog.info("BleDriverService: stop()")
            switch2Manager.removeListener(switch2Listener)
            controllerWrappers.clear()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        switch2Manager.start()
        return START_STICKY
    }

    override fun onDestroy() {
        switch2Manager.removeListener(switch2Listener)
        controllerWrappers.clear()
        listener = null
        super.onDestroy()
    }
}
