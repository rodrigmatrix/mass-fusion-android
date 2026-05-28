package com.limelight.binding.input.driver;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.SparseArray;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.limelight.LimeLog;

import java.util.ArrayList;
import java.util.List;

public class BlePairingActivity extends Activity {

    public static final String PREF_PAIRED_BLE_CONTROLLER = "paired_ble_controller_mac";
    private static final int PERMISSION_REQUEST_CODE = 1001;
    private static final long SCAN_PERIOD = 30000;

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner bluetoothLeScanner;
    private boolean mScanning;
    private Handler handler;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        handler = new Handler(Looper.getMainLooper());

        BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (bluetoothManager != null) {
            bluetoothAdapter = bluetoothManager.getAdapter();
        }

        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            Toast.makeText(this, "Bluetooth must be enabled to pair controllers", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        if (checkPermissions()) {
            startBleScan();
        } else {
            requestPermissions();
        }
    }

    private boolean checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                   ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        } else {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        }
    }

    private void requestPermissions() {
        List<String> permissions = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN);
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT);
        } else {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }

        ActivityCompat.requestPermissions(this, permissions.toArray(new String[0]), PERMISSION_REQUEST_CODE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }

            if (allGranted) {
                startBleScan();
            } else {
                Toast.makeText(this, "Permissions required to scan for BLE controllers", Toast.LENGTH_SHORT).show();
                finish();
            }
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }
    }

    private void startBleScan() {
        if (bluetoothAdapter == null) return;
        bluetoothLeScanner = bluetoothAdapter.getBluetoothLeScanner();
        if (bluetoothLeScanner == null) {
            Toast.makeText(this, "BLE scanning is not supported on this device", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        Toast.makeText(this, "Scanning for Switch Pro Controller...", Toast.LENGTH_LONG).show();

        // Stop scanning after a pre-defined scan period.
        handler.postDelayed(() -> {
            if (mScanning) {
                mScanning = false;
                try {
                    bluetoothLeScanner.stopScan(leScanCallback);
                } catch (SecurityException e) {
                    LimeLog.warning("SecurityException stopping scan: " + e.getMessage());
                }
                Toast.makeText(BlePairingActivity.this, "Could not find controller. Make sure it is in pairing mode.", Toast.LENGTH_LONG).show();
                finish();
            }
        }, SCAN_PERIOD);

        mScanning = true;
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();
                
        // Scan for all devices, filter by name in callback
        try {
            bluetoothLeScanner.startScan(null, settings, leScanCallback);
        } catch (SecurityException e) {
            LimeLog.warning("SecurityException starting scan: " + e.getMessage());
            Toast.makeText(this, "Permission error starting scan", Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    private final ScanCallback leScanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            if (!mScanning) return;
            
            String deviceName = result.getDevice().getName();
            if (deviceName == null && result.getScanRecord() != null) {
                deviceName = result.getScanRecord().getDeviceName();
            }

            LimeLog.info("BLE Scanner saw device: " + result.getDevice().getAddress() + " Name: " + deviceName);

            boolean isProController = false;

            if (deviceName != null && deviceName.toLowerCase().contains("pro controller")) {
                isProController = true;
            }

            SparseArray<byte[]> manufacturerData = result.getScanRecord() != null ? result.getScanRecord().getManufacturerSpecificData() : null;
            if (manufacturerData != null && manufacturerData.indexOfKey(1363) >= 0) {
                isProController = true;
            }

            if (isProController) {
                LimeLog.info("Found Nintendo Switch BLE Controller: " + result.getDevice().getAddress() + " name: " + deviceName);
                
                // Stop scanning
                mScanning = false;
                try {
                    bluetoothLeScanner.stopScan(leScanCallback);
                } catch (SecurityException e) {
                    // Ignore
                }

                // Bond with the device so it remembers the phone
                if (result.getDevice().getBondState() != BluetoothDevice.BOND_BONDED) {
                    try {
                        result.getDevice().createBond();
                    } catch (SecurityException e) {
                        LimeLog.warning("SecurityException creating bond: " + e.getMessage());
                    }
                }

                // Save MAC address to preferences
                SharedPreferences prefs = getSharedPreferences("ble_prefs", Context.MODE_PRIVATE);
                prefs.edit().putString(PREF_PAIRED_BLE_CONTROLLER, result.getDevice().getAddress()).apply();

                Toast.makeText(BlePairingActivity.this, "Paired successfully with Pro Controller 2!", Toast.LENGTH_SHORT).show();
                finish();
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            LimeLog.warning("BLE Scan failed with code " + errorCode);
            if (mScanning) {
                mScanning = false;
                Toast.makeText(BlePairingActivity.this, "Scan failed: " + errorCode, Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    };
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mScanning && bluetoothLeScanner != null) {
            try {
                bluetoothLeScanner.stopScan(leScanCallback);
            } catch (SecurityException e) {
                // Ignore
            }
            mScanning = false;
        }
    }
}
