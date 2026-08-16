package com.limelight.binding.input.virtual_controller

import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.Button
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.limelight.binding.input.virtual_controller.models.GestureBehavior
import kotlinx.coroutines.flow.update
import io.github.yoimerdr.compose.virtualjoystick.ui.view.VirtualJoystick
import com.limelight.binding.input.virtual_controller.ui.ProfileSelectionDialog
import com.limelight.binding.input.virtual_controller.ui.ButtonEditorDialog
import com.limelight.binding.input.virtual_controller.models.CustomButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import io.github.yoimerdr.compose.virtualjoystick.core.control.DirectionType
import io.github.yoimerdr.compose.virtualjoystick.ui.state.JoystickEvent
import io.github.yoimerdr.compose.virtualjoystick.ui.state.JoystickMoveEnd
import io.github.yoimerdr.compose.virtualjoystick.ui.state.JoystickMoving
import io.github.yoimerdr.compose.virtualjoystick.ui.view.rememberJoystickEventHolder
import io.github.yoimerdr.compose.virtualjoystick.ui.view.rememberJoystickState
import com.limelight.nvstream.input.ControllerPacket
import com.limelight.ui.theme.MassFusionTheme
import com.limelight.ui.theme.md_theme_dark_secondary
import com.limelight.ui.theme.md_theme_dark_tertiary
import kotlin.math.cos
import kotlin.math.sin

const val VK_MOUSE_LEFT: Short = 0x1001.toShort()
const val VK_MOUSE_RIGHT: Short = 0x1002.toShort()
const val VK_MOUSE_MIDDLE: Short = 0x1003.toShort()

fun handleActionKey(
    key: Short,
    down: Boolean,
    onKeyboardInput: (Short, Boolean) -> Unit,
    onMouseButton: (Byte, Boolean) -> Unit
) {
    when (key) {
        VK_MOUSE_LEFT -> onMouseButton(1, down)
        VK_MOUSE_MIDDLE -> onMouseButton(3, down)
        VK_MOUSE_RIGHT -> onMouseButton(2, down)
        else -> onKeyboardInput(key, down)
    }
}

