package com.limelight.binding.input.driver

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class Switch2ControllersActivity : ComponentActivity() {
    private var controllersState = mutableStateOf<List<String>>(emptyList())
    private var combineJoyConsState = mutableStateOf(true)

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            com.limelight.ui.theme.MassFusionTheme {
                val controllers by controllersState
                val combineJoyCons by combineJoyConsState
                
                Scaffold(
                    topBar = {
                        TopAppBar(title = { Text("Switch 2 Controllers") })
                    }
                ) { paddingValues ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(paddingValues)
                            .padding(16.dp)
                    ) {
                        Button(
                            onClick = {
                                startActivity(Intent(this@Switch2ControllersActivity, BlePairingActivity::class.java))
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Pair new controller")
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Card(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Use Joy-Cons as a pair", style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        "Left and right Joy-Con 2 stream as one controller.",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                                Switch(
                                    checked = combineJoyCons,
                                    onCheckedChange = { checked ->
                                        Switch2ControllerMappings.setCombineJoyCons(this@Switch2ControllersActivity, checked)
                                        combineJoyConsState.value = checked
                                        restartBleDriverService()
                                    }
                                )
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        if (controllers.isEmpty()) {
                            Text(
                                text = "No paired Switch 2 controllers",
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(top = 16.dp)
                            )
                        } else {
                            LazyColumn(modifier = Modifier.fillMaxSize()) {
                                items(controllers) { address ->
                                    ControllerRow(
                                        address = address,
                                        name = Switch2ControllerMappings.controllerName(this@Switch2ControllersActivity, address),
                                        onSettings = {
                                            startActivity(
                                                Intent(this@Switch2ControllersActivity, Switch2ControllerSettingsActivity::class.java)
                                                    .putExtra(Switch2ControllerSettingsActivity.EXTRA_ADDRESS, address)
                                            )
                                        },
                                        onForget = {
                                            Switch2ControllerMappings.removePairedController(this@Switch2ControllersActivity, address)
                                            controllersState.value = Switch2ControllerMappings.getPairedControllers(this@Switch2ControllersActivity)
                                        }
                                    )
                                    HorizontalDivider()
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        controllersState.value = Switch2ControllerMappings.getPairedControllers(this)
        combineJoyConsState.value = Switch2ControllerMappings.combineJoyCons(this)
    }

    private fun restartBleDriverService() {
        val intent = Intent(this, BleDriverService::class.java)
        stopService(intent)
        if (Switch2ControllerMappings.getPairedControllers(this).isNotEmpty()) {
            startService(intent)
        }
    }
}

@Composable
fun ControllerRow(
    address: String,
    name: String,
    onSettings: () -> Unit,
    onForget: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
        Text(text = name, style = MaterialTheme.typography.titleLarge)
        Text(text = address, style = MaterialTheme.typography.bodyMedium)
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onSettings, modifier = Modifier.weight(1f)) {
                Text("Settings")
            }
            Button(
                onClick = onForget,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text("Forget")
            }
        }
    }
}
