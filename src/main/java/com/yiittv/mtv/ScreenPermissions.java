package com.yiittv.mtv;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Map;

public final class ScreenPermissions {

    static final String ADMIN = "mtv.admin";
    static final String SEE_ALL = "mtv.see.*";

    private static final Map<String, String> COMMAND_NODES = Map.ofEntries(
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
            Map.entry("menu", "mtv.menu.dashboard"),
            Map.entry("studio", "mtv.menu.dashboard"),
            Map.entry("web", "mtv.web"),
            Map.entry("vote", "mtv.vote"),
            Map.entry("mediamtx", "mtv.mediamtx")
    );

    private ScreenPermissions() {
    }

    static String commandNode(String subcommand) {
        return COMMAND_NODES.get(subcommand);
    }

    static String viewNode(String screenId) {
        return "mtv.see." + ScreenDefinition.normalizeId(screenId);
    }

    static boolean canUse(CommandSender sender, String subcommand) {
        String node = commandNode(subcommand);
        return node == null || sender.hasPermission(ADMIN) || sender.hasPermission(node);
    }

    static boolean canView(Player player, ScreenDefinition definition) {
        return !definition.permissionRequired()
                || player.hasPermission(ADMIN)
                || player.hasPermission(SEE_ALL)
                || player.hasPermission(viewNode(definition.id()));
    }
}
