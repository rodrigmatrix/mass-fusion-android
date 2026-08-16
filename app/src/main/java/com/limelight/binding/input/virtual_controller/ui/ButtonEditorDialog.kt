package com.limelight.binding.input.virtual_controller.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.limelight.binding.input.virtual_controller.oscIconMap
import com.limelight.binding.input.virtual_controller.models.CustomButton
import com.limelight.binding.input.virtual_controller.models.ButtonAction
import com.limelight.binding.input.virtual_controller.models.GestureBehavior
import com.limelight.binding.input.KeyboardTranslator

@Composable
fun ButtonEditorDialog(
    initialButton: CustomButton,
    onDismiss: () -> Unit,
    onSave: (CustomButton) -> Unit,
    onDelete: (CustomButton) -> Unit
) {
    var label by remember { mutableStateOf(initialButton.label) }
    var iconName by remember { mutableStateOf(initialButton.iconName) }
    var sizeDp by remember { mutableStateOf(initialButton.sizeDp.coerceIn(40f, 220f)) }
    var opacity by remember { mutableStateOf(initialButton.opacity.coerceIn(0.1f, 1f)) }
    var isTransparentArea by remember { mutableStateOf(initialButton.isTransparentArea) }
    var gestureBehavior by remember { mutableStateOf(initialButton.gestureBehavior) }
    
    // Tap actions
    var onTapKeys by remember { mutableStateOf(initialButton.onTapAction?.keys ?: emptyList()) }
    var onHoldKeys by remember { mutableStateOf(initialButton.onHoldAction?.keys ?: emptyList()) }
    var onReleaseKeys by remember { mutableStateOf(initialButton.onReleaseAction?.keys ?: emptyList()) }
    
    var showIconPicker by remember { mutableStateOf(false) }
    var showKeyPickerFor by remember { mutableStateOf<String?>(null) } // "tap", "hold", "release"

    val availableIcons: Map<String, ImageVector?> = mapOf("None" to null) + oscIconMap()

    val availableKeys = mapOf(
        "A" to KeyboardTranslator.VK_A.toShort(),
        "B" to KeyboardTranslator.VK_B.toShort(),
        "C" to KeyboardTranslator.VK_C.toShort(),
        "D" to KeyboardTranslator.VK_D.toShort(),
        "F" to 0x46.toShort(),
        "Q" to KeyboardTranslator.VK_Q.toShort(),
        "W" to KeyboardTranslator.VK_W.toShort(),
        "E" to KeyboardTranslator.VK_E.toShort(),
        "R" to KeyboardTranslator.VK_R.toShort(),
        "V" to KeyboardTranslator.VK_V.toShort(),
        "X" to KeyboardTranslator.VK_X.toShort(),
        "Y" to 0x59.toShort(), // Y
        "Z" to KeyboardTranslator.VK_Z.toShort(),
        "1" to 0x31.toShort(),
        "2" to 0x32.toShort(),
        "3" to 0x33.toShort(),
        "4" to 0x34.toShort(),
        "5" to 0x35.toShort(),
        "Space" to KeyboardTranslator.VK_SPACE.toShort(),
        "Shift" to KeyboardTranslator.VK_LSHIFT.toShort(),
        "Ctrl" to KeyboardTranslator.VK_LCONTROL.toShort(),
        "Tab" to KeyboardTranslator.VK_TAB.toShort(),
        "Esc" to KeyboardTranslator.VK_ESCAPE.toShort(),
        "Enter" to KeyboardTranslator.VK_RETURN.toShort(),
        "Left Mouse Click" to 0x1001.toShort(),
        "Right Mouse Click" to 0x1002.toShort(),
        "Middle Mouse Click" to 0x1003.toShort()
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Advanced Button Editor") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = isTransparentArea,
                        onCheckedChange = { isTransparentArea = it }
                    )
                    Text("Invisible Gesture Area")
                }
                
                if (isTransparentArea) {
                    Text("Drag Behavior:")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { gestureBehavior = GestureBehavior.NONE }, colors = ButtonDefaults.buttonColors(containerColor = if (gestureBehavior == GestureBehavior.NONE) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary)) { Text("None") }
                        Button(onClick = { gestureBehavior = GestureBehavior.RIGHT_JOYSTICK }, colors = ButtonDefaults.buttonColors(containerColor = if (gestureBehavior == GestureBehavior.RIGHT_JOYSTICK) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary)) { Text("Joystick") }
                        Button(onClick = { gestureBehavior = GestureBehavior.MOUSE_LOOK }, colors = ButtonDefaults.buttonColors(containerColor = if (gestureBehavior == GestureBehavior.MOUSE_LOOK) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary)) { Text("Mouse") }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                } else {
                    OutlinedTextField(
                        value = label,
                        onValueChange = { label = it },
                        label = { Text("Text Label") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Icon: ${iconName.ifBlank { "None" }}")
                        Button(onClick = { showIconPicker = true }) {
                            Text("Change Icon")
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text("Size: ${sizeDp.toInt()} dp")
                Slider(
                    value = sizeDp,
                    onValueChange = { sizeDp = it },
                    valueRange = 40f..220f,
                    steps = 17
                )

                Text("Opacity: ${(opacity * 100).toInt()}%")
                Slider(
                    value = opacity,
                    onValueChange = { opacity = it },
                    valueRange = 0.1f..1f,
                    steps = 8
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                Divider()
                Spacer(modifier = Modifier.height(16.dp))
                
                // Key Bindings
                Text("Key Combos:")
                
                @Composable
                fun renderKeyComboRow(title: String, keys: List<Short>, type: String) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(title)
                        Button(onClick = { showKeyPickerFor = type }) {
                            val names = keys.mapNotNull { k -> availableKeys.entries.find { it.value == k }?.key }
                            Text(if (names.isEmpty()) "None" else names.joinToString(" + "))
                        }
                    }
                }
                
                renderKeyComboRow("On Tap:", onTapKeys, "tap")
                renderKeyComboRow("On Hold:", onHoldKeys, "hold")
                renderKeyComboRow("On Release:", onReleaseKeys, "release")
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    initialButton.copy(
                        label = label,
                        iconName = iconName,
                        sizeDp = sizeDp,
                        opacity = opacity,
                        isTransparentArea = isTransparentArea,
                        gestureBehavior = gestureBehavior,
                        onTapAction = if (onTapKeys.isNotEmpty()) ButtonAction(onTapKeys) else null,
                        onHoldAction = if (onHoldKeys.isNotEmpty()) ButtonAction(onHoldKeys) else null,
                        onReleaseAction = if (onReleaseKeys.isNotEmpty()) ButtonAction(onReleaseKeys) else null,
                        keyBindings = emptyList() // clear legacy
                    )
                )
            }) {
                Text("Save")
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onDelete(initialButton) }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        }
    )

    if (showIconPicker) {
        AlertDialog(
            onDismissRequest = { showIconPicker = false },
            title = { Text("Select Icon") },
            text = {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.height(300.dp)
                ) {
                    items(availableIcons.keys.toList()) { name ->
                        val icon = availableIcons[name]
                        Button(
                            onClick = { 
                                iconName = if (name == "None") "" else name
                                showIconPicker = false 
                            },
                            modifier = Modifier.padding(4.dp)
                        ) {
                            if (icon != null) {
                                Icon(icon, contentDescription = name)
                            } else {
                                Text("None")
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showIconPicker = false }) { Text("Close") }
            }
        )
    }

    if (showKeyPickerFor != null) {
        AlertDialog(
            onDismissRequest = { showKeyPickerFor = null },
            title = { Text("Build Key Combo") },
            text = {
                val currentKeys = when (showKeyPickerFor) {
                    "tap" -> onTapKeys
                    "hold" -> onHoldKeys
                    "release" -> onReleaseKeys
                    else -> emptyList()
                }
                
                Column {
                    Text("Current Combo: " + currentKeys.mapNotNull { k -> availableKeys.entries.find { it.value == k }?.key }.joinToString(" + "))
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = { 
                        when (showKeyPickerFor) {
                            "tap" -> onTapKeys = emptyList()
                            "hold" -> onHoldKeys = emptyList()
                            "release" -> onReleaseKeys = emptyList()
                        }
                    }) { Text("Clear Combo") }
                    
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        modifier = Modifier.height(300.dp)
                    ) {
                        items(availableKeys.keys.toList()) { name ->
                            val keyCode = availableKeys[name]!!
                            Button(
                                onClick = { 
                                    when (showKeyPickerFor) {
                                        "tap" -> onTapKeys = onTapKeys + keyCode
                                        "hold" -> onHoldKeys = onHoldKeys + keyCode
                                        "release" -> onReleaseKeys = onReleaseKeys + keyCode
                                    }
                                },
                                modifier = Modifier.padding(4.dp)
                            ) {
                                Text(name)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showKeyPickerFor = null }) { Text("Done") }
            }
        )
    }
}
