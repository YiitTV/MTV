package com.yiittv.mtv;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber;
import org.bytedeco.javacv.Java2DFrameConverter;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.Buffer;
import java.nio.ShortBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

final class FfmpegSourceWorker implements SourceWorker {

    private final MtvPlugin plugin;
    private final SharedMediaSource sharedSource;
    private final ScreenSource source;
    private final int reconnectDelaySeconds;
    private final int reconnectMaxDelaySeconds;
    private final long ioTimeoutSeconds;
    private final long stopTimeoutSeconds;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean terminated = new AtomicBoolean(true);
    private final AtomicReference<String> state = new AtomicReference<>("stopped");
    private final AtomicReference<String> lastError = new AtomicReference<>("none");
    private final AtomicInteger sourceWidth = new AtomicInteger();
    private final AtomicInteger sourceHeight = new AtomicInteger();
    private final AtomicLong reconnects = new AtomicLong();
    private final AtomicLong lastFrameAt = new AtomicLong();
    private short[] audioScratch = new short[0];

    private ExecutorService executor;

    FfmpegSourceWorker(MtvPlugin plugin, SharedMediaSource sharedSource,
                       ScreenSource source, int reconnectDelaySeconds,
                       int reconnectMaxDelaySeconds, long ioTimeoutSeconds,
                       long stopTimeoutSeconds) {
        this.plugin = plugin;
        this.sharedSource = sharedSource;
        this.source = source;
        this.reconnectDelaySeconds = Math.max(1, reconnectDelaySeconds);
        this.reconnectMaxDelaySeconds =
                Math.max(this.reconnectDelaySeconds, reconnectMaxDelaySeconds);
        this.ioTimeoutSeconds = Math.max(1, ioTimeoutSeconds);
        this.stopTimeoutSeconds = Math.max(1, stopTimeoutSeconds);
    }

