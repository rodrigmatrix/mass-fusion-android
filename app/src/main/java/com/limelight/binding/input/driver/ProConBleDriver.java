package com.limelight.binding.input.driver;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.limelight.LimeLog;
import com.limelight.nvstream.input.ControllerPacket;
import com.limelight.nvstream.jni.MoonBridge;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.LinkedList;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;

public class ProConBleDriver extends AbstractController {

    private final BluetoothDevice device;
    private final Context context;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic writeCharacteristic;
    private BluetoothGattCharacteristic notifyCharacteristic;
    private BluetoothGattCharacteristic responseCharacteristic;
    private boolean stopped = false;

    private final int[][][] stickCalibration = new int[2][2][3];
    private final float[][][] stickExtends = new float[2][2][2];

    // Custom GATT Protocol UUIDs (matching switch2-controllers-windows10-gyro)
    private static final UUID INPUT_REPORT_UUID    = UUID.fromString("ab7de9be-89fe-49ad-828f-118f09df7fd2");
    private static final UUID COMMAND_WRITE_UUID   = UUID.fromString("649d4ac9-8eb7-4e6c-af44-1ea54fe5f005");
    private static final UUID COMMAND_RESPONSE_UUID = UUID.fromString("c765a961-d9d8-4d36-a20a-5315b111836a");

    // Commands (matching controller.py)
    private static final int COMMAND_LEDS              = 0x09;
    private static final int SUBCOMMAND_LEDS_SET_PLAYER = 0x07;
    private static final int COMMAND_FEATURE           = 0x0c;
    private static final int SUBCOMMAND_FEATURE_INIT   = 0x02;
    private static final int SUBCOMMAND_FEATURE_ENABLE = 0x04;
    private static final int COMMAND_PAIR              = 0x15;
    private static final int SUBCOMMAND_PAIR_SET_MAC   = 0x01;
    private static final int SUBCOMMAND_PAIR_LTK1      = 0x04;
    private static final int SUBCOMMAND_PAIR_LTK2      = 0x02;
    private static final int SUBCOMMAND_PAIR_FINISH    = 0x03;

    // Feature flags (FEATURE_MOTION | FEATURE_MOUSE | FEATURE_MAGNOMETER)
    private static final int FEATURE_FLAGS = 0x04 | 0x10 | 0x80; // = 0x94

    // LED pattern for Player 1
    private static final int LED_PLAYER_1 = 0x01;

    // CCCD UUID
    private static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    // Response-driven command sequencing state
    // Each pending command is a byte[] to write to COMMAND_WRITE_UUID
    private final Queue<byte[]> commandQueue = new LinkedList<>();
    private boolean awaitingResponse = false;
    private boolean descriptorSetupDone = false;
    private int descriptorsToSetup = 0;
    private int descriptorsSetup = 0;

    public ProConBleDriver(Context context, BluetoothDevice device, int deviceId, UsbDriverListener listener) {
        super(deviceId, listener, 0x057e, 0x2009);
        this.context = context;
        this.device = device;
        this.type = MoonBridge.LI_CTYPE_NINTENDO;
        this.capabilities = MoonBridge.LI_CCAP_GYRO | MoonBridge.LI_CCAP_ACCEL | MoonBridge.LI_CCAP_RUMBLE;
        applyDefaultCalibration(0);
        applyDefaultCalibration(1);
    }

    @SuppressLint("MissingPermission")
    @Override
    public boolean start() {
        if (device == null) return false;
        LimeLog.info("ProConBleDriver: Connecting to GATT server...");
        gatt = device.connectGatt(context, false, gattCallback);
        return gatt != null;
    }

    @SuppressLint("MissingPermission")
    @Override
    public void stop() {
        if (stopped) return;
        stopped = true;
        if (gatt != null) {
            gatt.disconnect();
            gatt.close();
            gatt = null;
        }
        notifyDeviceRemoved();
    }

    @Override
    public void rumble(short lowFreqMotor, short highFreqMotor) {
        // TODO: implement using VIBRATION_WRITE_PRO_CONTROLLER_UUID
    }

    @Override
    public void rumbleTriggers(short leftTrigger, short rightTrigger) {}

