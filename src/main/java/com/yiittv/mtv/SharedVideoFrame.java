package com.yiittv.mtv;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.awt.image.DirectColorModel;
import java.awt.image.WritableRaster;
import java.util.concurrent.atomic.AtomicInteger;

public final class SharedVideoFrame {

    private static final int[] RGB_MASKS = {0x00ff0000, 0x0000ff00, 0x000000ff};
    private static final DirectColorModel RGB_MODEL =
            new DirectColorModel(24, RGB_MASKS[0], RGB_MASKS[1], RGB_MASKS[2]);

    private final BufferedImage image;
    /** Fully opaque 0xFFRRGGBB pixels, row-major without padding; null for other images. */
    private final int[] argb;
    private final AtomicInteger references = new AtomicInteger(1);

    SharedVideoFrame(BufferedImage image) {
        this(image, null);
    }

    private SharedVideoFrame(BufferedImage image, int[] argb) {
        this.image = image;
        this.argb = argb;
    }

    /**
     * Wraps decoded pixels without copying them. Every pixel must have alpha 0xFF, so
     * screens can copy rows straight into MapEngine buffers.
     */
    static SharedVideoFrame opaque(int[] argb, int width, int height) {
        if (argb.length != width * height) {
            throw new IllegalArgumentException("pixel count does not match " + width + "x" + height);
        }
        WritableRaster raster = WritableRaster.createPackedRaster(
                new DataBufferInt(argb, argb.length), width, height, width, RGB_MASKS, null);
        return new SharedVideoFrame(new BufferedImage(RGB_MODEL, raster, false, null), argb);
    }

    BufferedImage image() {
        return image;
    }

    /** Opaque ARGB pixels of {@link #image()}, or null when the frame has none. */
    int[] opaquePixels() {
        return argb;
    }

    SharedVideoFrame retain() {
        references.incrementAndGet();
        return this;
    }

    void release() {
        if (references.decrementAndGet() == 0) {
            image.flush();
        }
    }
}
