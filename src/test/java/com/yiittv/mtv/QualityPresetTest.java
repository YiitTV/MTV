package com.yiittv.mtv;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QualityPresetTest {

    @Test
    void parsesPresetsInEnglishAndTurkish() {
        assertEquals(QualityPreset.LOW, QualityPreset.parse("dusuk"));
        assertEquals(QualityPreset.MEDIUM, QualityPreset.parse("Medium"));
        assertEquals(QualityPreset.HIGH, QualityPreset.parse("yuksek"));
        assertEquals(QualityPreset.ULTRA, QualityPreset.parse("ultra"));
        assertNull(QualityPreset.parse("best"));
        assertTrue(QualityPreset.HIGH.dithering());
        assertEquals(4, QualityPreset.LOW.fps());
    }

    @Test
    void buildsResolutionCappedFormats() {
        assertEquals("best[height<=720]/best", QualityPreset.liveFormat(720));
        assertTrue(QualityPreset.youtubeFormat(360).startsWith("18/"));
        assertTrue(QualityPreset.youtubeFormat(1080).contains("[height<=1080]"));
        assertTrue(QualityPreset.youtubeFormat(1080).endsWith("/b"));
    }
}
