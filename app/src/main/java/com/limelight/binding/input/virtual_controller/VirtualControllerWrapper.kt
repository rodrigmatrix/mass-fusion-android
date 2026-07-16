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
