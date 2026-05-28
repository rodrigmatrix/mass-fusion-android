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

public class ProConBleDriver extends AbstractController {

    private static final int PACKET_SIZE = 64;
    private final BluetoothDevice device;
    private final Context context;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic writeCharacteristic;
    private BluetoothGattCharacteristic notifyCharacteristic;
    private boolean stopped = false;
    private byte sendPacketCount = 0;

    private final int[][][] stickCalibration = new int[2][2][3];
    private final float[][][] stickExtends = new float[2][2][2];

    private final Queue<byte[]> writeQueue = new LinkedList<>();
    private boolean isWriting = false;

    // Standard Client Characteristic Configuration Descriptor UUID
    private static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

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
        if (writeCharacteristic == null) return;
        byte[] data = new byte[10];
        data[0] = 0x10;
        data[1] = sendPacketCount++;
        if (sendPacketCount > 0xF) sendPacketCount = 0;

        if (lowFreqMotor != 0) {
            data[4] = data[8] = (byte)(0x50 - (lowFreqMotor & 0xFFFF >> 12));
            data[5] = data[9] = (byte)((((lowFreqMotor & 0xFFFF) >> 8) / 5) + 0x40);
        }
        if (highFreqMotor != 0) {
            data[6] = (byte)((0x70 - ((highFreqMotor & 0xFFFF) >> 10) & -0x04));
            data[7] = (byte)(((highFreqMotor & 0xFFFF) >> 8) * 0xC8 / 0xFF);
        }

        data[2] |= 0x00;
        data[3] |= 0x01;
        data[5] |= 0x40;
        data[6] |= 0x00;
        data[7] |= 0x01;
        data[9] |= 0x40;

