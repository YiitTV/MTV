package com.yiittv.mtv;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

/** Feet position plus eye height and unit look direction, captured on the main thread. */
record ViewerPosition(Player player, World world, double x, double y, double z,
                      double eyeY, double lookX, double lookY, double lookZ) {

    static ViewerPosition capture(Player player) {
        Location location = player.getLocation();
        Vector look = location.getDirection();
        return new ViewerPosition(
                player,
                location.getWorld(),
                location.getX(),
                location.getY(),
                location.getZ(),
                location.getY() + player.getEyeHeight(),
                look.getX(),
                look.getY(),
                look.getZ());
    }
}
