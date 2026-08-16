package com.limelight.binding.input.virtual_controller.models

import java.util.UUID

enum class GestureBehavior {
    NONE,
    RIGHT_JOYSTICK,
    MOUSE_LOOK,
    MOUSE_CLICK
}

data class ButtonAction(
    val keys: List<Short> = emptyList() // List of keys to press in this combo
)

data class CustomButton(
    val id: String = UUID.randomUUID().toString(),
    var x: Float = 0f,
    var y: Float = 0f,
    var sizeDp: Float = 60f,
    var opacity: Float = 0.7f,
    var scale: Float = 1.0f,
    var label: String = "",
    var iconName: String = "",
    
    // Legacy support, we can migrate to the new actions
    var keyBindings: List<Short> = emptyList(), 
    
    // Advanced Actions
    var onTapAction: ButtonAction? = null,
    var onHoldAction: ButtonAction? = null,
    var onReleaseAction: ButtonAction? = null,
    
    // Invisible Area settings
    var isTransparentArea: Boolean = false,
    var gestureBehavior: GestureBehavior = GestureBehavior.NONE,
    
    var isEnabled: Boolean = true
)
