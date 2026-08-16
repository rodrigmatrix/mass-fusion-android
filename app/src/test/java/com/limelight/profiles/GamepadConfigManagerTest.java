package com.limelight.profiles;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import com.limelight.TestLogSuppressor;
import com.limelight.binding.input.virtual_controller.GamepadConfigManager;
import com.limelight.binding.input.virtual_controller.models.OscProfile;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

@Config(sdk = {33})
@RunWith(RobolectricTestRunner.class)
public class GamepadConfigManagerTest {
    private Context context;

    @BeforeClass
    public static void suppressLogs() {
        TestLogSuppressor.install();
    }

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        context.getSharedPreferences("GamepadProfiles", Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit();
    }

    @Test
    public void loadProfiles_addsBuiltInProfiles() {
        GamepadConfigManager manager = new GamepadConfigManager(context);

        assertNotNull(manager.getActiveProfile().getValue());
        assertTrue(manager.getProfiles().getValue().stream()
                .anyMatch(profile -> "MOBA / Wild Rift".equals(profile.getName())));
        assertTrue(manager.getProfiles().getValue().stream()
                .anyMatch(profile -> "Shooter / Red Dead".equals(profile.getName())));
        assertTrue(manager.getProfiles().getValue().stream()
                .anyMatch(profile -> "Stealth Action / Assassin".equals(profile.getName())));
    }

    @Test
    public void duplicateProfile_copiesTemplateAsEditableActiveProfile() {
        GamepadConfigManager manager = new GamepadConfigManager(context);
        OscProfile moba = manager.getProfiles().getValue().stream()
                .filter(profile -> "MOBA / Wild Rift".equals(profile.getName()))
                .findFirst()
                .orElseThrow(AssertionError::new);

        OscProfile copy = manager.duplicateProfile(moba.getId(), "My MOBA");

        assertNotNull(copy);
        assertEquals("My MOBA", copy.getName());
        assertFalse(copy.isDefault());
        assertEquals(copy.getId(), manager.getActiveProfile().getValue().getId());
        assertEquals(moba.getCustomButtons().size(), copy.getCustomButtons().size());
        assertNotEquals(moba.getCustomButtons().get(0).getId(), copy.getCustomButtons().get(0).getId());
    }
}
