package com.limelight.binding.input.driver

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

class Switch2ControllerSettingsActivity : Activity() {
    private lateinit var address: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        address = intent.getStringExtra(EXTRA_ADDRESS) ?: run {
            finish()
            return
        }
        title = "Controller Settings"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        root.addView(TextView(this).apply {
            text = Switch2ControllerMappings.controllerName(this@Switch2ControllerSettingsActivity, address)
            textSize = 20f
        })
        root.addView(TextView(this).apply {
            text = address
            textSize = 14f
            setPadding(0, 0, 0, 24)
        })

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        for (source in Switch2ControllerMappings.sourceButtons(this, address)) {
            content.addView(createMappingRow(source))
            if (source.editableRawMask) {
                content.addView(createRawMaskRow(source))
            }
        }

        ScrollView(this).apply {
            addView(content)
            root.addView(this, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }

        Button(this).apply {
            text = "Done"
            setOnClickListener { finish() }
            root.addView(this, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        setContentView(root)
    }

    private fun createMappingRow(source: Switch2ControllerMappings.SourceButton): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 8, 0, 8)
        }
        row.addView(TextView(this).apply {
            text = source.label
            textSize = 16f
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val targets = Switch2ControllerMappings.targetButtons
        val spinner = Spinner(this)
        spinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            targets.map { it.label },
        )
        val currentTarget = Switch2ControllerMappings.targetFor(this, address, source)
        spinner.setSelection(targets.indexOfFirst { it.flag == currentTarget }.coerceAtLeast(0))
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                Switch2ControllerMappings.setTarget(
                    this@Switch2ControllerSettingsActivity,
                    address,
                    source.id,
                    targets[position].flag,
                )
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        row.addView(spinner, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    private fun createRawMaskRow(source: Switch2ControllerMappings.SourceButton): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(32, 0, 0, 16)
        }
        row.addView(TextView(this).apply {
            text = "${source.label} raw mask"
            textSize = 14f
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            hint = "0x00000000"
            setSingleLine(true)
            setText(Switch2ControllerMappings.rawMaskText(this@Switch2ControllerSettingsActivity, address, source.id))
        }
        row.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Button(this).apply {
            text = "Save"
            setOnClickListener {
                try {
                    val mask = Switch2ControllerMappings.parseRawMask(input.text.toString())
                    Switch2ControllerMappings.setRawMask(this@Switch2ControllerSettingsActivity, address, source.id, mask)
                    Toast.makeText(this@Switch2ControllerSettingsActivity, "Saved", Toast.LENGTH_SHORT).show()
                } catch (_: NumberFormatException) {
                    Toast.makeText(this@Switch2ControllerSettingsActivity, "Use a hex value like 0x4000", Toast.LENGTH_LONG).show()
                }
            }
        })
        return row
    }

    companion object {
        const val EXTRA_ADDRESS = "address"
    }
}
