package com.yiittv.mtv;

import org.bytedeco.javacv.FFmpegFrameGrabber;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Reads and caches the length of local video and GIF files for {@code duration: auto}. */
final class MediaDurationProbe {

    private final MtvPlugin plugin;
    private final Map<String, Entry> cache = new ConcurrentHashMap<>();

    MediaDurationProbe(MtvPlugin plugin) {
        this.plugin = plugin;
    }

    /** Length in milliseconds, or -1 when it cannot be read. */
    long millis(ScreenSource source) {
        if (source == null || source.usesRemoteLocation()
                || (source.type() != SourceType.VIDEO && source.type() != SourceType.GIF)) {
            return -1;
        }
        Path path;
        long modified;
        long size;
        try {
            path = Path.of(plugin.resolveSourceInput(source));
            modified = Files.getLastModifiedTime(path).toMillis();
            size = Files.size(path);
        } catch (IOException | RuntimeException exception) {
            return -1;
        }
        String key = path.toString();
        Entry cached = cache.get(key);
        if (cached != null && cached.modified() == modified && cached.size() == size) {
            return cached.millis();
        }
        long length = probe(path);
        cache.put(key, new Entry(modified, size, length));
        return length;
    }

    private static long probe(Path path) {
        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(path.toFile())) {
            grabber.start();
            long micros = grabber.getLengthInTime();
            return micros > 0 ? Math.max(1_000, micros / 1_000) : -1;
        } catch (Exception exception) {
            return -1;
        }
    }

    private record Entry(long modified, long size, long millis) {
    }
}
