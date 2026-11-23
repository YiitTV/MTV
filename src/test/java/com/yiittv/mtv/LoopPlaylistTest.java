package com.yiittv.mtv;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoopPlaylistTest {

    @Test
    void parsesPlaylistOrders() {
        assertEquals(PlaybackController.PlaylistOrder.SEQUENCE,
                PlaybackController.PlaylistOrder.parse("sequence"));
        assertEquals(PlaybackController.PlaylistOrder.SEQUENCE,
                PlaybackController.PlaylistOrder.parse(" Sirali "));
        assertEquals(PlaybackController.PlaylistOrder.SHUFFLE,
                PlaybackController.PlaylistOrder.parse("shuffle"));
        assertEquals(PlaybackController.PlaylistOrder.RANDOM,
                PlaybackController.PlaylistOrder.parse(null));
        assertEquals(PlaybackController.PlaylistOrder.RANDOM,
                PlaybackController.PlaylistOrder.parse("anything"));
    }

    @Test
    void loopPlaylistIdsAreValidPlaylistNames() {
        assertEquals("loop_spawn_tabela", ScreenCommand.loopPlaylistId("Spawn_Tabela"));
        String longId = ScreenCommand.loopPlaylistId("a".repeat(32));
        assertEquals(32, longId.length());
        assertTrue(ScreenDefinition.isValidId(longId));
    }
}
