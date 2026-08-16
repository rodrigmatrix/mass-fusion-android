package com.limelight.binding.input.virtual_controller

import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView

object VirtualControllerWrapper {
    @JvmStatic
    fun attachComposeView(
        viewGroup: ViewGroup,
        virtualController: VirtualController
    ): ComposeView {
        val composeView = ComposeView(viewGroup.context).apply {
            setContent {
                VirtualControllerCompose(
                    inputContext = virtualController.controllerInputContext,
                    onInputChanged = {
                        virtualController.sendControllerInputContext()
                    },
                    onKeyboardInput = { keyCode, isDown ->
                        val direction = if (isDown) 0.toByte() else 1.toByte() // 0 is KEY_DOWN, 1 is KEY_UP
                        virtualController.sendKeyboardInput(keyCode, direction, 0.toByte(), 0.toByte())
                    },
                    onMouseMove = { dx, dy ->
                        virtualController.sendMouseMove(dx.toShort(), dy.toShort())
                    },
                    onMouseButton = { button, isDown ->
                        if (isDown) virtualController.sendMouseButtonDown(button) else virtualController.sendMouseButtonUp(button)
                    }
                )
            }
        }
        viewGroup.addView(composeView, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        return composeView
    }
}
