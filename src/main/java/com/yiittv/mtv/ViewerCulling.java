package com.yiittv.mtv;

import org.bukkit.block.BlockFace;
import org.bukkit.util.BlockVector;

/**
 * Decides which nearby players actually need map packets. Players behind the wall can never
 * see the picture, and players facing away cannot see it until they turn around; both still
 * keep their spawned item frames, they only stop receiving pixel updates. Pure geometry so it
 * can be tested without a server.
 */
final class ViewerCulling {

    /** Players closer than this to the screen surface always receive updates. */
    static final double ALWAYS_VISIBLE_DISTANCE = 2.5;

    private ViewerCulling() {
    }

    /**
     * True when the viewer is in front of the screen. The plane is the back face of the screen's
     * anchor block, which is behind the picture whether the anchor is the wall block or the air
     * block the frames hang in, so nobody in front is ever culled.
     */
    static boolean inFront(BlockVector anchor, BlockFace facing, double x, double z) {
        if (anchor == null || facing == null) {
            return true;
        }
        double normalX = facing.getModX();
        double normalZ = facing.getModZ();
        double planeX = anchor.getBlockX() + 0.5 - 0.5 * normalX;
        double planeZ = anchor.getBlockZ() + 0.5 - 0.5 * normalZ;
        return (x - planeX) * normalX + (z - planeZ) * normalZ > -0.1;
    }

    /**
     * True when any part of the screen box lies within {@code maxAngleDegrees} of the look
     * direction. The nearest point of the box is used, so wide screens count as seen as soon
     * as one edge is near the view.
     */
    static boolean looksTowards(double eyeX, double eyeY, double eyeZ,
                                double lookX, double lookY, double lookZ,
                                double minX, double minY, double minZ,
                                double maxX, double maxY, double maxZ,
                                double maxAngleDegrees) {
        double lookLength = Math.sqrt(lookX * lookX + lookY * lookY + lookZ * lookZ);
        if (lookLength < 1.0e-6 || maxAngleDegrees >= 180) {
            return true;
        }
        double nearestX = clamp(eyeX, minX, maxX) - eyeX;
        double nearestY = clamp(eyeY, minY, maxY) - eyeY;
        double nearestZ = clamp(eyeZ, minZ, maxZ) - eyeZ;
        double distance = Math.sqrt(nearestX * nearestX + nearestY * nearestY + nearestZ * nearestZ);
        if (distance <= ALWAYS_VISIBLE_DISTANCE) {
            return true;
        }
        double cosine = (nearestX * lookX + nearestY * lookY + nearestZ * lookZ)
                / (distance * lookLength);
        return cosine >= Math.cos(Math.toRadians(Math.max(1, maxAngleDegrees)));
    }

    private static double clamp(double value, double min, double max) {
        return value < min ? min : Math.min(value, max);
    }
}
