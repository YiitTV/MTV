package com.yiittv.mtv;

/** A positional audio emitter placed at the centre of one screen. */
interface ScreenAudioChannel {

    /** Queues one encoded 20 ms frame. Called from decoder threads. */
    void offer(byte[] frame);

    /** Drops queued frames, for example after the screen switched source. */
    void flush();

    void close();

    default String diagnostics() {
        return "";
    }

    @FunctionalInterface
    interface DistanceSupplier {
        int distance();
    }
}
