package com.limelight.binding.input.virtual_controller;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.View;
import android.widget.FrameLayout;

import com.limelight.LimeLog;
import com.limelight.binding.input.ControllerHandler;
import com.limelight.nvstream.NvConnection;
import com.limelight.preferences.PreferenceConfiguration;
import androidx.compose.ui.platform.ComposeView;

public class VirtualController {
    public static class ControllerInputContext {
        public int inputMap = 0;
        public byte leftTrigger = 0x00;
        public byte rightTrigger = 0x00;
        public short rightStickX = 0x0000;
        public short rightStickY = 0x0000;
        public short leftStickX = 0x0000;
        public short leftStickY = 0x0000;
    }

    public enum ControllerMode {
        Active,
        MoveButtons,
        ResizeButtons,
        DisableEnableButtons
    }

    private static final boolean _PRINT_DEBUG_INFORMATION = false;

    private final ControllerHandler controllerHandler;
    private final Context context;
    private final Handler handler;

    private final Runnable delayedRetransmitRunnable = new Runnable() {
        @Override
        public void run() {
            sendControllerInputContextInternal();
        }
    };

    private FrameLayout frame_layout = null;
    private ComposeView composeView = null;

    ControllerMode currentMode = ControllerMode.Active;
    ControllerInputContext inputContext = new ControllerInputContext();

    private final Vibrator vibrator;
    private final VibrationEffect defaultVibrationEffect;
    private final NvConnection conn;

    public VirtualController(final ControllerHandler controllerHandler, final NvConnection conn, FrameLayout layout, final Context context) {
        this.controllerHandler = controllerHandler;
        this.conn = conn;
        this.frame_layout = layout;
        this.context = context;
        this.handler = new Handler(Looper.getMainLooper());

        this.vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            defaultVibrationEffect = VibrationEffect.createOneShot(10, VibrationEffect.DEFAULT_AMPLITUDE);
        } else {
            defaultVibrationEffect = null;
        }
    }

    Handler getHandler() {
        return handler;
    }

    public void hide() {
        if (composeView != null) composeView.setVisibility(View.GONE);
    }

    public void show() {
        if (composeView != null) composeView.setVisibility(View.VISIBLE);
    }

    public int switchShowHide() {
        if (composeView != null && composeView.getVisibility() == View.VISIBLE) {
            hide();
            return 0;
        } else {
            show();
            return 1;
        }
    }

    public void removeElements() {
        if (composeView != null) {
            frame_layout.removeView(composeView);
            composeView = null;
        }
    }

    private static final void _DBG(String text) {
        if (_PRINT_DEBUG_INFORMATION) {
            LimeLog.info("VirtualController: " + text);
        }
    }

    public void refreshLayout() {
        removeElements();
        composeView = VirtualControllerWrapper.attachComposeView(frame_layout, this);
    }

    public ControllerMode getControllerMode() {
        return currentMode;
    }

    public ControllerInputContext getControllerInputContext() {
        return inputContext;
    }

    private void sendControllerInputContextInternal() {
        _DBG("INPUT_MAP + " + inputContext.inputMap);
        _DBG("LEFT_TRIGGER " + inputContext.leftTrigger);
        _DBG("RIGHT_TRIGGER " + inputContext.rightTrigger);
        _DBG("LEFT STICK X: " + inputContext.leftStickX + " Y: " + inputContext.leftStickY);
        _DBG("RIGHT STICK X: " + inputContext.rightStickX + " Y: " + inputContext.rightStickY);

        if (controllerHandler != null) {
            controllerHandler.reportOscState(
                    inputContext.inputMap,
                    inputContext.leftStickX,
                    inputContext.leftStickY,
                    inputContext.rightStickX,
                    inputContext.rightStickY,
                    inputContext.leftTrigger,
                    inputContext.rightTrigger
            );
        }
    }

    public void sendControllerInputContext(long vibrationDuration, int vibrationAmplitude) {
        handler.removeCallbacks(delayedRetransmitRunnable);

        sendControllerInputContextInternal();
        if (frame_layout != null && PreferenceConfiguration.readPreferences(context).enableKeyboardVibrate) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                VibrationEffect effect;
                if (vibrationDuration == 0) {
                    effect = defaultVibrationEffect;
                } else {
                    effect = VibrationEffect.createOneShot(vibrationDuration, vibrationAmplitude);
                }
                vibrator.vibrate(effect);
            } else {
                if (vibrationDuration == 0) {
                    vibrationDuration = 10;
                }
                vibrator.vibrate(vibrationDuration);
            }
        }
        handler.postDelayed(delayedRetransmitRunnable, 25);
        handler.postDelayed(delayedRetransmitRunnable, 50);
        handler.postDelayed(delayedRetransmitRunnable, 75);
    }

    public void sendControllerInputContext() {
        sendControllerInputContext(0, 0);
    }

    public void sendKeyboardInput(short keyCode, byte keyDirection, byte modifier, byte flags) {
        if (conn != null) {
            conn.sendKeyboardInput(keyCode, keyDirection, modifier, flags);
        }
    }

    public void sendMouseMove(short deltaX, short deltaY) {
        if (conn != null) {
            conn.sendMouseMove(deltaX, deltaY);
        }
    }

    public void sendMouseButtonDown(byte mouseButton) {
        if (conn != null) {
            conn.sendMouseButtonDown(mouseButton);
        }
    }

    public void sendMouseButtonUp(byte mouseButton) {
        if (conn != null) {
            conn.sendMouseButtonUp(mouseButton);
        }
    }
}
