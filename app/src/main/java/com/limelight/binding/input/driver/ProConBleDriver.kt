package com.limelight.binding.input.driver

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.limelight.LimeLog
import com.limelight.nvstream.input.ControllerPacket
import com.limelight.nvstream.jni.MoonBridge
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.LinkedList
import java.util.Queue
import java.util.UUID

class ProConBleDriver(
    private val context: Context,
    private val device: BluetoothDevice?,
    deviceId: Int,
    listener: UsbDriverListener,
    private val productId: Int = Switch2ControllerMappings.PRODUCT_PRO_CONTROLLER_2,
) : AbstractController(deviceId, listener, USB_VENDOR_NINTENDO, productId) {
    val address: String = device?.address ?: ""
    private var gatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var vibrationCharacteristic: BluetoothGattCharacteristic? = null
    private var notifyCharacteristic: BluetoothGattCharacteristic? = null
    private var responseCharacteristic: BluetoothGattCharacteristic? = null
    private var stopped = false
    private val commandQueue: Queue<ByteArray> = LinkedList()
    private var awaitingResponse = false
    private var descriptorsSetup = 0
    private var commandSequence = 0
    private var vibrationPacketId = 0
    private var inputReportLogCount = 0
    private var decodedInputLogCount = 0
    private var lastDecodedButtons = 0
    private var lastReportedButtonFlags = 0
    private var lastRawButtons = 0
    private var lastInputReport: ByteArray? = null
    private var rawReportDeltaLogCount = 0
    private var lastLoggedLeftTrigger = -1f
    private var lastLoggedRightTrigger = -1f
    private var lastLoggedLeftStickX = Float.NaN
    private var lastLoggedLeftStickY = Float.NaN
    private var lastLoggedRightStickX = Float.NaN
    private var lastLoggedRightStickY = Float.NaN

    init {
        type = MoonBridge.LI_CTYPE_NINTENDO
        capabilities = (
            MoonBridge.LI_CCAP_GYRO.toInt() or
                MoonBridge.LI_CCAP_ACCEL.toInt() or
                MoonBridge.LI_CCAP_RUMBLE.toInt()
            ).toShort()
        supportedButtonFlags = Switch2ControllerMappings.supportedButtonFlags()
    }

    @SuppressLint("MissingPermission")
    override fun start(): Boolean {
        val targetDevice = device ?: return false
        LimeLog.info("${driverName()}: Connecting to GATT server...")
        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            targetDevice.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            targetDevice.connectGatt(context, false, gattCallback)
        }
        return gatt != null
    }

    @SuppressLint("MissingPermission")
    override fun stop() {
        if (stopped) return
        stopped = true
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        notifyDeviceRemoved()
    }

    @SuppressLint("MissingPermission")
    override fun rumble(lowFreqMotor: Short, highFreqMotor: Short) {
        val activeGatt = gatt ?: return
        val characteristic = vibrationCharacteristic ?: run {
            LimeLog.warning("${driverName()}: Rumble requested but vibration characteristic is unavailable")
            return
        }

        val low = lowFreqMotor.toInt() and 0xffff
        val high = highFreqMotor.toInt() and 0xffff
        val lowAmplitude = (low ushr 8) * MAX_SWITCH_RUMBLE_AMPLITUDE / 0xff
        val highAmplitude = (high ushr 8) * MAX_SWITCH_RUMBLE_AMPLITUDE / 0xff

        val payload = if (isJoyCon()) {
            val activeAmplitude = if (isJoyConLeft()) lowAmplitude else highAmplitude
            val frame = buildVibrationFrame(activeAmplitude, activeAmplitude)
            val motorVibrations = ByteArray(1 + frame.size * 3)
            motorVibrations[0] = (0x50 + (vibrationPacketId and 0x0f)).toByte()
            frame.copyInto(motorVibrations, destinationOffset = 1)
            frame.copyInto(motorVibrations, destinationOffset = 1 + frame.size)
            frame.copyInto(motorVibrations, destinationOffset = 1 + frame.size * 2)
            
            // JoyCons expect exactly 16 bytes on their dedicated characteristics
            motorVibrations
        } else {
            val leftFrame = buildVibrationFrame(lowAmplitude, lowAmplitude)
            val rightFrame = buildVibrationFrame(highAmplitude, highAmplitude)
            
            val leftVibrations = ByteArray(1 + leftFrame.size * 3)
            leftVibrations[0] = (0x50 + (vibrationPacketId and 0x0f)).toByte()
            leftFrame.copyInto(leftVibrations, destinationOffset = 1)
            leftFrame.copyInto(leftVibrations, destinationOffset = 1 + leftFrame.size)
            leftFrame.copyInto(leftVibrations, destinationOffset = 1 + leftFrame.size * 2)

            val rightVibrations = ByteArray(1 + rightFrame.size * 3)
            rightVibrations[0] = (0x50 + (vibrationPacketId and 0x0f)).toByte()
            rightFrame.copyInto(rightVibrations, destinationOffset = 1)
            rightFrame.copyInto(rightVibrations, destinationOffset = 1 + rightFrame.size)
            rightFrame.copyInto(rightVibrations, destinationOffset = 1 + rightFrame.size * 2)

            // Pro Controller expects 1 byte prefix + Left motor (16 bytes) + Right motor (16 bytes)
            ByteArray(1 + leftVibrations.size + rightVibrations.size).also {
                it[0] = 0x00
                leftVibrations.copyInto(it, destinationOffset = 1)
                rightVibrations.copyInto(it, destinationOffset = 1 + leftVibrations.size)
            }
        }

        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        characteristic.value = payload
        if (!activeGatt.writeCharacteristic(characteristic)) {
            LimeLog.warning("${driverName()}: Rumble write failed to start")
        }
        vibrationPacketId = (vibrationPacketId + 1) and 0x0f
    }

    override fun rumbleTriggers(leftTrigger: Short, rightTrigger: Short) = Unit

    fun isJoyCon(): Boolean = isJoyConLeft() || isJoyConRight()

    fun isJoyConLeft(): Boolean = Switch2ControllerMappings.isJoyConLeft(productId)

    fun isJoyConRight(): Boolean = Switch2ControllerMappings.isJoyConRight(productId)

    private fun buildCommand(commandId: Int, subcommandId: Int, payload: ByteArray): ByteArray {
        return ByteArray(8 + payload.size).also { data ->
            data[0] = commandId.toByte()
            data[1] = 0x91.toByte()
            data[2] = 0x01
            data[3] = subcommandId.toByte()
            data[4] = 0x00
            data[5] = payload.size.toByte()
            data[6] = 0x00
            data[7] = 0x00
            payload.copyInto(data, destinationOffset = 8)
        }
    }

    @Synchronized
    private fun enqueueCommand(commandId: Int, subcommandId: Int, payload: ByteArray) {
        commandQueue.add(buildCommand(commandId, subcommandId, payload))
        if (!awaitingResponse) {
            sendNextCommand()
        }
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    private fun sendNextCommand() {
        val characteristic = writeCharacteristic
        val activeGatt = gatt
        if (commandQueue.isEmpty() || characteristic == null || activeGatt == null) {
            awaitingResponse = false
            return
        }

        val next = commandQueue.poll()
        awaitingResponse = true
        val sequence = ++commandSequence
        characteristic.writeType =
            if ((characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            } else {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            }
        characteristic.value = next
        activeGatt.writeCharacteristic(characteristic)
        Handler(Looper.getMainLooper()).postDelayed({ onCommandTimeout(sequence) }, 1000L)
    }

    @Synchronized
    private fun onCommandResponse(response: ByteArray) {
        LimeLog.info("${driverName()}: Command response received (${response.size} bytes)")
        awaitingResponse = false
        if (commandQueue.isNotEmpty()) {
            Handler(Looper.getMainLooper()).postDelayed({ sendNextCommand() }, 50L)
        }
    }

    @Synchronized
    private fun onCommandTimeout(sequence: Int) {
        if (awaitingResponse && sequence == commandSequence) {
            LimeLog.warning("${driverName()}: No command response received, advancing queue...")
            awaitingResponse = false
            sendNextCommand()
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            LimeLog.info("${driverName()}: onConnectionStateChange status=$status newState=$newState")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    LimeLog.info("${driverName()}: Connected. Requesting MTU 512...")
                    if (!gatt.requestMtu(512)) {
                        LimeLog.warning("${driverName()}: requestMtu failed, discovering services anyway...")
                        gatt.discoverServices()
                    }
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    LimeLog.info("${driverName()}: Disconnected.")
                    stop()
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            LimeLog.info("${driverName()}: MTU changed to $mtu (status $status). Discovering services...")
            gatt.discoverServices()
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return

            LimeLog.info("${driverName()}: Services discovered.")
            for (service in gatt.services) {
                LimeLog.info("${driverName()}: Service: ${service.uuid}")
                for (characteristic in service.characteristics) {
                    LimeLog.info("${driverName()}:   Char: ${characteristic.uuid} props=${characteristic.properties}")
                    when (characteristic.uuid) {
                        COMMAND_WRITE_UUID -> writeCharacteristic = characteristic
                        INPUT_REPORT_UUID -> notifyCharacteristic = characteristic
                        COMMAND_RESPONSE_UUID -> responseCharacteristic = characteristic
                        VIBRATION_WRITE_PRO_CONTROLLER_UUID,
                        VIBRATION_WRITE_JOYCON_L_UUID,
                        VIBRATION_WRITE_JOYCON_R_UUID -> vibrationCharacteristic = characteristic
                    }
                }
            }

            if (writeCharacteristic == null || notifyCharacteristic == null || responseCharacteristic == null) {
                LimeLog.warning("${driverName()}: Missing required characteristics!")
                return
            }
            if (vibrationCharacteristic == null) {
                LimeLog.warning("${driverName()}: Missing vibration characteristic; rumble will be unavailable")
            }

            Handler(Looper.getMainLooper()).postDelayed({ startNotificationSetup(gatt) }, 2000L)
        }

        @SuppressLint("MissingPermission")
        private fun startNotificationSetup(gatt: BluetoothGatt) {
            descriptorsSetup = 0

            val response = responseCharacteristic ?: return
            gatt.setCharacteristicNotification(response, true)
            val descriptor = response.getDescriptor(CCCD_UUID)
            if (descriptor != null) {
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(descriptor)
                LimeLog.info("${driverName()}: Enabling COMMAND_RESPONSE notifications...")
            } else {
                enableInputReportNotification(gatt)
            }
        }

        @SuppressLint("MissingPermission")
        private fun enableInputReportNotification(gatt: BluetoothGatt) {
            val input = notifyCharacteristic ?: return
            gatt.setCharacteristicNotification(input, true)
            val descriptor = input.getDescriptor(CCCD_UUID)
            if (descriptor != null) {
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(descriptor)
                LimeLog.info("${driverName()}: Enabling INPUT_REPORT notifications...")
            } else {
                onAllNotificationsEnabled()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            LimeLog.info("${driverName()}: Descriptor written for ${descriptor.characteristic.uuid} status=$status")
            descriptorsSetup++
            if (descriptorsSetup == 1) {
                enableInputReportNotification(gatt)
            } else if (descriptorsSetup >= 2) {
                onAllNotificationsEnabled()
            }
        }

        private fun onAllNotificationsEnabled() {
            LimeLog.info("${driverName()}: All notifications enabled. Sending init sequence...")
            notifyDeviceAdded()
            sendInitSequence()
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            LimeLog.info("${driverName()}: Write complete for ${characteristic.uuid} status=$status")
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            handleCharacteristicChanged(characteristic.uuid, characteristic.value ?: return)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            handleCharacteristicChanged(characteristic.uuid, value)
        }

        private fun handleCharacteristicChanged(uuid: UUID, data: ByteArray) {
            when (uuid) {
                COMMAND_RESPONSE_UUID -> onCommandResponse(data)
                INPUT_REPORT_UUID -> {
                    if (inputReportLogCount < 5) {
                        LimeLog.info("${driverName()}: Input report received (${data.size} bytes): ${data.toHexPreview()}")
                        inputReportLogCount++
                    }
                    logRawReportDelta(data)
                    if (handleRead(ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN))) {
                        reportInput()
                        reportMotion()
                    }
                }
            }
        }
    }

    private fun sendInitSequence() {
        val setMacPayload = byteArrayOf(
            0x00, 0x02, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        )
        enqueueCommand(COMMAND_PAIR, SUBCOMMAND_PAIR_SET_MAC, setMacPayload)

        val ltk1 = byteArrayOf(
            0x00, 0xea.toByte(), 0xbd.toByte(), 0x47, 0x13, 0x89.toByte(), 0x35, 0x42,
            0xc6.toByte(), 0x79, 0xee.toByte(), 0x07, 0xf2.toByte(), 0x53, 0x2c, 0x6c, 0x31,
        )
        enqueueCommand(COMMAND_PAIR, SUBCOMMAND_PAIR_LTK1, ltk1)

        val ltk2 = byteArrayOf(
            0x00, 0x40, 0xb0.toByte(), 0x8a.toByte(), 0x5f, 0xcd.toByte(), 0x1f, 0x9b.toByte(),
            0x41, 0x12, 0x5c, 0xac.toByte(), 0xc6.toByte(), 0x3f, 0x38, 0xa0.toByte(), 0x73,
        )
        enqueueCommand(COMMAND_PAIR, SUBCOMMAND_PAIR_LTK2, ltk2)

        enqueueCommand(COMMAND_PAIR, SUBCOMMAND_PAIR_FINISH, byteArrayOf(0x00))

        val featureFlags = byteArrayOf((FEATURE_FLAGS and 0xff).toByte(), 0x00, 0x00, 0x00)
        enqueueCommand(COMMAND_FEATURE, SUBCOMMAND_FEATURE_INIT, featureFlags)
        enqueueCommand(COMMAND_FEATURE, SUBCOMMAND_FEATURE_ENABLE, featureFlags)

        enqueueCommand(COMMAND_LEDS, SUBCOMMAND_LEDS_SET_PLAYER, byteArrayOf(LED_PLAYER_1.toByte(), 0x00, 0x00, 0x00))
    }

    private fun handleRead(buf: ByteBuffer): Boolean {
        if (buf.limit() < 16) return false

        val buttons = buf.getInt(4)

        buttonFlags = Switch2ControllerMappings.mapButtons(context, address, buttons)

        leftTrigger = if ((buttons and 0x00800000) != 0) 1f else 0f
        rightTrigger = if ((buttons and 0x00000080) != 0) 1f else 0f

        val lsRaw = readStick24(buf, 10)
        val rsRaw = readStick24(buf, 13)
        if (Switch2ControllerMappings.isJoyConRight(productId)) {
            leftStickX = 0f
            leftStickY = 0f
            rightStickX = stickAxisX(rsRaw)
            rightStickY = stickAxisY(rsRaw)
        } else if (Switch2ControllerMappings.isJoyConLeft(productId)) {
            leftStickX = stickAxisX(lsRaw)
            leftStickY = stickAxisY(lsRaw)
            rightStickX = 0f
            rightStickY = 0f
        } else {
            leftStickX = stickAxisX(lsRaw)
            leftStickY = stickAxisY(lsRaw)
            rightStickX = stickAxisX(rsRaw)
            rightStickY = stickAxisY(rsRaw)
        }

        logRawButtonChanges(buttons)
        logButtonChanges(buttons, buttonFlags)
        logAnalogChanges()

        if (decodedInputLogCount < 20 && (buttons != lastDecodedButtons || buttons != 0)) {
            LimeLog.info(
                "${driverName()}: decoded buttons=0x${buttons.toUInt().toString(16)} " +
                    "mapped=0x${buttonFlags.toUInt().toString(16)} " +
                    "ls=($leftStickX,$leftStickY) rs=($rightStickX,$rightStickY) " +
                    "lt=$leftTrigger rt=$rightTrigger",
            )
            decodedInputLogCount++
            lastDecodedButtons = buttons
        }

        if (buf.limit() >= 60) {
            accelX = buf.getShort(48) / 4096.0f
            accelY = buf.getShort(50) / 4096.0f
            accelZ = buf.getShort(52) / 4096.0f
            gyroX = buf.getShort(54) / 16.0f
            gyroZ = buf.getShort(56) / 16.0f
            gyroY = -buf.getShort(58) / 16.0f
        }
        return true
    }

    private fun logRawReportDelta(data: ByteArray) {
        val previous = lastInputReport
        lastInputReport = data.copyOf()

        if (previous == null || rawReportDeltaLogCount >= RAW_REPORT_DELTA_LOG_LIMIT) return

        val maxSize = maxOf(previous.size, data.size)
        val deltas = ArrayList<String>()
        for (i in 0 until maxSize) {
            val oldValue = previous.getOrNull(i)?.toInt()?.and(0xff)
            val newValue = data.getOrNull(i)?.toInt()?.and(0xff)
            if (oldValue != newValue) {
                deltas.add("${i}:${oldValue?.toHexByte() ?: "--"}>${newValue?.toHexByte() ?: "--"}")
            }
        }

        if (deltas.isEmpty()) return

        val message = "report-delta size=${data.size} changed=${deltas.take(20).joinToString(",")}" +
            if (deltas.size > 20) ",..." else ""
        Log.i(LOG_TAG_INPUT, message)
        LimeLog.info("${driverName()}: $message")
        rawReportDeltaLogCount++
    }

    private fun logRawButtonChanges(rawButtons: Int) {
        val changed = rawButtons xor lastRawButtons
        if (changed == 0) return

        val pressed = rawButtonNamesFor(rawButtons and changed)
        val released = rawButtonNamesFor(lastRawButtons and changed)
        val message = buildString {
            append("raw-buttons changed=0x")
            append(changed.toUInt().toString(16))
            append(" raw=0x")
            append(rawButtons.toUInt().toString(16))
            if (pressed.isNotEmpty()) {
                append(" pressed=")
                append(pressed.joinToString("+"))
            }
            if (released.isNotEmpty()) {
                append(" released=")
                append(released.joinToString("+"))
            }
        }
        Log.i(LOG_TAG_INPUT, message)
        LimeLog.info("${driverName()}: $message")
        lastRawButtons = rawButtons
    }

    private fun logButtonChanges(rawButtons: Int, mappedButtons: Int) {
        val changed = mappedButtons xor lastReportedButtonFlags
        if (changed == 0) return

        val pressed = buttonNamesFor(mappedButtons and changed)
        val released = buttonNamesFor(lastReportedButtonFlags and changed)
        val message = buildString {
            append("raw=0x")
            append(rawButtons.toUInt().toString(16))
            append(" mapped=0x")
            append(mappedButtons.toUInt().toString(16))
            if (pressed.isNotEmpty()) {
                append(" pressed=")
                append(pressed.joinToString("+"))
            }
            if (released.isNotEmpty()) {
                append(" released=")
                append(released.joinToString("+"))
            }
        }

        Log.i(LOG_TAG_INPUT, message)
        LimeLog.info("${driverName()}: button change $message")
        lastReportedButtonFlags = mappedButtons
    }

    private fun logAnalogChanges() {
        val triggerChanged = triggerChanged(leftTrigger, lastLoggedLeftTrigger) ||
            triggerChanged(rightTrigger, lastLoggedRightTrigger)
        if (triggerChanged) {
            val message = "triggers ZL=$leftTrigger ZR=$rightTrigger"
            Log.i(LOG_TAG_INPUT, message)
            LimeLog.info("${driverName()}: $message")
            lastLoggedLeftTrigger = leftTrigger
            lastLoggedRightTrigger = rightTrigger
        }

        val leftStickChanged = stickChanged(leftStickX, lastLoggedLeftStickX) ||
            stickChanged(leftStickY, lastLoggedLeftStickY)
        val rightStickChanged = stickChanged(rightStickX, lastLoggedRightStickX) ||
            stickChanged(rightStickY, lastLoggedRightStickY)
        if (leftStickChanged || rightStickChanged) {
            val message = "sticks LS=(${leftStickX.formatAxis()},${leftStickY.formatAxis()}) " +
                "RS=(${rightStickX.formatAxis()},${rightStickY.formatAxis()})"
            Log.i(LOG_TAG_INPUT, message)
            LimeLog.info("${driverName()}: $message")
            lastLoggedLeftStickX = leftStickX
            lastLoggedLeftStickY = leftStickY
            lastLoggedRightStickX = rightStickX
            lastLoggedRightStickY = rightStickY
        }
    }

    private fun triggerChanged(current: Float, previous: Float): Boolean {
        return previous < 0f || kotlin.math.abs(current - previous) >= TRIGGER_LOG_THRESHOLD
    }

    private fun stickChanged(current: Float, previous: Float): Boolean {
        return previous.isNaN() || kotlin.math.abs(current - previous) >= STICK_LOG_THRESHOLD
    }

    private fun buttonNamesFor(flags: Int): List<String> {
        return BUTTON_LOG_NAMES.mapNotNull { (flag, name) ->
            if ((flags and flag) != 0) name else null
        }
    }

    private fun rawButtonNamesFor(flags: Int): List<String> {
        return RAW_BUTTON_LOG_NAMES.mapNotNull { (flag, name) ->
            if ((flags and flag) != 0) name else null
        }.ifEmpty {
            listOf("UNKNOWN_BITS_0x${flags.toUInt().toString(16)}")
        }
    }

    private fun readStick24(buf: ByteBuffer, offset: Int): Int {
        return (buf.get(offset).toInt() and 0xff) or
            ((buf.get(offset + 1).toInt() and 0xff) shl 8) or
            ((buf.get(offset + 2).toInt() and 0xff) shl 16)
    }

    private fun stickAxisX(raw: Int): Float = ((raw and 0xfff) - 2048) / 2048.0f

    private fun stickAxisY(raw: Int): Float = -(((raw shr 12) and 0xfff) - 2048) / 2048.0f

    private fun driverName(): String {
        return "Switch2BleDriver(${Switch2ControllerMappings.controllerNameForProduct(productId)})"
    }

    private fun buildVibrationFrame(lowAmplitude: Int, highAmplitude: Int): ByteArray {
        var value = 0L
        value = value or (DEFAULT_LOW_FREQUENCY.toLong() and 0x1ff)
        value = value or ((lowAmplitude.coerceIn(0, 0x3ff).toLong() and 0x3ff) shl 10)
        value = value or ((DEFAULT_HIGH_FREQUENCY.toLong() and 0x1ff) shl 20)
        value = value or ((highAmplitude.coerceIn(0, 0x3ff).toLong() and 0x3ff) shl 30)

        return ByteArray(5) { index ->
            ((value shr (index * 8)) and 0xff).toByte()
        }
    }

    private fun ByteArray.toHexPreview(maxBytes: Int = 32): String {
        return take(maxBytes).joinToString(separator = " ") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    }

    private fun Int.toHexByte(): String = toString(16).padStart(2, '0')

    private fun Float.formatAxis(): String = "%.3f".format(java.util.Locale.US, this)

    companion object {
        private const val LOG_TAG_INPUT = "ProConBleInput"
        private const val RAW_REPORT_DELTA_LOG_LIMIT = 160
        private const val STICK_LOG_THRESHOLD = 0.08f
        private const val TRIGGER_LOG_THRESHOLD = 0.05f
        private val INPUT_REPORT_UUID: UUID = UUID.fromString("ab7de9be-89fe-49ad-828f-118f09df7fd2")
        private val VIBRATION_WRITE_JOYCON_R_UUID: UUID = UUID.fromString("fa19b0fb-cd1f-46a7-84a1-bbb09e00c149")
        private val VIBRATION_WRITE_JOYCON_L_UUID: UUID = UUID.fromString("289326cb-a471-485d-a8f4-240c14f18241")
        private val VIBRATION_WRITE_PRO_CONTROLLER_UUID: UUID = UUID.fromString("cc483f51-9258-427d-a939-630c31f72b05")
        private val COMMAND_WRITE_UUID: UUID = UUID.fromString("649d4ac9-8eb7-4e6c-af44-1ea54fe5f005")
        private val COMMAND_RESPONSE_UUID: UUID = UUID.fromString("c765a961-d9d8-4d36-a20a-5315b111836a")
        private const val DEFAULT_LOW_FREQUENCY = 0x0e1
        private const val DEFAULT_HIGH_FREQUENCY = 0x1e1
        private const val MAX_SWITCH_RUMBLE_AMPLITUDE = 800

        private const val COMMAND_LEDS = 0x09
        private const val SUBCOMMAND_LEDS_SET_PLAYER = 0x07
        private const val COMMAND_FEATURE = 0x0c
        private const val SUBCOMMAND_FEATURE_INIT = 0x02
        private const val SUBCOMMAND_FEATURE_ENABLE = 0x04
        private const val COMMAND_PAIR = 0x15
        private const val SUBCOMMAND_PAIR_SET_MAC = 0x01
        private const val SUBCOMMAND_PAIR_LTK1 = 0x04
        private const val SUBCOMMAND_PAIR_LTK2 = 0x02
        private const val SUBCOMMAND_PAIR_FINISH = 0x03
        private const val FEATURE_FLAGS = 0x04 or 0x10 or 0x80
        private const val LED_PLAYER_1 = 0x01
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val USB_VENDOR_NINTENDO = 0x057e
        private val BUTTON_LOG_NAMES = listOf(
            ControllerPacket.A_FLAG to "A",
            ControllerPacket.B_FLAG to "B",
            ControllerPacket.X_FLAG to "X",
            ControllerPacket.Y_FLAG to "Y",
            ControllerPacket.UP_FLAG to "DPAD_UP",
            ControllerPacket.DOWN_FLAG to "DPAD_DOWN",
            ControllerPacket.LEFT_FLAG to "DPAD_LEFT",
            ControllerPacket.RIGHT_FLAG to "DPAD_RIGHT",
            ControllerPacket.LB_FLAG to "L",
            ControllerPacket.RB_FLAG to "R",
            ControllerPacket.LS_CLK_FLAG to "L_STICK",
            ControllerPacket.RS_CLK_FLAG to "R_STICK",
            ControllerPacket.BACK_FLAG to "MINUS",
            ControllerPacket.PLAY_FLAG to "PLUS",
            ControllerPacket.SPECIAL_BUTTON_FLAG to "HOME",
            ControllerPacket.TOUCHPAD_FLAG to "TOUCHPAD",
            ControllerPacket.MISC_FLAG to "CAPTURE",
            ControllerPacket.PADDLE1_FLAG to "PADDLE1",
            ControllerPacket.PADDLE2_FLAG to "PADDLE2",
            ControllerPacket.PADDLE3_FLAG to "PADDLE3",
            ControllerPacket.PADDLE4_FLAG to "PADDLE4",
        )
        private val RAW_BUTTON_LOG_NAMES = listOf(
            0x00000008 to "A",
            0x00000004 to "B",
            0x00000002 to "X",
            0x00000001 to "Y",
            0x00400000 to "L",
            0x00000040 to "R",
            0x00800000 to "ZL",
            0x00000080 to "ZR",
            0x00000100 to "MINUS",
            0x00000200 to "PLUS",
            0x00000800 to "L_STICK",
            0x00000400 to "R_STICK",
            0x00001000 to "HOME",
            0x00002000 to "CAPTURE",
            0x00004000 to "GAMECHAT",
            0x00020000 to "DPAD_UP",
            0x00010000 to "DPAD_DOWN",
            0x00080000 to "DPAD_LEFT",
            0x00040000 to "DPAD_RIGHT",
            0x00200000 to "SL_L",
            0x00100000 to "SR_L",
            0x00000020 to "SL_R",
            0x00000010 to "SR_R",
            0x02000000 to "GL",
            0x01000000 to "GR",
        )
    }
}