    // Build a command packet matching Python's write_command:
    // [commandId] [0x91] [0x01] [subcommandId] [0x00] [len(payload)] [0x00] [0x00] [payload...]
    private byte[] buildCommand(int commandId, int subcommandId, byte[] payload) {
        byte[] data = new byte[8 + payload.length];
        data[0] = (byte) commandId;
        data[1] = (byte) 0x91;
        data[2] = (byte) 0x01;
        data[3] = (byte) subcommandId;
        data[4] = (byte) 0x00;
        data[5] = (byte) payload.length;
        data[6] = (byte) 0x00;
        data[7] = (byte) 0x00;
        System.arraycopy(payload, 0, data, 8, payload.length);
        return data;
    }

    // Enqueue a command. Will only be sent once the previous one gets a response.
    private synchronized void enqueueCommand(int commandId, int subcommandId, byte[] payload) {
        commandQueue.add(buildCommand(commandId, subcommandId, payload));
        if (!awaitingResponse) {
            sendNextCommand();
        }
    }

    @SuppressLint("MissingPermission")
    private synchronized void sendNextCommand() {
        if (commandQueue.isEmpty() || writeCharacteristic == null || gatt == null) {
            awaitingResponse = false;
            return;
        }
        byte[] next = commandQueue.poll();
        awaitingResponse = true;
        writeCharacteristic.setValue(next);
        gatt.writeCharacteristic(writeCharacteristic);
    }

