package com.yiittv.mtv;

import org.bukkit.World;

/** Voice-chat backend used for positional screen audio. */
interface ScreenAudioOutput {

    boolean ready();

    /** PCM sample rate expected by {@link AudioFrameEncoder#encode(short[])}. */
    int sampleRate();

    String status();

    /** Creates an encoder for 20 ms mono 16-bit frames, or {@code null}. */
    AudioFrameEncoder createEncoder();

    ScreenAudioChannel open(World world, double x, double y, double z,
                            ScreenAudioChannel.DistanceSupplier distance);

    void shutdown();

    interface AudioFrameEncoder extends AutoCloseable {
        byte[] encode(short[] samples);

        @Override
        void close();
    }
}
