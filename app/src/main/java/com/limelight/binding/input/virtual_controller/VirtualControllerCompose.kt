package com.limelight.binding.input.virtual_controller

import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.yoimerdr.compose.virtualjoystick.ui.view.VirtualJoystick
import io.github.yoimerdr.compose.virtualjoystick.core.control.DirectionType
import io.github.yoimerdr.compose.virtualjoystick.ui.state.JoystickEvent
import io.github.yoimerdr.compose.virtualjoystick.ui.state.JoystickMoveEnd
import io.github.yoimerdr.compose.virtualjoystick.ui.state.JoystickMoveHeld
import io.github.yoimerdr.compose.virtualjoystick.ui.state.JoystickMoveStart
import io.github.yoimerdr.compose.virtualjoystick.ui.state.JoystickMoving
import io.github.yoimerdr.compose.virtualjoystick.ui.view.rememberJoystickEventHolder
import io.github.yoimerdr.compose.virtualjoystick.ui.view.rememberJoystickState
import com.limelight.nvstream.input.ControllerPacket
import com.limelight.ui.theme.MassFusionTheme
import com.limelight.ui.theme.md_theme_dark_primary
import com.limelight.ui.theme.md_theme_dark_secondary
import com.limelight.ui.theme.md_theme_dark_tertiary
import kotlin.math.cos
import kotlin.math.sin

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun VirtualControllerCompose(
    inputContext: VirtualController.ControllerInputContext?,
    onInputChanged: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Left Joystick
        Box(modifier = Modifier
            .align(Alignment.BottomStart)
            .padding(32.dp)) {
            val leftState = rememberJoystickState(directionType = DirectionType.Complete)
            val leftEventHolder = rememberJoystickEventHolder(leftState)
            
            LaunchedEffect(leftEventHolder) {
                leftEventHolder.events.collect { event: JoystickEvent ->
                    when (event) {
                        is JoystickMoving -> {
                            val angleRad = Math.toRadians(event.snapshot.angle.toDouble())
                            val x = (event.snapshot.strength * cos(angleRad) * 32767).toInt().toShort()
                            val y = (event.snapshot.strength * sin(angleRad) * 32767).toInt().toShort()
                            inputContext?.leftStickX = x
                            inputContext?.leftStickY = y
                            onInputChanged()
                        }
                        is JoystickMoveEnd -> {
                            inputContext?.leftStickX = 0
                            inputContext?.leftStickY = 0
                            onInputChanged()
                        }
                        else -> {}
                    }
                }
            }
            VirtualJoystick(
                state = leftState,
                holder = leftEventHolder,
                modifier = Modifier
                    .size(150.dp)
                    .alpha(0.7f),
                properties = io.github.yoimerdr.compose.virtualjoystick.ui.scope.draw.shapes.CircleDrawDefaults.properties()
            )
        }

        // Right Joystick
        Box(modifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(32.dp)
            .offset(x = (-200).dp, y = (-50).dp)) {
            val rightState = rememberJoystickState(directionType = DirectionType.Complete)
            val rightEventHolder = rememberJoystickEventHolder(rightState)
            
            LaunchedEffect(rightEventHolder) {
                rightEventHolder.events.collect { event: JoystickEvent ->
                    when (event) {
                        is JoystickMoving -> {
                            val angleRad = Math.toRadians(event.snapshot.angle.toDouble())
                            val x = (event.snapshot.strength * cos(angleRad) * 32767).toInt().toShort()
                            val y = (event.snapshot.strength * sin(angleRad) * 32767).toInt().toShort()
                            inputContext?.rightStickX = x
                            inputContext?.rightStickY = y
                            onInputChanged()
                        }
                        is JoystickMoveEnd -> {
                            inputContext?.rightStickX = 0
                            inputContext?.rightStickY = 0
                            onInputChanged()
                        }
                        else -> {}
                    }
                }
            }
            VirtualJoystick(
                state = rightState,
                holder = rightEventHolder,
                modifier = Modifier
                    .size(150.dp)
                    .alpha(0.7f),
                properties = io.github.yoimerdr.compose.virtualjoystick.ui.scope.draw.shapes.CircleDrawDefaults.properties()
            )
        }

        // Action Buttons
        Box(modifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(32.dp)) {
            ActionButtons(inputContext, onInputChanged)
        }
        
        // D-Pad
        Box(modifier = Modifier
            .align(Alignment.CenterStart)
            .padding(start = 32.dp, bottom = 100.dp)) {
            DPad(inputContext, onInputChanged)
        }

        // Triggers and Bumpers (L1/L2, R1/R2)
        Box(modifier = Modifier
            .align(Alignment.TopStart)
            .padding(32.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                GamepadButton("L1", Color.Gray, ControllerPacket.LB_FLAG, inputContext, onInputChanged)
                GamepadButton("L2", Color.DarkGray, -1, inputContext, onInputChanged, isLeftTrigger = true)
            }
        }

        Box(modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(32.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                GamepadButton("R2", Color.DarkGray, -1, inputContext, onInputChanged, isRightTrigger = true)
                GamepadButton("R1", Color.Gray, ControllerPacket.RB_FLAG, inputContext, onInputChanged)
            }
        }
        
        // Start / Select
        Box(modifier = Modifier
            .align(Alignment.TopCenter)
            .padding(32.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(64.dp)) {
                GamepadButton("SEL", Color.Gray, ControllerPacket.BACK_FLAG, inputContext, onInputChanged)
                GamepadButton("STA", Color.Gray, ControllerPacket.PLAY_FLAG, inputContext, onInputChanged)
            }
        }
    }
}

