package com.limelight.utils

class MouseModeOption(
    @JvmField val index: Int,
    @JvmField val label: String
) {
    override fun toString(): String {
        return label
    }
}
