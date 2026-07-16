package com.limelight

import android.app.AlertDialog
import android.content.Context
import android.hardware.Sensor
import android.media.AudioAttributes
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.InputDevice
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.limelight.utils.DeviceUtils

class DebugInfoActivity : AppCompatActivity() {
    private var vibrator: Vibrator? = null
    private var vibratorOnline: Vibrator? = null
    private var simulatedAmplitude by mutableStateOf(220)
    private val ids = mutableListOf<InputDevice>()
    private var gamepadInfoText by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

        setContent {
            com.limelight.ui.theme.MassFusionTheme {
                DebugInfoScreen(
                    simulatedAmplitude = simulatedAmplitude,
                    gamepadInfoText = gamepadInfoText,
                    onUpdateAmplitude = { showSimulateAmpDialog() },
                    onDeviceVibration = { showDeviceVibrationDialog() },
                    onRefreshGamepad = { updateGamePad() },
                    onGamepadRumble = { showGamepadRumbleDialog() },
                    onCancelRumble = { cancelRumble() }
                )
            }
        }
    }

    private fun showSimulateAmpDialog() {
        val seekBar = SeekBar(this).apply {
            max = 255
            progress = simulatedAmplitude
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    simulatedAmplitude = progress
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.debug_info_set_amplitude))
            .setView(seekBar)
            .show()
    }

    private fun cancelRumble() {
        vibratorOnline?.cancel()
        vibrator?.cancel()
    }

    private fun rumble(v: Vibrator) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(VibrationEffect.createWaveform(longArrayOf(1000), intArrayOf(simulatedAmplitude), 0))
        } else {
            val pwmPeriod = 20L
            val onTime = ((simulatedAmplitude / 255.0) * pwmPeriod).toLong()
            val offTime = pwmPeriod - onTime
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .build()
            v.vibrate(longArrayOf(0, onTime, offTime), 0, audioAttributes)
        }
    }

    private fun showDeviceVibrationDialog() {
        val titles = arrayOf(
            getString(R.string.debug_info_simple_vibration),
            getString(R.string.debug_info_continuous_hd_vibration)
        )
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.debug_info_please_choose))
            .setItems(titles) { dialog, which ->
                dialog.dismiss()
                when (which) {
                    0 -> vibrator?.vibrate(1000)
                    1 -> vibrator?.let { rumble(it) }
                }
            }
            .show()
    }

    private fun showGamepadRumbleDialog() {
        if (ids.isEmpty()) {
            Toast.makeText(this, getString(R.string.debug_info_no_gamepad_detected), Toast.LENGTH_LONG).show()
            return
        }
        val strings = ids.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.debug_info_please_choose))
            .setItems(strings) { dialog, which ->
                dialog.dismiss()
                val devVibrator = ids[which].vibrator
                if (devVibrator.hasVibrator()) {
                    val titles = arrayOf(
                        getString(R.string.debug_info_simple_vibration),
                        getString(R.string.debug_info_continuous_hd_vibration)
                    )
                    AlertDialog.Builder(this)
                        .setTitle(getString(R.string.debug_info_please_choose))
                        .setItems(titles) { d2, which2 ->
                            d2.dismiss()
                            when (which2) {
                                0 -> devVibrator.vibrate(1000)
                                1 -> {
                                    cancelRumble()
                                    vibratorOnline = devVibrator
                                    rumble(vibratorOnline!!)
                                }
                            }
                        }
                        .show()
                } else {
                    Toast.makeText(this, getString(R.string.debug_info_no_vibrator), Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        vibratorOnline?.cancel()
    }

    private fun updateGamePad() {
        ids.clear()
        val sb = StringBuilder("\n")
        val deviceIds = InputDevice.getDeviceIds()
        for (deviceId in deviceIds) {
            val dev = InputDevice.getDevice(deviceId) ?: continue
            val sources = dev.sources
            if ((sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                (sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
            ) {
                if (getMotionRangeForJoystickAxis(dev, MotionEvent.AXIS_X) != null &&
                    getMotionRangeForJoystickAxis(dev, MotionEvent.AXIS_Y) != null
                ) {
                    ids.add(dev)
                    sb.append(getString(R.string.debug_info_name)).append(dev.name).append("\n")
                    sb.append(getString(R.string.debug_info_sensors))
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        var sensor = ""
                        if (dev.sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null) {
                            sensor += getString(R.string.debug_info_accelerometer)
                        }
                        if (dev.sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null) {
                            sensor += getString(R.string.debug_info_gyroscope)
                        }
                        if (sensor.isEmpty()) {
                            sb.append(getString(R.string.debug_info_no_relevant_driver))
                        } else {
                            sb.append(sensor)
                        }
                        sb.append("\n")
                    } else {
                        sb.append(getString(R.string.debug_info_no_api_below_android12)).append("\n")
                    }
                    sb.append(getString(R.string.debug_info_vid_pid))
                        .append(dev.vendorId).append("_").append(dev.productId)
                        .append("\t    [").append(String.format("%04x", dev.vendorId))
                        .append("_").append(String.format("%04x", dev.productId)).append("]\n")
                    sb.append(getString(R.string.debug_info_vibration))
                        .append(if (dev.vibrator.hasVibrator()) getString(R.string.debug_info_supported) else getString(R.string.debug_info_not_supported))
                        .append("\n")
                    sb.append(getString(R.string.debug_info_details)).append("\n")
                    sb.append(dev.toString()).append("\n")
                }
            }
        }
        gamepadInfoText = getString(R.string.debug_info_number_of_gamepads) + ids.size + "\n" + sb.toString()
    }

    private fun getMotionRangeForJoystickAxis(dev: InputDevice, axis: Int): InputDevice.MotionRange? {
        return dev.getMotionRange(axis, InputDevice.SOURCE_JOYSTICK)
            ?: dev.getMotionRange(axis, InputDevice.SOURCE_GAMEPAD)
    }
}

@Composable
fun DebugInfoScreen(
    simulatedAmplitude: Int,
    gamepadInfoText: String,
    onUpdateAmplitude: () -> Unit,
    onDeviceVibration: () -> Unit,
    onRefreshGamepad: () -> Unit,
    onGamepadRumble: () -> Unit,
    onCancelRumble: () -> Unit
) {
    val context = LocalContext.current
    val kernelVersion = System.getProperty("os.version") ?: ""
    val deviceText = buildString {
        append(stringResource(R.string.debug_info_android_version)).append(DeviceUtils.getSDKVersionName())
        append("\t").append(stringResource(R.string.debug_info_api_version)).append(Build.VERSION.SDK_INT)
        append("\n").append(stringResource(R.string.debug_info_kernel_version)).append(kernelVersion)
        append("\n").append(stringResource(R.string.debug_info_brand_model)).append(DeviceUtils.getManufacturer()).append("\t-\t").append(DeviceUtils.getModel())
    }
    
    val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    val hasVibrator = vibrator.hasVibrator()
    val content = if (hasVibrator) stringResource(R.string.debug_info_has_vibration_motor) else stringResource(R.string.debug_info_no_vibration_motor)
    val deviceVibrationText = stringResource(R.string.debug_info_test_device_vibration, content)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(10.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Button(onClick = onDeviceVibration, modifier = Modifier.fillMaxWidth()) {
            Text(deviceVibrationText)
        }
        
        Button(onClick = onRefreshGamepad, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.debug_info_refresh_gamepad_list))
        }
        
        Button(onClick = onGamepadRumble, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.debug_info_test_gamepad_rumble))
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onUpdateAmplitude, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.debug_info_vibration_amplitude, simulatedAmplitude))
            }
            Button(onClick = onCancelRumble, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.debug_info_stop_vibration))
            }
        }

        Text(text = deviceText)
        
        Text(text = gamepadInfoText)
    }
}