        queueWrite(data);
    }

    @Override
    public void rumbleTriggers(short leftTrigger, short rightTrigger) {
    }

    private void queueWrite(byte[] data) {
        synchronized (writeQueue) {
            writeQueue.add(data);
            processWriteQueue();
        }
    }

    @SuppressLint("MissingPermission")
    private void processWriteQueue() {
        synchronized (writeQueue) {
            if (isWriting || writeQueue.isEmpty() || writeCharacteristic == null || gatt == null) {
                return;
            }
            byte[] data = writeQueue.poll();
            if (data != null) {
                isWriting = true;
                writeCharacteristic.setValue(data);
                gatt.writeCharacteristic(writeCharacteristic);
            }
        }
    }

    private void sendSubcommand(byte subcommand, byte[] payload) {
        byte[] data = new byte[11 + payload.length];
        data[0] = 0x01;
        data[1] = sendPacketCount++;
        if (sendPacketCount > 0xF) sendPacketCount = 0;

        data[10] = subcommand;
        System.arraycopy(payload, 0, data, 11, payload.length);
        queueWrite(data);
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                LimeLog.info("ProConBleDriver: Connected to GATT server. Discovering services...");
                gatt.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                LimeLog.info("ProConBleDriver: Disconnected from GATT server.");
                stop();
            }
        }

        @SuppressLint("MissingPermission")
        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                LimeLog.info("ProConBleDriver: Services discovered.");
                
                for (BluetoothGattService service : gatt.getServices()) {
                    for (BluetoothGattCharacteristic characteristic : service.getCharacteristics()) {
                        int props = characteristic.getProperties();
                        if ((props & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0 || 
                            (props & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
                            if (writeCharacteristic == null) writeCharacteristic = characteristic;
                        }
                        if ((props & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) {
                            if (notifyCharacteristic == null) notifyCharacteristic = characteristic;
                        }
                    }
                }

                if (notifyCharacteristic != null) {
                    gatt.setCharacteristicNotification(notifyCharacteristic, true);
                    BluetoothGattDescriptor descriptor = notifyCharacteristic.getDescriptor(CCCD_UUID);
                    if (descriptor != null) {
                        descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                        gatt.writeDescriptor(descriptor);
                    }
                }

                if (writeCharacteristic != null && notifyCharacteristic != null) {
                    LimeLog.info("ProConBleDriver: Found characteristics! Initializing...");
                    notifyDeviceAdded();
                    // Basic init
                    sendSubcommand((byte)0x40, new byte[]{0x01}); // Enable IMU
                    sendSubcommand((byte)0x48, new byte[]{0x01}); // Enable Vibration
                    sendSubcommand((byte)0x30, new byte[]{0x01}); // Player 1 LED
                    sendSubcommand((byte)0x03, new byte[]{0x30}); // Input mode 0x30
                } else {
                    LimeLog.warning("ProConBleDriver: Required characteristics not found.");
                }
            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            LimeLog.info("ProConBleDriver: Descriptor write status=" + status);
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            synchronized (writeQueue) {
                isWriting = false;
                processWriteQueue();
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            if (characteristic == notifyCharacteristic) {
                byte[] data = characteristic.getValue();
                if (data != null && data.length > 0) {
                    if (handleRead(ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN))) {
                        reportInput();
                        reportMotion();
                    }
                }
            }
        }
    };

    private boolean handleRead(ByteBuffer buffer) {
        if (buffer.remaining() < 12) return false;

        byte reportId = buffer.get(0);
        if (reportId == 0x30 || reportId == 0x21 || reportId == 0x31 || reportId == 0x32 || reportId == 0x33) {
            buttonFlags = 0;
            setButtonFlag(ControllerPacket.B_FLAG, buffer.get(3) & 0x08);
            setButtonFlag(ControllerPacket.A_FLAG, buffer.get(3) & 0x04);
            setButtonFlag(ControllerPacket.Y_FLAG, buffer.get(3) & 0x02);
            setButtonFlag(ControllerPacket.X_FLAG, buffer.get(3) & 0x01);
            setButtonFlag(ControllerPacket.UP_FLAG, buffer.get(5) & 0x02);
            setButtonFlag(ControllerPacket.DOWN_FLAG, buffer.get(5) & 0x01);
            setButtonFlag(ControllerPacket.LEFT_FLAG, buffer.get(5) & 0x08);
            setButtonFlag(ControllerPacket.RIGHT_FLAG, buffer.get(5) & 0x04);
            setButtonFlag(ControllerPacket.BACK_FLAG, buffer.get(4) & 0x01);
            setButtonFlag(ControllerPacket.PLAY_FLAG, buffer.get(4) & 0x02);
            setButtonFlag(ControllerPacket.MISC_FLAG, buffer.get(4) & 0x20);
            setButtonFlag(ControllerPacket.SPECIAL_BUTTON_FLAG, buffer.get(4) & 0x10);
            setButtonFlag(ControllerPacket.LB_FLAG, buffer.get(5) & 0x40);
            setButtonFlag(ControllerPacket.RB_FLAG, buffer.get(3) & 0x40);
            setButtonFlag(ControllerPacket.LS_CLK_FLAG, buffer.get(4) & 0x08);
            setButtonFlag(ControllerPacket.RS_CLK_FLAG, buffer.get(4) & 0x04);

            leftTrigger = ((buffer.get(5) & 0x80) != 0) ? 1 : 0;
            rightTrigger = ((buffer.get(3) & 0x80) != 0) ? 1 : 0;

            if (buffer.remaining() >= 12) {
                int _leftStickX = buffer.get(6) & 0xFF | ((buffer.get(7) & 0x0F) << 8);
                int _leftStickY = ((buffer.get(7) & 0xF0) >> 4) | (buffer.get(8) << 4);
                int _rightStickX = buffer.get(9) & 0xFF | ((buffer.get(10) & 0x0F) << 8);
                int _rightStickY = ((buffer.get(10) & 0xF0) >> 4) | (buffer.get(11) << 4);

                leftStickX = applyStickCalibration(_leftStickX, 0, 0);
                leftStickY = applyStickCalibration(-_leftStickY - 1, 0, 1);
                rightStickX = applyStickCalibration(_rightStickX, 1, 0);
                rightStickY = applyStickCalibration(-_rightStickY - 1, 1, 1);
            }

            if (buffer.remaining() >= 49 && reportId == 0x30) {
                accelX = buffer.getShort(37) / 4096.0f;
                accelY = buffer.getShort(39) / 4096.0f;
                accelZ = buffer.getShort(41) / 4096.0f;
                gyroZ = -buffer.getShort(43) / 16.0f;
                gyroX = -buffer.getShort(45) / 16.0f;
                gyroY = buffer.getShort(47) / 16.0f;
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
        if (value < stickExtends[stick][axis][0]) {
            stickExtends[stick][axis][0] = value;
            return -1;
        } else if (value > stickExtends[stick][axis][1]) {
            stickExtends[stick][axis][1] = value;
            return 1;
        }
        if (value > 0) return value / stickExtends[stick][axis][1];
        else return -value / stickExtends[stick][axis][0];
    }
}
