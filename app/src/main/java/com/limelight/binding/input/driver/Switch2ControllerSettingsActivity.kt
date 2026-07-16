package com.limelight.binding.input.driver

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class Switch2ControllerSettingsActivity : ComponentActivity() {
    private lateinit var address: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        address = intent.getStringExtra(EXTRA_ADDRESS) ?: run {
            finish()
            return
        }

        setContent {
            com.limelight.ui.theme.MassFusionTheme {
                ControllerSettingsScreen(
                    address = address,
                    onDone = { finish() }
                )
            }
        }
    }

    companion object {
        const val EXTRA_ADDRESS = "address"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ControllerSettingsScreen(address: String, onDone: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val controllerName = remember { Switch2ControllerMappings.controllerName(context, address) }
    val sources = remember { Switch2ControllerMappings.sourceButtons(context, address) }
    
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Controller Settings") })
        },
        bottomBar = {
            Box(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                    Text("Done")
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text(text = controllerName, style = MaterialTheme.typography.titleLarge)
            Text(text = address, style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.height(24.dp))
            
            sources.forEach { source ->
                MappingRow(address = address, source = source)
                if (source.editableRawMask) {
                    RawMaskRow(address = address, source = source)
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            }
        }
    }
}

@Composable
fun MappingRow(address: String, source: Switch2ControllerMappings.SourceButton) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val targets = Switch2ControllerMappings.targetButtons
    var expanded by remember { mutableStateOf(false) }
    
    val initialTargetFlag = remember { Switch2ControllerMappings.targetFor(context, address, source) }
    val initialTargetIndex = targets.indexOfFirst { it.flag == initialTargetFlag }.coerceAtLeast(0)
    var selectedTarget by remember { mutableStateOf(targets[initialTargetIndex]) }
    
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = source.label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        
        Box(modifier = Modifier.weight(1f)) {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text(selectedTarget.label)
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                targets.forEach { target ->
                    DropdownMenuItem(
                        text = { Text(target.label) },
                        onClick = {
                            selectedTarget = target
                            Switch2ControllerMappings.setTarget(context, address, source.id, target.flag)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun RawMaskRow(address: String, source: Switch2ControllerMappings.SourceButton) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var maskText by remember { mutableStateOf(Switch2ControllerMappings.rawMaskText(context, address, source.id)) }
    
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "${source.label} raw mask",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        
        OutlinedTextField(
            value = maskText,
            onValueChange = { maskText = it },
            singleLine = true,
            modifier = Modifier.weight(1f).padding(end = 8.dp)
        )
        
        Button(
            onClick = {
                try {
                    val mask = Switch2ControllerMappings.parseRawMask(maskText)
                    Switch2ControllerMappings.setRawMask(context, address, source.id, mask)
                    Toast.makeText(context, "Saved", Toast.LENGTH_SHORT).show()
                } catch (_: NumberFormatException) {
                    Toast.makeText(context, "Use a hex value like 0x4000", Toast.LENGTH_LONG).show()
                }
            }
        ) {
            Text("Save")
        }
    }
}
