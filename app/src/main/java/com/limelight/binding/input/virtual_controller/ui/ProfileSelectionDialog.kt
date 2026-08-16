package com.limelight.binding.input.virtual_controller.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.limelight.binding.input.virtual_controller.GamepadConfigManager
import com.limelight.binding.input.virtual_controller.VirtualControllerState
import com.limelight.binding.input.virtual_controller.models.OscProfile
import kotlinx.coroutines.flow.update

@Composable
fun ProfileSelectionDialog(configManager: GamepadConfigManager) {
    val profiles by configManager.profiles.collectAsState()
    val activeProfile by configManager.activeProfile.collectAsState()
    var showNewProfileDialog by remember { mutableStateOf(false) }
    var newProfileName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { VirtualControllerState.showProfileSelectionDialog.update { false } },
        title = { Text("Select OSC Profile") },
        text = {
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                LazyColumn {
                    items(profiles) { profile ->
                        val isActive = profile.id == activeProfile?.id
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { configManager.setActiveProfile(profile.id) }
                                .padding(vertical = 12.dp, horizontal = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = profile.name + (if (isActive) " (Active)" else ""),
                                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                                color = if (isActive) MaterialTheme.colorScheme.primary else Color.Unspecified,
                                fontSize = 16.sp
                            )
                            
                            Row {
                                IconButton(onClick = { configManager.duplicateProfile(profile.id) }) {
                                    Icon(Icons.Default.ContentCopy, contentDescription = "Duplicate Profile")
                                }
                                if (!profile.isDefault) {
                                    IconButton(onClick = { configManager.deleteProfile(profile.id) }) {
                                        Icon(Icons.Default.Delete, contentDescription = "Delete Profile")
                                    }
                                }
                            }
                        }
                        Divider()
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Button(
                    onClick = { showNewProfileDialog = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Create New Profile")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { VirtualControllerState.showProfileSelectionDialog.update { false } }) {
                Text("Close")
            }
        }
    )

    if (showNewProfileDialog) {
        AlertDialog(
            onDismissRequest = { showNewProfileDialog = false },
            title = { Text("New Profile") },
            text = {
                OutlinedTextField(
                    value = newProfileName,
                    onValueChange = { newProfileName = it },
                    label = { Text("Profile Name") }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newProfileName.isNotBlank()) {
                        configManager.addProfile(newProfileName)
                        showNewProfileDialog = false
                        newProfileName = ""
                    }
                }) {
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(onClick = { showNewProfileDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
