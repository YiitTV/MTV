package com.yiittv.mtv;

import java.util.function.Consumer;

/**
 * Splits decoded 16-bit mono PCM into fixed 20 ms frames and applies a volume factor.
 * Not thread-safe; owned by one decoder.
 */
final class AudioFramer {

    private final short[] frame;
    private final double volume;
    private int filled;

    AudioFramer(int sampleRate, double volume) {
        this.frame = new short[Math.max(1, sampleRate / 50)];
        this.volume = Math.max(0, Math.min(2, volume));
    }

    int frameSize() {
        return frame.length;
    }

    void accept(short[] samples, int count, Consumer<short[]> output) {
        int limit = Math.min(count, samples.length);
        for (int index = 0; index < limit; index++) {
            frame[filled++] = scale(samples[index]);
            if (filled == frame.length) {
                output.accept(frame.clone());
                filled = 0;
            }
        }
    }

    void reset() {
        filled = 0;
    }

    private short scale(short sample) {
        if (volume == 1) return sample;
        long scaled = Math.round(sample * volume);
        return (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, scaled));
    }
}
