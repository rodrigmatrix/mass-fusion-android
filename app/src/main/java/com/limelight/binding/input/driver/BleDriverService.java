package com.limelight.binding.input.driver;

import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Binder;
import android.os.IBinder;

import com.limelight.LimeLog;

import java.util.ArrayList;

public class BleDriverService extends Service implements UsbDriverListener {

    private BluetoothAdapter bluetoothAdapter;
    private boolean started;

    private final BleDriverBinder binder = new BleDriverBinder();
    private final ArrayList<AbstractController> controllers = new ArrayList<>();

    private UsbDriverListener listener;
    private int nextDeviceId = 100; // Start at 100 to avoid conflict with USB devices

    @Override
    public void reportControllerState(int controllerId, int buttonFlags, float leftStickX, float leftStickY,
                                      float rightStickX, float rightStickY, float leftTrigger, float rightTrigger) {
        if (listener != null) {
            listener.reportControllerState(controllerId, buttonFlags, leftStickX, leftStickY, rightStickX, rightStickY, leftTrigger, rightTrigger);
        }
    }

    @Override
    public void reportControllerMotion(int controllerId, byte motionType, float motionX, float motionY, float motionZ) {
        if (listener != null) {
            listener.reportControllerMotion(controllerId, motionType, motionX, motionY, motionZ);
        }
    }

    @Override
    public void deviceRemoved(AbstractController controller) {
        controllers.remove(controller);
        if (listener != null) {
            listener.deviceRemoved(controller);
        }
    }

    @Override
    public void deviceAdded(AbstractController controller) {
        if (listener != null) {
            listener.deviceAdded(controller);
        }
    }

    public class BleDriverBinder extends Binder {
        public void setListener(UsbDriverListener listener) {
            BleDriverService.this.listener = listener;
            if (listener != null) {
                for (AbstractController controller : controllers) {
                    listener.deviceAdded(controller);
                }
            }
        }

        public void start() {
            BleDriverService.this.start();
        }

        public void stop() {
            BleDriverService.this.stop();
        }
    }

    private void start() {
        if (started) return;

        SharedPreferences prefs = getSharedPreferences("ble_prefs", Context.MODE_PRIVATE);
        String mac = prefs.getString(BlePairingActivity.PREF_PAIRED_BLE_CONTROLLER, null);

        if (mac != null && bluetoothAdapter != null && bluetoothAdapter.isEnabled()) {
            BluetoothDevice device = bluetoothAdapter.getRemoteDevice(mac);
            LimeLog.info("BleDriverService attempting to connect to " + mac);
            ProConBleDriver controller = new ProConBleDriver(this, device, nextDeviceId++, this);
            if (controller.start()) {
                controllers.add(controller);
                started = true;
            }
        }
    }

    private void stop() {
        if (!started) return;
        started = false;

        while (controllers.size() > 0) {
            controllers.remove(0).stop();
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (bluetoothManager != null) {
            this.bluetoothAdapter = bluetoothManager.getAdapter();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        LimeLog.info("BleDriverService: onStartCommand executed!");
        start();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stop();
        listener = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }
}
