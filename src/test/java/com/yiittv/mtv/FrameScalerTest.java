package com.yiittv.mtv;

import org.bytedeco.javacv.FFmpegFrameFilter;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class FrameScalerTest {

    @Test
    void scalesDecodedVideoNativelyToFitTheScreen(@TempDir Path directory) throws Exception {
        Path video = directory.resolve("clip.mp4");
        try (FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(video.toFile(), 1280, 720);
             Java2DFrameConverter converter = new Java2DFrameConverter()) {
            recorder.setFormat("mp4");
            recorder.setFrameRate(10);
            recorder.start();
            for (int index = 0; index < 5; index++) {
                BufferedImage image = new BufferedImage(1280, 720, BufferedImage.TYPE_3BYTE_BGR);
                Graphics2D graphics = image.createGraphics();
                graphics.setColor(new Color(40 * index, 120, 200));
                graphics.fillRect(0, 0, 1280, 720);
                graphics.dispose();
                recorder.record(converter.convert(image));
            }
            recorder.stop();
        }

        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(video.toFile());
             Java2DFrameConverter converter = new Java2DFrameConverter()) {
            grabber.start();
            try (FFmpegFrameFilter filter = FfmpegSourceWorker.scaler(grabber.getImageWidth(),
                    grabber.getImageHeight(), grabber.getPixelFormat(), grabber.getFrameRate(),
                    896, 512)) {
                Frame frame = grabber.grabImage();
                assertNotNull(frame);
                filter.push(frame);
                Frame scaled = filter.pull();
                assertNotNull(scaled);
                BufferedImage image = converter.convert(scaled);
                // 1280x720 fitted inside 896x512 keeps 16:9.
                assertEquals(896, image.getWidth());
                assertEquals(504, image.getHeight());
            }
        }
    }
}
