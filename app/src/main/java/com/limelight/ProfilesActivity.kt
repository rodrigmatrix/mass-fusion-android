package com.limelight

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.limelight.profiles.ProfilesManager
import com.limelight.profiles.SettingsProfile
import com.limelight.utils.UiHelper

class ProfilesActivity : AppCompatActivity(), ProfilesManager.ProfileChangeListener {
    
    private var profiles by mutableStateOf<List<SettingsProfile>>(emptyList())
    private var activeProfile by mutableStateOf<SettingsProfile?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ProfilesManager.getInstance().addListener(this)
        updateUI()

        setContent {
            com.limelight.ui.theme.MassFusionTheme {
                ProfilesScreen(
                    profiles = profiles,
                    activeProfile = activeProfile,
                    onAddProfile = {
                        startActivity(Intent(this@ProfilesActivity, EditProfileActivity::class.java))
                    },
                    onEditProfile = { profile ->
                        val intent = Intent(this@ProfilesActivity, EditProfileActivity::class.java)
                        intent.putExtra("profileUuid", profile.uuid.toString())
                        startActivity(intent)
                    },
                    onDeleteProfile = { profile ->
                        AlertDialog.Builder(this@ProfilesActivity)
                            .setTitle(R.string.profile_manager_delete_profile)
                            .setMessage(getString(R.string.profile_manager_confirm_profile_deleteion, profile.name))
                            .setPositiveButton(R.string.profile_manager_delete) { _, _ ->
                                ProfilesManager.getInstance().delete(profile.uuid)
                                ProfilesManager.getInstance().save(this@ProfilesActivity)
                                Toast.makeText(this@ProfilesActivity, getString(R.string.profile_manager_profile_deleted, profile.name), Toast.LENGTH_SHORT).show()
                            }
                            .setNegativeButton(getString(R.string.cancel), null)
                            .show()
                    },
                    onToggleActive = { profile ->
                        val isActive = activeProfile?.uuid == profile.uuid
                        if (isActive) {
                            ProfilesManager.getInstance().setActive(null)
                            Toast.makeText(this@ProfilesActivity, R.string.profile_manager_deactivated_profile, Toast.LENGTH_SHORT).show()
                        } else {
                            ProfilesManager.getInstance().setActive(profile.uuid)
                            Toast.makeText(this@ProfilesActivity, getString(R.string.profile_manager_activated_profile, profile.name), Toast.LENGTH_SHORT).show()
                        }
                        ProfilesManager.getInstance().save(this@ProfilesActivity)
                    }
                )
            }
        }

        UiHelper.notifyNewRootView(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        ProfilesManager.getInstance().removeListener(this)
    }

    override fun onProfilesChanged() {
        runOnUiThread { updateUI() }
    }

    private fun updateUI() {
        profiles = ProfilesManager.getInstance().profiles.toList()
        activeProfile = ProfilesManager.getInstance().active
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfilesScreen(
    profiles: List<SettingsProfile>,
    activeProfile: SettingsProfile?,
    onAddProfile: () -> Unit,
    onEditProfile: (SettingsProfile) -> Unit,
    onDeleteProfile: (SettingsProfile) -> Unit,
    onToggleActive: (SettingsProfile) -> Unit
) {
    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = onAddProfile) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.profile_manager_new_profile))
            }
        }
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues).fillMaxSize()) {
            if (profiles.isEmpty()) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = stringResource(R.string.profile_manager_no_profiles_yet),
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.profile_manager_tap_create_profile),
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    Text(
                        text = stringResource(R.string.profile_manager_profiles_description),
                        modifier = Modifier.padding(16.dp),
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(profiles) { profile ->
                            ProfileRow(
                                profile = profile,
                                isActive = activeProfile?.uuid == profile.uuid,
                                onEdit = { onEditProfile(profile) },
                                onDelete = { onDeleteProfile(profile) },
                                onToggleActive = { onToggleActive(profile) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ProfileRow(
    profile: SettingsProfile,
    isActive: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggleActive: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = profile.name,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
            Spacer(modifier = Modifier.height(4.dp))
            val timestamp = DateUtils.getRelativeTimeSpanString(
                profile.modifiedUtc, 
                System.currentTimeMillis(), 
                DateUtils.MINUTE_IN_MILLIS
            ).toString()
            Text(
                text = timestamp,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        
        RadioButton(
            selected = isActive,
            onClick = onToggleActive,
            modifier = Modifier.padding(horizontal = 8.dp)
        )
        
        IconButton(onClick = onEdit) {
            Icon(Icons.Default.Edit, contentDescription = "Edit profile")
        }
        
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, contentDescription = "Delete profile")
        }
    }
}
