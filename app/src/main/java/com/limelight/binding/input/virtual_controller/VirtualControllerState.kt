package com.limelight.binding.input.virtual_controller

import kotlinx.coroutines.flow.MutableStateFlow

object VirtualControllerState {
    val isEditMode = MutableStateFlow(false)
    val showProfileSelectionDialog = MutableStateFlow(false)

    @JvmStatic
    fun enableEditMode() {
        isEditMode.value = true
    }

    @JvmStatic
    fun showProfileSelection() {
        showProfileSelectionDialog.value = true
    }
}
