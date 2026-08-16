package com.limelight.binding.input.virtual_controller.models

import com.limelight.binding.input.virtual_controller.GamepadElementConfig
import java.util.UUID

data class OscProfile(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    var isDefault: Boolean = false,
    var standardElements: MutableMap<String, GamepadElementConfig> = mutableMapOf(),
    var customButtons: MutableList<CustomButton> = mutableListOf()
)
