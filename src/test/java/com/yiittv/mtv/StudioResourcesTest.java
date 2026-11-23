package com.yiittv.mtv;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StudioResourcesTest {

    @Test
    void pluginDescriptorContainsStudioRolesAndVoting() {
        var stream = getClass().getClassLoader().getResourceAsStream("plugin.yml");
        assertNotNull(stream);
        YamlConfiguration plugin = YamlConfiguration.loadConfiguration(
                new InputStreamReader(stream, StandardCharsets.UTF_8));

        assertNotNull(plugin.getConfigurationSection("permissions.mtv.menu.*"));
        assertNotNull(plugin.getConfigurationSection(
                "permissions.mtv.menu.emergency"));
        assertNotNull(plugin.getConfigurationSection(
                "permissions.mtv.menu.configuration"));
        assertNotNull(plugin.getConfigurationSection(
                "permissions.mtv.menu.monitoring"));
        assertNotNull(plugin.getConfigurationSection("permissions.mtv.web"));
        assertNotNull(plugin.getConfigurationSection("permissions.mtv.vote"));
        assertTrue(plugin.getBoolean(
                "permissions.mtv.admin.children.mtv.menu.*"));
        assertTrue(plugin.getBoolean(
                "permissions.mtv.admin.children.mtv.vote"));
    }

    @Test
    void playbackSnapshotUsesImmutableEmptyCollectionsForDirectMode() {
        PlaybackSnapshot snapshot = PlaybackSnapshot.direct("configured source");
        assertTrue(snapshot.queue().isEmpty());
        assertTrue(snapshot.history().isEmpty());
    }
}
