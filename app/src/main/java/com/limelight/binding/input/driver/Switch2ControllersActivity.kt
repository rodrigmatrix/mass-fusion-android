package com.limelight.binding.input.driver

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class Switch2ControllersActivity : Activity() {
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Switch 2 Controllers"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        Button(this).apply {
            text = "Pair new controller"
            setOnClickListener {
                startActivity(Intent(this@Switch2ControllersActivity, BlePairingActivity::class.java))
            }
            root.addView(this, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        ScrollView(this).apply {
            addView(list)
            root.addView(this, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        renderControllers()
    }

    private fun renderControllers() {
        list.removeAllViews()
        val controllers = Switch2ControllerMappings.getPairedControllers(this)
        if (controllers.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "No paired Switch 2 controllers"
                textSize = 18f
                setPadding(0, 32, 0, 0)
            })
            return
        }

        for (address in controllers) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 32, 0, 24)
            }
            row.addView(TextView(this).apply {
                text = Switch2ControllerMappings.controllerName(this@Switch2ControllersActivity, address)
                textSize = 20f
            })
            row.addView(TextView(this).apply {
                text = address
                textSize = 14f
            })

            val buttons = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            buttons.addView(Button(this).apply {
                text = "Settings"
                setOnClickListener {
                    startActivity(
                        Intent(this@Switch2ControllersActivity, Switch2ControllerSettingsActivity::class.java)
                            .putExtra(Switch2ControllerSettingsActivity.EXTRA_ADDRESS, address),
                    )
                }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            buttons.addView(Button(this).apply {
                text = "Forget"
                setOnClickListener {
                    Switch2ControllerMappings.removePairedController(this@Switch2ControllersActivity, address)
                    renderControllers()
                }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(buttons)
            list.addView(row, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
    }
}
