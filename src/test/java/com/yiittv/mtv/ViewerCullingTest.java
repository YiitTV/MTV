package com.yiittv.mtv;

import org.bukkit.block.BlockFace;
import org.bukkit.util.BlockVector;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ViewerCullingTest {

    @Test
    void playersBehindTheWallAreCulled() {
        // Wall block at z=10, picture facing north (towards -z).
        BlockVector anchor = new BlockVector(0, 64, 10);
        assertTrue(ViewerCulling.inFront(anchor, BlockFace.NORTH, 0.5, 5));
        assertTrue(ViewerCulling.inFront(anchor, BlockFace.NORTH, 0.5, 9.7));
        assertFalse(ViewerCulling.inFront(anchor, BlockFace.NORTH, 0.5, 11.3));
        assertFalse(ViewerCulling.inFront(anchor, BlockFace.NORTH, 0.5, 20));

        // Facing east (towards +x).
        assertTrue(ViewerCulling.inFront(anchor, BlockFace.EAST, 4, 10));
        assertFalse(ViewerCulling.inFront(anchor, BlockFace.EAST, -2, 10));
    }

    @Test
    void playersLookingAwayAreCulled() {
        // Screen box spanning x 0..7, y 60..64, z 10..11; viewer 20 blocks north of it.
        double[] box = {0, 60, 10, 7, 64, 11};
        assertTrue(looks(3.5, 62, -10, 0, 0, 1, box));    // straight at it
        assertTrue(looks(3.5, 62, -10, 1, 0, 0, box));    // 90 degrees to the side
        assertFalse(looks(3.5, 62, -10, 0, 0, -1, box));  // back turned
        assertFalse(looks(3.5, 62, -10, -0.7, 0, -0.7, box));
    }

    @Test
    void closeViewersAlwaysReceiveUpdates() {
        double[] box = {0, 60, 10, 7, 64, 11};
        assertTrue(looks(3.5, 62, 8.5, 0, 0, -1, box));
    }

    @Test
    void onlyStillImagesAreDithered() {
        for (SourceType type : SourceType.values()) {
            assertEquals(type == SourceType.IMAGE || type == SourceType.URL_IMAGE,
                    ManagedScreen.ditheringAllowed(type), type.name());
        }
    }

    private static boolean looks(double x, double y, double z,
                                 double lookX, double lookY, double lookZ, double[] box) {
        return ViewerCulling.looksTowards(x, y, z, lookX, lookY, lookZ,
                box[0], box[1], box[2], box[3], box[4], box[5], 105);
    }
}
