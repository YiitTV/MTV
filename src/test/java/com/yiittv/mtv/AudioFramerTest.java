package com.yiittv.mtv;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AudioFramerTest {

    @Test
    void splitsPcmIntoTwentyMillisecondFrames() {
        AudioFramer framer = new AudioFramer(48_000, 1);
        assertEquals(960, framer.frameSize());

        List<short[]> frames = new ArrayList<>();
        framer.accept(new short[1500], 1500, frames::add);
        assertEquals(1, frames.size());
        framer.accept(new short[500], 500, frames::add);
        assertEquals(2, frames.size());
        frames.forEach(frame -> assertEquals(960, frame.length));
    }

    @Test
    void respectsTheValidSampleCount() {
        AudioFramer framer = new AudioFramer(24_000, 1);
        List<short[]> frames = new ArrayList<>();
        framer.accept(new short[2000], 480, frames::add);
        assertEquals(1, frames.size());
        assertEquals(480, frames.getFirst().length);
    }

    @Test
    void appliesAndClampsVolume() {
        AudioFramer half = new AudioFramer(50, 0.5);
        List<short[]> frames = new ArrayList<>();
        half.accept(new short[]{1000}, 1, frames::add);
        assertArrayEquals(new short[]{500}, frames.getFirst());

        AudioFramer loud = new AudioFramer(50, 2);
        frames.clear();
        loud.accept(new short[]{30_000}, 1, frames::add);
        assertArrayEquals(new short[]{Short.MAX_VALUE}, frames.getFirst());
    }

    @Test
    void validatesAudioDistanceRange() {
        assertTrue(ScreenAudioManager.validDistance(32));
        assertTrue(ScreenAudioManager.validDistance(512));
        assertFalse(ScreenAudioManager.validDistance(3));
        assertFalse(ScreenAudioManager.validDistance(Double.NaN));
        assertEquals(4, ScreenAudioManager.clampDistance(1));
        assertEquals(512, ScreenAudioManager.clampDistance(10_000));
    }
}