@Composable
fun ActionButtons(
    inputContext: VirtualController.ControllerInputContext?,
    onInputChanged: () -> Unit
) {
    Box(modifier = Modifier.size(200.dp)) {
        // Y (Top)
        Box(modifier = Modifier.align(Alignment.TopCenter)) {
            GamepadButton("Y", Color(0xFF39FF14), ControllerPacket.Y_FLAG, inputContext, onInputChanged) // Neon Green
        }
        // A (Bottom)
        Box(modifier = Modifier.align(Alignment.BottomCenter)) {
            GamepadButton("A", md_theme_dark_tertiary, ControllerPacket.A_FLAG, inputContext, onInputChanged) // Neon Cyan
        }
        // X (Left)
        Box(modifier = Modifier.align(Alignment.CenterStart)) {
            GamepadButton("X", md_theme_dark_secondary, ControllerPacket.X_FLAG, inputContext, onInputChanged) // Neon Pink
        }
        // B (Right)
        Box(modifier = Modifier.align(Alignment.CenterEnd)) {
            GamepadButton("B", Color(0xFFFF3131), ControllerPacket.B_FLAG, inputContext, onInputChanged) // Neon Red
        }
    }
}

@Composable
fun DPad(
    inputContext: VirtualController.ControllerInputContext?,
    onInputChanged: () -> Unit
) {
    Box(modifier = Modifier.size(160.dp)) {
        // Up
        Box(modifier = Modifier.align(Alignment.TopCenter)) {
            GamepadButton("▲", Color.White, ControllerPacket.UP_FLAG, inputContext, onInputChanged)
        }
        // Down
        Box(modifier = Modifier.align(Alignment.BottomCenter)) {
            GamepadButton("▼", Color.White, ControllerPacket.DOWN_FLAG, inputContext, onInputChanged)
        }
        // Left
        Box(modifier = Modifier.align(Alignment.CenterStart)) {
            GamepadButton("◀", Color.White, ControllerPacket.LEFT_FLAG, inputContext, onInputChanged)
        }
        // Right
        Box(modifier = Modifier.align(Alignment.CenterEnd)) {
            GamepadButton("▶", Color.White, ControllerPacket.RIGHT_FLAG, inputContext, onInputChanged)
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun GamepadButton(
    text: String,
    baseColor: Color,
    flag: Int,
    inputContext: VirtualController.ControllerInputContext?,
    onInputChanged: () -> Unit,
    isLeftTrigger: Boolean = false,
    isRightTrigger: Boolean = false
) {
    var isPressed by remember { mutableStateOf(false) }

    val currentColor = if (isPressed) baseColor else baseColor.copy(alpha = 0.3f)
    val shadowRadius = if (isPressed) 20.dp else 0.dp

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(60.dp)
            .shadow(shadowRadius, CircleShape, spotColor = baseColor)
            .background(Color.Black.copy(alpha = 0.5f), CircleShape)
            .border(2.dp, currentColor, CircleShape)
            .pointerInteropFilter { event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                        isPressed = true
                        if (flag != -1) inputContext?.inputMap = inputContext.inputMap or flag
                        if (isLeftTrigger) inputContext?.leftTrigger = 255.toByte()
                        if (isRightTrigger) inputContext?.rightTrigger = 255.toByte()
                        onInputChanged()
                        true
                    }

                    MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                        isPressed = false
                        if (flag != -1) inputContext?.inputMap = inputContext.inputMap and flag.inv()
                        if (isLeftTrigger) inputContext?.leftTrigger = 0.toByte()
                        if (isRightTrigger) inputContext?.rightTrigger = 0.toByte()
                        onInputChanged()
                        true
                    }

                    else -> false
                }
            }
    ) {
        Text(
            text = text,
            color = if (isPressed) Color.White else baseColor,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Preview(widthDp = 1000, heightDp = 600)
@Composable
private fun VirtualControllerComposePreview() {
    MassFusionTheme {
        VirtualControllerCompose(
            inputContext = null,
            onInputChanged = { },
        )
    }
}