    @Override
    public boolean start() {
        if (!running.compareAndSet(false, true)) {
            return false;
        }
        terminated.set(false);
        executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable,
                    "MTV-" + source.type().id() + "-"
                            + Integer.toUnsignedString(source.key().hashCode(), 36));
            thread.setDaemon(true);
            return thread;
        });
        executor.submit(this::runLoop);
        return true;
    }

    @Override
    public boolean stop() {
        requestStop();
        ExecutorService currentExecutor = executor;
        if (currentExecutor == null) {
            state.set("stopped");
            terminated.set(true);
            return true;
        }

        boolean stopped = false;
        try {
            stopped = currentExecutor.awaitTermination(
                    stopTimeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        if (!stopped) {
            plugin.getLogger().severe(plugin.messages().plain("logs.decoder-stop-failed"));
            state.set("stopping");
            return false;
        }
        executor = null;
        state.set("stopped");
        return true;
    }

    @Override
    public void requestStop() {
        running.set(false);
        if (!terminated.get()) {
            state.set("stopping");
        }
        ExecutorService currentExecutor = executor;
        if (currentExecutor != null) {
            currentExecutor.shutdownNow();
        }
    }

    @Override
    public String state() {
        return state.get();
    }

    @Override
    public String lastError() {
        return lastError.get();
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public boolean isTerminated() {
        return terminated.get();
    }

    @Override
    public int sourceWidth() {
        return sourceWidth.get();
    }

    @Override
    public int sourceHeight() {
        return sourceHeight.get();
    }

    @Override
    public long reconnects() {
        return reconnects.get();
    }

    @Override
    public long lastFrameAgeMillis() {
        long timestamp = lastFrameAt.get();
        return timestamp == 0 ? -1 : Math.max(0, System.currentTimeMillis() - timestamp);
    }

    private void runLoop() {
        try {
            int retryDelay = reconnectDelaySeconds;
            boolean showConnecting = true;
            while (running.get()) {
                if (!sharedSource.shouldDecode()) {
                    state.set("paused (no viewers)");
                    lastError.set("none");
                    retryDelay = reconnectDelaySeconds;
                    showConnecting = true;
                    if (!sleepMillis(500)) {
                        break;
                    }
                    continue;
                }

                try {
                    if (showConnecting) {
                        sharedSource.showFrame("screen.connecting");
                    }
                    ReceiveResult result = receive();
                    retryDelay = reconnectDelaySeconds;
                    if (result == ReceiveResult.LOOP || result == ReceiveResult.RECONFIGURE) {
                        showConnecting = false;
                        continue;
                    }
                    if (result == ReceiveResult.PAUSED) {
                        showConnecting = true;
                        continue;
                    }
                } catch (Exception exception) {
                    sharedSource.resetAudio();
                    if (running.get()) {
                        showConnecting = true;
                        state.set("waiting for source");
                        lastError.set(compactError(exception));
                        sharedSource.showFrame("screen.offline");
                    }
                }

                if (running.get()) {
                    if (!sleepMillis(TimeUnit.SECONDS.toMillis(retryDelay))) {
                        break;
                    }
                    retryDelay = nextRetryDelay(retryDelay, reconnectMaxDelaySeconds);
                }
            }
        } finally {
            sharedSource.resetAudio();
            running.set(false);
            terminated.set(true);
            state.set("stopped");
        }
    }

    private ReceiveResult receive() throws Exception {
        try {
            return receiveFrames();
        } finally {
            sharedSource.resetAudio();
        }
    }

    private ReceiveResult receiveFrames() throws Exception {
        state.set("connecting");
        reconnects.incrementAndGet();
        String input = source.type() == SourceType.LIVE
                ? resolveLive()
                : plugin.resolveSourceInput(source);
        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(input);
             Java2DFrameConverter converter = new Java2DFrameConverter()) {
            boolean audio = sharedSource.wantsAudio();
            configure(grabber, audio, plugin.audio().sampleRate());
            grabber.start();

            if (grabber.getImageWidth() <= 0 || grabber.getImageHeight() <= 0) {
                throw new IllegalStateException("video dimensions are missing");
            }
            sourceWidth.set(grabber.getImageWidth());
            sourceHeight.set(grabber.getImageHeight());
            state.set("live");
            lastError.set("none");
            plugin.getLogger().info(plugin.messages().plain(
                    "logs.source-connected",
                    "type", source.type().id(),
                    "value", source.displayValue(),
                    "width", grabber.getImageWidth(),
                    "height", grabber.getImageHeight()));

            long nextPublish = System.nanoTime();
            long playbackStarted = 0;
            long firstTimestamp = -1;
            boolean loopSeekPending = false;
            long nextAudioCheck = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);

            while (running.get()) {
                if (!sharedSource.shouldDecode()) {
                    state.set("paused (no viewers)");
                    lastError.set("none");
                    return ReceiveResult.PAUSED;
                }
                if (System.nanoTime() >= nextAudioCheck) {
                    nextAudioCheck = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
                    if (sharedSource.wantsAudio() != audio) {
                        // Audio was switched on or off for a screen: reopen with the new setup.
                        return ReceiveResult.RECONFIGURE;
                    }
                }

                Frame frame = audio ? grabber.grab() : grabber.grabImage();
                if (frame == null) {
                    if (source.type().loopsAtEnd() && !loopSeekPending) {
                        grabber.setTimestamp(0);
                        firstTimestamp = -1;
                        playbackStarted = 0;
                        loopSeekPending = true;
                        continue;
                    }
                    if (source.type().loopsAtEnd()) {
                        return ReceiveResult.LOOP;
                    }
                    throw new IllegalStateException("source ended");
                }
                boolean hasAudio = audio && frame.samples != null && frame.samples.length > 0;
                boolean hasImage = frame.image != null
                        && frame.imageWidth > 0 && frame.imageHeight > 0;
                if (!hasAudio && !hasImage) {
                    continue;
                }
                loopSeekPending = false;

                // Files and HLS live streams are paced to real time; HLS delivers the first
                // segments in a burst that would otherwise play (and buffer audio) too fast.
                if ((source.type().loopsAtEnd() || source.type() == SourceType.LIVE)
                        && frame.timestamp >= 0) {
                    if (firstTimestamp < 0) {
                        firstTimestamp = frame.timestamp;
                        playbackStarted = System.nanoTime();
                    }
                    long target = playbackStarted
                            + Math.max(0, frame.timestamp - firstTimestamp) * 1_000L;
                    if (frame.timestamp < firstTimestamp
                            || target - System.nanoTime() > TimeUnit.SECONDS.toNanos(2)) {
                        // Timestamp discontinuity (stream ad break, segment reset): re-anchor
                        // instead of freezing the picture while waiting for the new timeline.
                        firstTimestamp = frame.timestamp;
                        playbackStarted = System.nanoTime();
                        target = playbackStarted;
                    }
                    if (!sleepUntil(target)) {
                        return ReceiveResult.STOPPED;
                    }
                }

                if (hasAudio) {
                    publishAudio(frame.samples[0]);
                    if (!hasImage) {
                        continue;
                    }
                }

                lastFrameAt.set(System.currentTimeMillis());
                long now = System.nanoTime();
                long interval = (long) (TimeUnit.SECONDS.toNanos(1)
                        / Math.max(0.1, Math.min(20, sharedSource.targetFps())));
                if (now < nextPublish) {
                    continue;
                }
                nextPublish = now + interval;
                BufferedImage image = converter.convert(frame);
                if (image != null) {
                    sharedSource.publish(copyFrame(image));
                }
            }
        }
        return ReceiveResult.STOPPED;
    }

    private String resolveLive() throws Exception {
        state.set("resolving live stream");
        String executable = plugin.getConfig().getString("youtube.yt-dlp-path", "yt-dlp");
        String format = plugin.getConfig().getString("live.format",
                LiveStreamResolver.DEFAULT_FORMAT);
        long timeout = Math.max(10, plugin.getConfig().getLong("live.resolve-timeout-seconds", 30));
        String address = LiveStreamResolver.resolve(
                executable == null || executable.isBlank() ? "yt-dlp" : executable.trim(),
                format == null || format.isBlank() ? LiveStreamResolver.DEFAULT_FORMAT : format.trim(),
                source.value(), timeout);
        state.set("connecting");
        return address;
    }

    private void publishAudio(Buffer samples) {
        if (!(samples instanceof ShortBuffer buffer)) {
            return;
        }
        ShortBuffer view = buffer.duplicate();
        int count = view.remaining();
        if (count < 1) {
            return;
        }
        if (audioScratch.length < count) {
            audioScratch = new short[count];
        }
        view.get(audioScratch, 0, count);
        sharedSource.publishAudio(audioScratch, count);
    }

    private void configure(FFmpegFrameGrabber grabber, boolean audio, int sampleRate) {
        if (source.type() == SourceType.RTMP) {
            grabber.setFormat("flv");
        }
        if (audio) {
            // Plasmo Voice expects 16-bit mono PCM at its configured sample rate.
            grabber.setSampleMode(FrameGrabber.SampleMode.SHORT);
            grabber.setAudioChannels(1);
            grabber.setSampleRate(sampleRate);
        }
        if (source.usesRemoteLocation()) {
            grabber.setOption("analyzeduration", "10000000");
            grabber.setOption("probesize", "10000000");
            grabber.setOption("rw_timeout", Long.toString(
                    TimeUnit.SECONDS.toMicros(ioTimeoutSeconds)));
        }
        if (source.type() == SourceType.LIVE) {
            grabber.setOption("reconnect", "1");
            grabber.setOption("reconnect_streamed", "1");
            grabber.setOption("reconnect_delay_max", "5");
        }
    }

    private static int nextRetryDelay(int current, int maximum) {
        return current >= maximum / 2 ? maximum : Math.min(maximum, current * 2);
    }

    private boolean sleepUntil(long targetNanos) {
        while (running.get()) {
            long remaining = targetNanos - System.nanoTime();
            if (remaining <= 0) {
                return true;
            }
            if (!sleepMillis(Math.min(
                    TimeUnit.NANOSECONDS.toMillis(remaining) + 1, 100))) {
                return false;
            }
        }
        return false;
    }

    private static BufferedImage copyFrame(BufferedImage source) {
        BufferedImage output = new BufferedImage(
                source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = output.createGraphics();
        try {
            graphics.drawImage(source, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        return output;
    }

    private static String compactError(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        if (message.contains("Could not open input")) {
            return "source offline";
        }
        message = StreamUrlSanitizer.mask(message);
        return message.length() > 100 ? message.substring(0, 100) : message;
    }

    private boolean sleepMillis(long millis) {
        try {
            TimeUnit.MILLISECONDS.sleep(Math.max(1, millis));
            return true;
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private enum ReceiveResult {
        PAUSED,
        LOOP,
        RECONFIGURE,
        STOPPED
    }
}
