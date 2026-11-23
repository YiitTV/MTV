package com.yiittv.mtv;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScreenPermissionsTest {

    @Test
    void everySubcommandHasItsOwnPermission() {
        Map<String, String> expected = Map.ofEntries(
                Map.entry("create", "mtv.create"),
                Map.entry("clone", "mtv.clone"),
                Map.entry("list", "mtv.list"),
                Map.entry("start", "mtv.start"),
                Map.entry("stop", "mtv.stop"),
                Map.entry("remove", "mtv.remove"),
                Map.entry("status", "mtv.status"),
                Map.entry("source", "mtv.source"),
                Map.entry("playlist", "mtv.playlist"),
                Map.entry("event", "mtv.event"),
                Map.entry("set", "mtv.set"),
                Map.entry("reload", "mtv.reload"),
                Map.entry("debug", "mtv.debug"),
                Map.entry("web", "mtv.web"),
                Map.entry("mediamtx", "mtv.mediamtx")
        );

        expected.forEach((command, permission) ->
                assertEquals(permission, ScreenPermissions.commandNode(command)));
        assertNull(ScreenPermissions.commandNode("unknown"));
    }

    @Test
    void screenViewPermissionUsesTheNormalizedScreenName() {
        assertEquals("mtv.see.spawn_tv",
                ScreenPermissions.viewNode(" Spawn_TV "));
    }

    @Test
    void pluginYamlExposesGranularPermissionsWithoutBlockingTheRootCommand() {
        var stream = getClass().getClassLoader().getResourceAsStream("plugin.yml");
        assertNotNull(stream);
        YamlConfiguration plugin = YamlConfiguration.loadConfiguration(
                new InputStreamReader(stream, StandardCharsets.UTF_8));

        assertNull(plugin.getString("commands.mtv.permission"));
        assertEquals("MTV", plugin.getString("name"));
        assertTrue(plugin.getStringList("authors").contains("unknown_56"));
        assertTrue(plugin.getStringList("softdepend").contains("PlasmoVoice"));
        assertEquals("op", plugin.getString("permissions.mtv.admin.default"));
        assertTrue(plugin.getBoolean(
                "permissions.mtv.admin.children.mtv.create"));
        assertTrue(plugin.getBoolean(
                "permissions.mtv.admin.children.mtv.source"));
        assertTrue(plugin.getBoolean(
                "permissions.mtv.admin.children.mtv.playlist"));
        assertTrue(plugin.getBoolean(
                "permissions.mtv.admin.children.mtv.event"));
        assertTrue(plugin.getBoolean(
                "permissions.mtv.admin.children.mtv.update"));
        assertTrue(plugin.getBoolean(
                "permissions.mtv.admin.children.mtv.web"));
        assertTrue(plugin.getBoolean(
                "permissions.mtv.admin.children.mtv.see.*"));
        assertFalse(plugin.getBoolean("permissions.mtv.create.default"));
        assertEquals("op", plugin.getString("permissions.mtv.update.default"));
        assertFalse(plugin.getBoolean("permissions.mtv.see.*.default"));
    }

    @Test
    void protectedScreensAcceptSpecificWildcardAndAdminPermissions() {
        ScreenDefinition publicScreen = definition(false);
        ScreenDefinition protectedScreen = definition(true);

        assertTrue(ScreenPermissions.canView(playerWith(), publicScreen));
        assertFalse(ScreenPermissions.canView(playerWith(), protectedScreen));
        assertTrue(ScreenPermissions.canView(
                playerWith("mtv.see.cinema"), protectedScreen));
        assertTrue(ScreenPermissions.canView(
                playerWith(ScreenPermissions.SEE_ALL), protectedScreen));
        assertTrue(ScreenPermissions.canView(
                playerWith(ScreenPermissions.ADMIN), protectedScreen));
    }

    private static ScreenDefinition definition(boolean permissionRequired) {
        return new ScreenDefinition(
                "cinema",
                ScreenSource.rtmp("rtmp://example/screen"),
                8,
                64,
                "world",
                new org.bukkit.util.BlockVector(0, 80, 0),
                7,
                4,
                org.bukkit.block.BlockFace.NORTH,
                true,
                permissionRequired,
                ""
        );
    }

    private static Player playerWith(String... permissions) {
        Set<String> granted = Set.of(permissions);
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[]{Player.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("hasPermission")) {
                        return granted.contains(arguments[0]);
                    }
                    if (method.getName().equals("toString")) {
                        return "PermissionTestPlayer";
                    }
                    if (method.getReturnType().equals(boolean.class)) {
                        return false;
                    }
                    if (method.getReturnType().isPrimitive()) {
                        return 0;
                    }
                    return null;
                }
        );
    }
}