    // Called when COMMAND_RESPONSE_UUID notification fires — move to next command
    private synchronized void onCommandResponse(byte[] response) {
        LimeLog.info("ProConBleDriver: Command response received (" + response.length + " bytes)");
        // Don't validate response strictly; just proceed to the next command
        awaitingResponse = false;
        if (!commandQueue.isEmpty()) {
            // Small delay between commands, like Python does implicitly via async scheduling
            new Handler(Looper.getMainLooper()).postDelayed(this::sendNextCommand, 50);
        }
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                LimeLog.info("ProConBleDriver: Connected. Discovering services...");
                gatt.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                LimeLog.info("ProConBleDriver: Disconnected.");
                stop();
            }
        }

        @SuppressLint("MissingPermission")
        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) return;

            LimeLog.info("ProConBleDriver: Services discovered.");
            for (BluetoothGattService service : gatt.getServices()) {
                LimeLog.info("ProConBleDriver: Service: " + service.getUuid());
                for (BluetoothGattCharacteristic c : service.getCharacteristics()) {
                    LimeLog.info("ProConBleDriver:   Char: " + c.getUuid() + " props=" + c.getProperties());
                    if (c.getUuid().equals(COMMAND_WRITE_UUID))    writeCharacteristic = c;
                    if (c.getUuid().equals(INPUT_REPORT_UUID))     notifyCharacteristic = c;
                    if (c.getUuid().equals(COMMAND_RESPONSE_UUID)) responseCharacteristic = c;
                }
            }

            if (writeCharacteristic == null || notifyCharacteristic == null || responseCharacteristic == null) {
                LimeLog.warning("ProConBleDriver: Missing required characteristics!");
                return;
            }

            // Wait 2 seconds (same as Python's asyncio.sleep(2.0)) then set up notifications
            new Handler(Looper.getMainLooper()).postDelayed(() -> startNotificationSetup(gatt), 2000);
        }

        @SuppressLint("MissingPermission")
        private void startNotificationSetup(BluetoothGatt gatt) {
            // We need to set up CCCD for COMMAND_RESPONSE_UUID first (like Python does),
            // then INPUT_REPORT_UUID. We do them sequentially using onDescriptorWrite callback.
            descriptorsToSetup = 2;
            descriptorsSetup = 0;
            descriptorSetupDone = false;

            // Set up COMMAND_RESPONSE_UUID notifications first (Python does this before send any commands)
            gatt.setCharacteristicNotification(responseCharacteristic, true);
            BluetoothGattDescriptor desc = responseCharacteristic.getDescriptor(CCCD_UUID);
            if (desc != null) {
                desc.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                gatt.writeDescriptor(desc);
                LimeLog.info("ProConBleDriver: Enabling COMMAND_RESPONSE notifications...");
            } else {
                // No CCCD, try enabling INPUT_REPORT directly
                enableInputReportNotification(gatt);
            }
        }

        @SuppressLint("MissingPermission")
        private void enableInputReportNotification(BluetoothGatt gatt) {
            gatt.setCharacteristicNotification(notifyCharacteristic, true);
            BluetoothGattDescriptor desc = notifyCharacteristic.getDescriptor(CCCD_UUID);
            if (desc != null) {
                desc.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                gatt.writeDescriptor(desc);
                LimeLog.info("ProConBleDriver: Enabling INPUT_REPORT notifications...");
            } else {
                onAllNotificationsEnabled();
            }
        }

        @SuppressLint("MissingPermission")
        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            LimeLog.info("ProConBleDriver: Descriptor written for " + descriptor.getCharacteristic().getUuid() + " status=" + status);
            descriptorsSetup++;
            if (descriptorsSetup == 1) {
                // First one done (COMMAND_RESPONSE), now enable INPUT_REPORT
                enableInputReportNotification(gatt);
            } else if (descriptorsSetup >= 2) {
                // Both done
                onAllNotificationsEnabled();
            }
        }

        private void onAllNotificationsEnabled() {
            LimeLog.info("ProConBleDriver: All notifications enabled. Sending init sequence...");
            notifyDeviceAdded();
            sendInitSequence();
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            LimeLog.info("ProConBleDriver: Write complete for " + characteristic.getUuid() + " status=" + status);
            // Don't advance the queue here; wait for the response notification instead.
            // If no response comes within 1 second, time out and send the next command.
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                synchronized (ProConBleDriver.this) {
                    if (awaitingResponse && !commandQueue.isEmpty()) {
                        LimeLog.warning("ProConBleDriver: No response received, advancing queue...");
                        awaitingResponse = false;
                        sendNextCommand();
                    }
                }
            }, 1000);
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            byte[] data = characteristic.getValue();
            if (data == null) return;

            if (characteristic.getUuid().equals(COMMAND_RESPONSE_UUID)) {
                onCommandResponse(data);
            } else if (characteristic.getUuid().equals(INPUT_REPORT_UUID)) {
                if (handleRead(ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN))) {
                    reportInput();
                    reportMotion();
                }
            }
        }
    };

    private void sendInitSequence() {
        // --- Pairing sequence (controller.py pair()) ---
        // PAIR_SET_MAC: [0x00, 0x02, mac*2] (we use a dummy all-zeros MAC)
        byte[] setMacPayload = new byte[]{0x00, 0x02, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                                          0x00, 0x00, 0x00, 0x00, 0x00, 0x00};
        enqueueCommand(COMMAND_PAIR, SUBCOMMAND_PAIR_SET_MAC, setMacPayload);

        byte[] ltk1 = new byte[]{0x00, (byte)0xea, (byte)0xbd, 0x47, 0x13, (byte)0x89, 0x35, 0x42,
                                  (byte)0xc6, 0x79, (byte)0xee, 0x07, (byte)0xf2, 0x53, 0x2c, 0x6c, 0x31};
        enqueueCommand(COMMAND_PAIR, SUBCOMMAND_PAIR_LTK1, ltk1);

        byte[] ltk2 = new byte[]{0x00, 0x40, (byte)0xb0, (byte)0x8a, 0x5f, (byte)0xcd, 0x1f, (byte)0x9b,
                                  0x41, 0x12, 0x5c, (byte)0xac, (byte)0xc6, 0x3f, 0x38, (byte)0xa0, 0x73};
        enqueueCommand(COMMAND_PAIR, SUBCOMMAND_PAIR_LTK2, ltk2);

        enqueueCommand(COMMAND_PAIR, SUBCOMMAND_PAIR_FINISH, new byte[]{0x00});

        // --- enableFeatures(FEATURE_MOTION | FEATURE_MOUSE | FEATURE_MAGNOMETER) ---
        byte[] featureFlags = new byte[]{(byte)(FEATURE_FLAGS & 0xFF), 0x00, 0x00, 0x00};
        enqueueCommand(COMMAND_FEATURE, SUBCOMMAND_FEATURE_INIT,   featureFlags);
        enqueueCommand(COMMAND_FEATURE, SUBCOMMAND_FEATURE_ENABLE, featureFlags);

        // --- set_leds(player_number=1) ---
        // LED_PATTERN[1] = 0x01, padded to 4 bytes
        enqueueCommand(COMMAND_LEDS, SUBCOMMAND_LEDS_SET_PLAYER, new byte[]{(byte)LED_PLAYER_1, 0x00, 0x00, 0x00});
    }

    private boolean handleRead(ByteBuffer buffer) {
        if (buffer.remaining() < 12) return false;

        byte reportId = buffer.get(0);
        if (reportId == 0x30 || reportId == 0x21 || reportId == 0x31 || reportId == 0x32 || reportId == 0x33) {
            buttonFlags = 0;
            setButtonFlag(ControllerPacket.B_FLAG,            buffer.get(3) & 0x08);
            setButtonFlag(ControllerPacket.A_FLAG,            buffer.get(3) & 0x04);
            setButtonFlag(ControllerPacket.Y_FLAG,            buffer.get(3) & 0x02);
            setButtonFlag(ControllerPacket.X_FLAG,            buffer.get(3) & 0x01);
            setButtonFlag(ControllerPacket.UP_FLAG,           buffer.get(5) & 0x02);
            setButtonFlag(ControllerPacket.DOWN_FLAG,         buffer.get(5) & 0x01);
            setButtonFlag(ControllerPacket.LEFT_FLAG,         buffer.get(5) & 0x08);
            setButtonFlag(ControllerPacket.RIGHT_FLAG,        buffer.get(5) & 0x04);
            setButtonFlag(ControllerPacket.BACK_FLAG,         buffer.get(4) & 0x01);
            setButtonFlag(ControllerPacket.PLAY_FLAG,         buffer.get(4) & 0x02);
            setButtonFlag(ControllerPacket.MISC_FLAG,         buffer.get(4) & 0x20);
            setButtonFlag(ControllerPacket.SPECIAL_BUTTON_FLAG, buffer.get(4) & 0x10);
            setButtonFlag(ControllerPacket.LB_FLAG,           buffer.get(5) & 0x40);
            setButtonFlag(ControllerPacket.RB_FLAG,           buffer.get(3) & 0x40);
            setButtonFlag(ControllerPacket.LS_CLK_FLAG,       buffer.get(4) & 0x08);
            setButtonFlag(ControllerPacket.RS_CLK_FLAG,       buffer.get(4) & 0x04);

            leftTrigger  = ((buffer.get(5) & 0x80) != 0) ? 1 : 0;
            rightTrigger = ((buffer.get(3) & 0x80) != 0) ? 1 : 0;

            int _leftStickX  = buffer.get(6) & 0xFF | ((buffer.get(7) & 0x0F) << 8);
            int _leftStickY  = ((buffer.get(7) & 0xF0) >> 4) | (buffer.get(8) << 4);
            int _rightStickX = buffer.get(9) & 0xFF | ((buffer.get(10) & 0x0F) << 8);
            int _rightStickY = ((buffer.get(10) & 0xF0) >> 4) | (buffer.get(11) << 4);

            leftStickX  = applyStickCalibration(_leftStickX,   0, 0);
            leftStickY  = applyStickCalibration(-_leftStickY-1, 0, 1);
            rightStickX = applyStickCalibration(_rightStickX,   1, 0);
            rightStickY = applyStickCalibration(-_rightStickY-1, 1, 1);

            if (buffer.remaining() >= 49 && reportId == 0x30) {
                accelX = buffer.getShort(37) / 4096.0f;
                accelY = buffer.getShort(39) / 4096.0f;
                accelZ = buffer.getShort(41) / 4096.0f;
                gyroZ  = -buffer.getShort(43) / 16.0f;
                gyroX  = -buffer.getShort(45) / 16.0f;
                gyroY  =  buffer.getShort(47) / 16.0f;
            }
            return true;
        }
        return false;
    }

    private void applyDefaultCalibration(int stick) {
        for (int axis = 0; axis < 2; axis++) {
            stickCalibration[stick][axis][0] = 0x000;
            stickCalibration[stick][axis][1] = 0x800;
            stickCalibration[stick][axis][2] = 0xFFF;
            stickExtends[stick][axis][0] = -0x700;
            stickExtends[stick][axis][1] = 0x700;
        }
    }

    private float applyStickCalibration(int value, int stick, int axis) {
        int center = stickCalibration[stick][axis][1];
        if (value < 0) value += 0x1000;
        value -= center;
        if (value < stickExtends[stick][axis][0]) { stickExtends[stick][axis][0] = value; return -1; }
        if (value > stickExtends[stick][axis][1]) { stickExtends[stick][axis][1] = value; return  1; }
        if (value > 0) return value / stickExtends[stick][axis][1];
        else           return -value / stickExtends[stick][axis][0];
    }
}
