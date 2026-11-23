package com.yiittv.mtv;

import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.ffmpeg.global.swscale;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrameScalerTest {

    @Test
    void fitsInsideTheScreenWithoutUpscaling() {
        assertArrayEquals(new int[]{896, 504},
                FfmpegSourceWorker.fitInside(1920, 1080, new int[]{896, 512}));
        assertArrayEquals(new int[]{640, 360},
                FfmpegSourceWorker.fitInside(640, 360, new int[]{896, 512}));
        assertArrayEquals(new int[]{1280, 720},
                FfmpegSourceWorker.fitInside(1280, 720, new int[]{0, 0}));
    }

    @Test
    void grabberScalesDecodedVideoToOpaqueScreenPixels(@TempDir Path directory) throws Exception {
        Path video = directory.resolve("clip.mp4");
        try (FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(video.toFile(), 1280, 720);
             Java2DFrameConverter converter = new Java2DFrameConverter()) {
            recorder.setFormat("mp4");
            recorder.setFrameRate(10);
            recorder.start();
            for (int index = 0; index < 5; index++) {
                BufferedImage image = new BufferedImage(1280, 720, BufferedImage.TYPE_3BYTE_BGR);
                Graphics2D graphics = image.createGraphics();
                graphics.setColor(new Color(200, 40, 40));
                graphics.fillRect(0, 0, 1280, 720);
                graphics.dispose();
                recorder.record(converter.convert(image));
            }
            recorder.stop();
        }

        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(video.toFile())) {
            grabber.setPixelFormat(avutil.AV_PIX_FMT_BGRA);
            grabber.setImageScalingFlags(swscale.SWS_BILINEAR);
            grabber.start();
            int[] size = FfmpegSourceWorker.fitInside(grabber.getImageWidth(),
                    grabber.getImageHeight(), new int[]{896, 512});
            grabber.setImageWidth(size[0]);
            grabber.setImageHeight(size[1]);

            // Unprocessed frames are reported as video without a usable picture.
            Frame skipped = grabber.grabFrame(false, true, false, false, false);
            assertNotNull(skipped);
            assertEquals(Frame.Type.VIDEO, skipped.type);
            assertEquals(0, skipped.imageWidth);

            Frame frame = grabber.grabFrame(false, true, true, false, false);
            assertNotNull(frame);
            SharedVideoFrame screenFrame = FfmpegSourceWorker.toScreenFrame(frame);
            assertNotNull(screenFrame);
            // 1280x720 fitted inside 896x512 keeps 16:9.
            assertEquals(896, screenFrame.image().getWidth());
            assertEquals(504, screenFrame.image().getHeight());
            int pixel = screenFrame.opaquePixels()[504 / 2 * 896 + 448];
            assertEquals(0xFF, pixel >>> 24, "decoded pixels must be opaque");
            assertTrue(((pixel >> 16) & 0xFF) > 150 && (pixel & 0xFF) < 90,
                    "expected red, got " + Integer.toHexString(pixel));
            assertEquals(pixel & 0xFFFFFF,
                    screenFrame.image().getRGB(448, 504 / 2) & 0xFFFFFF);
        }
    }

    @Test
    void copiesFittedFramesWithBlackBars() {
        int[] source = {1, 2, 3, 4, 5, 6};
        int[] target = new int[3 * 4];
        assertTrue(ManagedScreen.copyLetterboxed(source, 3, 2, target, 3, 4));
        assertArrayEquals(new int[]{
                0xFF000000, 0xFF000000, 0xFF000000,
                1, 2, 3,
                4, 5, 6,
                0xFF000000, 0xFF000000, 0xFF000000}, target);

        int[] wide = new int[4 * 2];
        assertTrue(ManagedScreen.copyLetterboxed(new int[]{1, 2, 3, 4}, 2, 2, wide, 4, 2));
        assertArrayEquals(new int[]{0xFF000000, 1, 2, 0xFF000000,
                0xFF000000, 3, 4, 0xFF000000}, wide);

        // Smaller frames need real scaling.
        assertFalse(ManagedScreen.copyLetterboxed(source, 3, 2, new int[16], 4, 4));
    }
}