fun joystickSnapshotToControllerAxes(strength: Float, angleRadians: Float): Pair<Short, Short> {
    val x = (strength * cos(angleRadians) * 32767).toInt().coerceIn(-32767, 32767).toShort()
    val y = (-strength * sin(angleRadians) * 32767).toInt().coerceIn(-32767, 32767).toShort()
    return x to y
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun VirtualControllerCompose(
    inputContext: VirtualController.ControllerInputContext?,
    onInputChanged: () -> Unit,
    onKeyboardInput: (Short, Boolean) -> Unit = { _, _ -> },
    onMouseMove: (Int, Int) -> Unit = { _, _ -> },
    onMouseButton: (Byte, Boolean) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    val configManager = remember { GamepadConfigManager(context) }
    val isEditMode by VirtualControllerState.isEditMode.collectAsState()
    val showProfileSelection by VirtualControllerState.showProfileSelectionDialog.collectAsState()
    val activeProfile by configManager.activeProfile.collectAsState()
    val activeProfileId = activeProfile?.id

    var editingButton by remember { mutableStateOf<CustomButton?>(null) }

    if (showProfileSelection) {
        ProfileSelectionDialog(configManager)
    }

    if (editingButton != null) {
        ButtonEditorDialog(
            initialButton = editingButton!!,
            onDismiss = { editingButton = null },
            onSave = { updated ->
                val profile = activeProfile
                if (profile != null) {
                    val index = profile.customButtons.indexOfFirst { it.id == updated.id }
                    if (index >= 0) {
                        profile.customButtons[index] = updated
                    } else {
                        profile.customButtons.add(updated)
                    }
                    configManager.updateActiveProfile(profile)
                }
                editingButton = null
            },
            onDelete = { toDelete ->
                val profile = activeProfile
                if (profile != null) {
                    profile.customButtons.removeIf { it.id == toDelete.id }
                    configManager.updateActiveProfile(profile)
                }
                editingButton = null
            }
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Left Joystick
        DraggableScalableComponent("LeftJoystick", activeProfileId, configManager, isEditMode, Modifier.align(Alignment.BottomStart).padding(32.dp)) {
            val leftState = rememberJoystickState(directionType = DirectionType.Complete)
            val leftEventHolder = rememberJoystickEventHolder(leftState)
            
            if (!isEditMode) {
                LaunchedEffect(leftEventHolder) {
                    leftEventHolder.events.collect { event: JoystickEvent ->
                        when (event) {
                            is JoystickMoving -> {
                                val (x, y) = joystickSnapshotToControllerAxes(
                                    event.snapshot.strength,
                                    event.snapshot.angle
                                )
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
        DraggableScalableComponent("RightJoystick", activeProfileId, configManager, isEditMode, Modifier.align(Alignment.BottomEnd).padding(32.dp).offset(x = (-200).dp, y = (-50).dp)) {
            val rightState = rememberJoystickState(directionType = DirectionType.Complete)
            val rightEventHolder = rememberJoystickEventHolder(rightState)
            
            if (!isEditMode) {
                LaunchedEffect(rightEventHolder) {
                    rightEventHolder.events.collect { event: JoystickEvent ->
                        when (event) {
                            is JoystickMoving -> {
                                val (x, y) = joystickSnapshotToControllerAxes(
                                    event.snapshot.strength,
                                    event.snapshot.angle
                                )
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
        DraggableScalableComponent("ActionButtons", activeProfileId, configManager, isEditMode, Modifier.align(Alignment.BottomEnd).padding(32.dp)) {
            ActionButtons(inputContext, isEditMode, onInputChanged)
        }
        
        // D-Pad
        DraggableScalableComponent("DPad", activeProfileId, configManager, isEditMode, Modifier.align(Alignment.CenterStart).padding(start = 32.dp, bottom = 100.dp)) {
            DPad(inputContext, isEditMode, onInputChanged)
        }

        // Triggers and Bumpers (L1/L2, R1/R2)
        DraggableScalableComponent("LeftBumpers", activeProfileId, configManager, isEditMode, Modifier.align(Alignment.TopStart).padding(32.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                GamepadButton("L1", Color.Gray, ControllerPacket.LB_FLAG, inputContext, isEditMode, onInputChanged)
                GamepadButton("L2", Color.DarkGray, -1, inputContext, isEditMode, onInputChanged, isLeftTrigger = true)
            }
        }

        DraggableScalableComponent("RightBumpers", activeProfileId, configManager, isEditMode, Modifier.align(Alignment.TopEnd).padding(32.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                GamepadButton("R2", Color.DarkGray, -1, inputContext, isEditMode, onInputChanged, isRightTrigger = true)
                GamepadButton("R1", Color.Gray, ControllerPacket.RB_FLAG, inputContext, isEditMode, onInputChanged)
            }
        }
        
        // Start / Select
        DraggableScalableComponent("CenterButtons", activeProfileId, configManager, isEditMode, Modifier.align(Alignment.TopCenter).padding(32.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(64.dp)) {
                GamepadButton("SEL", Color.Gray, ControllerPacket.BACK_FLAG, inputContext, isEditMode, onInputChanged)
                GamepadButton("STA", Color.Gray, ControllerPacket.PLAY_FLAG, inputContext, isEditMode, onInputChanged)
            }
        }

        // Edit Mode Toggle & Add Button
        Box(modifier = Modifier.align(Alignment.TopCenter).padding(top = 100.dp)) {
            if (isEditMode) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Button(onClick = { VirtualControllerState.isEditMode.update { false } }) {
                        Text("Finish Editing")
                    }
                    Button(onClick = { 
                        configManager.resetAll()
                        VirtualControllerState.isEditMode.update { false } 
                    }) {
                        Text("Reset Layout")
                    }
                    FloatingActionButton(onClick = {
                        editingButton = CustomButton() 
                    }) {
                        Icon(Icons.Default.Add, contentDescription = "Add Button")
                    }
                }
            }
        }
        
        // Custom Buttons
        activeProfile?.customButtons?.forEach { button ->
            DraggableScalableCustomComponent(
                button = button,
                isEditMode = isEditMode,
                onUpdate = { updated ->
                    val profile = activeProfile
                    if (profile != null) {
                        val index = profile.customButtons.indexOfFirst { it.id == updated.id }
                        if (index >= 0) {
                            profile.customButtons[index] = updated
                            configManager.updateActiveProfile(profile)
                        }
                    }
                },
                modifier = Modifier.align(Alignment.Center)
            ) {
                val baseColor = MaterialTheme.colorScheme.primary
                var isPressed by remember { mutableStateOf(false) }
                val currentColor = if (isPressed) baseColor else baseColor.copy(alpha = 0.5f)

                val alpha = if (button.isTransparentArea && !isEditMode) 0f else if (button.isTransparentArea) button.opacity else if (isPressed) 1f else button.opacity
                val bgColor = if (button.isTransparentArea && !isEditMode) Color.Transparent else if (button.isTransparentArea) Color.Gray else Color.Black.copy(alpha = 0.5f)
                val borderColor = if (button.isTransparentArea && !isEditMode) Color.Transparent else currentColor

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(button.sizeDp.dp)
                        .background(bgColor, if (button.isTransparentArea) RectangleShape else CircleShape)
                        .border(2.dp, borderColor, if (button.isTransparentArea) RectangleShape else CircleShape)
                        .alpha(alpha)
                        .then(
                            if (!isEditMode) {
                                Modifier
                                    .pointerInput(Unit) {
                                        if (button.gestureBehavior != GestureBehavior.NONE) {
                                            detectDragGestures(
                                                onDragStart = { isPressed = true },
                                                onDragEnd = {
                                                    isPressed = false
                                                    if (button.gestureBehavior == GestureBehavior.RIGHT_JOYSTICK) {
                                                        inputContext?.rightStickX = 0
                                                        inputContext?.rightStickY = 0
                                                        onInputChanged()
                                                    }
                                                },
                                                onDragCancel = {
                                                    isPressed = false
                                                    if (button.gestureBehavior == GestureBehavior.RIGHT_JOYSTICK) {
                                                        inputContext?.rightStickX = 0
                                                        inputContext?.rightStickY = 0
                                                        onInputChanged()
                                                    }
                                                },
                                                onDrag = { change, dragAmount ->
                                                    change.consume()
                                                    if (button.gestureBehavior == GestureBehavior.RIGHT_JOYSTICK) {
                                                        val mult = 300f
                                                        inputContext?.rightStickX = (dragAmount.x * mult).toInt().coerceIn(-32767, 32767).toShort()
                                                        inputContext?.rightStickY = (dragAmount.y * mult).toInt().coerceIn(-32767, 32767).toShort()
                                                        onInputChanged()
                                                    } else if (button.gestureBehavior == GestureBehavior.MOUSE_LOOK) {
                                                        onMouseMove((dragAmount.x * 2).toInt(), (dragAmount.y * 2).toInt())
                                                    }
                                                }
                                            )
                                        }
                                    }
                                    .pointerInput(Unit) {
                                        detectTapGestures(
                                            onPress = {
                                                isPressed = true
                                                val holdKeys = button.onHoldAction?.keys.orEmpty()
                                                holdKeys.forEach { handleActionKey(it, true, onKeyboardInput, onMouseButton) }

                                                val wasReleased = tryAwaitRelease()

                                                if (wasReleased) {
                                                    val tapKeys = button.onTapAction?.keys.orEmpty() + button.keyBindings
                                                    tapKeys.forEach { handleActionKey(it, true, onKeyboardInput, onMouseButton) }
                                                    tapKeys.reversed().forEach { handleActionKey(it, false, onKeyboardInput, onMouseButton) }

                                                    if (button.onReleaseAction != null) {
                                                        button.onReleaseAction?.keys?.forEach { handleActionKey(it, true, onKeyboardInput, onMouseButton) }
                                                        button.onReleaseAction?.keys?.reversed()?.forEach { handleActionKey(it, false, onKeyboardInput, onMouseButton) }
                                                    }

                                                    if (button.gestureBehavior == GestureBehavior.MOUSE_CLICK) {
                                                        onMouseButton(1, true)
                                                        onMouseButton(1, false)
                                                    }
                                                }
                                                holdKeys.reversed().forEach { handleActionKey(it, false, onKeyboardInput, onMouseButton) }
                                                isPressed = false
                                            }
                                        )
                                    }
                            } else {
                                Modifier.clickable { editingButton = button }
                            }
                        )
                ) {
                    if (!button.isTransparentArea || isEditMode) {
                        val availableIcons = oscIconMap()
                        
                        val icon = availableIcons[button.iconName]
                        if (icon != null) {
                            Icon(icon, contentDescription = button.label, tint = Color.White)
                        } else if (button.label.isNotBlank()) {
                            Text(
                                text = button.label,
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp
                            )
                        } else {
                            Text("?", color = Color.White)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DraggableScalableCustomComponent(
    button: CustomButton,
    isEditMode: Boolean,
    onUpdate: (CustomButton) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .offset(x = button.x.dp, y = button.y.dp)
            .graphicsLayer(scaleX = button.scale, scaleY = button.scale)
            .then(
                if (isEditMode) {
                    Modifier
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                onUpdate(
                                    button.copy(
                                        x = button.x + (pan.x / density),
                                        y = button.y + (pan.y / density),
                                        scale = (button.scale * zoom).coerceIn(0.5f, 3f)
                                    )
                                )
                            }
                        }
                        .border(2.dp, Color.Yellow, if (button.isTransparentArea) RectangleShape else CircleShape)
                        .padding(8.dp)
                } else Modifier
            )
    ) {
        content()
    }
}

@Composable
fun DraggableScalableComponent(
    id: String,
    activeProfileId: String?,
    configManager: GamepadConfigManager,
    isEditMode: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    var config by remember { mutableStateOf(configManager.loadConfig(id)) }
    LaunchedEffect(activeProfileId) {
        config = configManager.loadConfig(id)
    }

    if (!config.isEnabled && !isEditMode) return

    Box(
        modifier = modifier
            .offset(x = config.x.dp, y = config.y.dp)
            .graphicsLayer(scaleX = config.scale, scaleY = config.scale)
            .then(
                if (isEditMode) {
                    Modifier
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                config = config.copy(
                                    x = config.x + (pan.x / density),
                                    y = config.y + (pan.y / density),
                                    scale = (config.scale * zoom).coerceIn(0.5f, 3f)
                                )
                                configManager.saveConfig(config)
                            }
                        }
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = {
                                    config = config.copy(isEnabled = !config.isEnabled)
                                    configManager.saveConfig(config)
                                }
                            )
                        }
                        .border(2.dp, if (config.isEnabled) Color.Yellow else Color.Red, CircleShape)
                        .padding(8.dp)
                } else Modifier
            )
            .alpha(if (!config.isEnabled && isEditMode) 0.3f else 1f)
    ) {
        content()
    }
}

@Composable
fun oscIconMap() = mapOf(
    "Build" to Icons.Default.Build,
    "Send" to Icons.Default.Send,
    "Favorite" to Icons.Default.Favorite,
    "Star" to Icons.Default.Star,
    "Warning" to Icons.Default.Warning,
    "Home" to Icons.Default.Home,
    "Settings" to Icons.Default.Settings,
    "Person" to Icons.Default.Person,
    "Bolt" to Icons.Default.Bolt,
    "Security" to Icons.Default.Security,
    "FlashOn" to Icons.Default.FlashOn,
    "DirectionsRun" to Icons.Default.DirectionsRun,
    "PanTool" to Icons.Default.PanTool,
    "MyLocation" to Icons.Default.MyLocation,
    "Visibility" to Icons.Default.Visibility,
    "Refresh" to Icons.Default.Refresh,
    "KeyboardArrowDown" to Icons.Default.KeyboardArrowDown,
    "Filter1" to Icons.Default.Filter1,
    "Filter2" to Icons.Default.Filter2,
    "Filter3" to Icons.Default.Filter3,
    "Gavel" to Icons.Default.Gavel
)

@Composable
fun ActionButtons(
    inputContext: VirtualController.ControllerInputContext?,
    isEditMode: Boolean,
    onInputChanged: () -> Unit
) {
    Box(modifier = Modifier.size(200.dp)) {
        // Y (Top)
        Box(modifier = Modifier.align(Alignment.TopCenter)) {
            GamepadButton("X", Color(0xFF39FF14), ControllerPacket.Y_FLAG, inputContext, isEditMode, onInputChanged) // Neon Green
        }
        // A (Bottom)
        Box(modifier = Modifier.align(Alignment.BottomCenter)) {
            GamepadButton("B", md_theme_dark_tertiary, ControllerPacket.A_FLAG, inputContext, isEditMode, onInputChanged) // Neon Cyan
        }
        // X (Left)
        Box(modifier = Modifier.align(Alignment.CenterStart)) {
            GamepadButton("Y", md_theme_dark_secondary, ControllerPacket.X_FLAG, inputContext, isEditMode, onInputChanged) // Neon Pink
        }
        // B (Right)
        Box(modifier = Modifier.align(Alignment.CenterEnd)) {
            GamepadButton("A", Color(0xFFFF3131), ControllerPacket.B_FLAG, inputContext, isEditMode, onInputChanged) // Neon Red
        }
    }
}

@Composable
fun DPad(
    inputContext: VirtualController.ControllerInputContext?,
    isEditMode: Boolean,
    onInputChanged: () -> Unit
) {
    Box(modifier = Modifier.size(160.dp)) {
        // Up
        Box(modifier = Modifier.align(Alignment.TopCenter)) {
            GamepadButton("▲", Color.White, ControllerPacket.UP_FLAG, inputContext, isEditMode, onInputChanged)
        }
        // Down
        Box(modifier = Modifier.align(Alignment.BottomCenter)) {
            GamepadButton("▼", Color.White, ControllerPacket.DOWN_FLAG, inputContext, isEditMode, onInputChanged)
        }
        // Left
        Box(modifier = Modifier.align(Alignment.CenterStart)) {
            GamepadButton("◀", Color.White, ControllerPacket.LEFT_FLAG, inputContext, isEditMode, onInputChanged)
        }
        // Right
        Box(modifier = Modifier.align(Alignment.CenterEnd)) {
            GamepadButton("▶", Color.White, ControllerPacket.RIGHT_FLAG, inputContext, isEditMode, onInputChanged)
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
    isEditMode: Boolean,
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
            .then(
                if (!isEditMode) {
                    Modifier.pointerInteropFilter { event ->
                        when (event.action) {
                            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                                isPressed = true
                                if (flag != -1) inputContext?.inputMap = inputContext?.inputMap?.or(flag) ?: 0
                                if (isLeftTrigger) inputContext?.leftTrigger = 255.toByte()
                                if (isRightTrigger) inputContext?.rightTrigger = 255.toByte()
                                onInputChanged()
                                true
                            }
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                                isPressed = false
                                if (flag != -1) inputContext?.inputMap = inputContext?.inputMap?.and(flag.inv()) ?: 0
                                if (isLeftTrigger) inputContext?.leftTrigger = 0
                                if (isRightTrigger) inputContext?.rightTrigger = 0
                                onInputChanged()
                                true
                            }
                            else -> false
                        }
                    }
                } else Modifier
            )
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
