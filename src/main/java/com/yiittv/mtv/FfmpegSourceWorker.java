package com.yiittv.mtv;

import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.ffmpeg.global.swscale;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
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
    private final int decoderThreads;
    private final boolean skipNonReferenceFrames;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean terminated = new AtomicBoolean(true);
    private final AtomicReference<String> state = new AtomicReference<>("stopped");
    private final AtomicReference<String> lastError = new AtomicReference<>("none");
    private final AtomicInteger sourceWidth = new AtomicInteger();
    private final AtomicInteger sourceHeight = new AtomicInteger();
    private final AtomicLong reconnects = new AtomicLong();
    private final AtomicLong lastFrameAt = new AtomicLong();
    private short[] audioScratch = new short[0];
    private volatile Thread sideAudioThread;

    private ExecutorService executor;

    FfmpegSourceWorker(MtvPlugin plugin, SharedMediaSource sharedSource,
                       ScreenSource source, int reconnectDelaySeconds,
                       int reconnectMaxDelaySeconds, long ioTimeoutSeconds,
                       long stopTimeoutSeconds, int decoderThreads,
                       boolean skipNonReferenceFrames) {
        this.plugin = plugin;
        this.sharedSource = sharedSource;
        this.source = source;
        this.reconnectDelaySeconds = Math.max(1, reconnectDelaySeconds);
        this.reconnectMaxDelaySeconds =
                Math.max(this.reconnectDelaySeconds, reconnectMaxDelaySeconds);
        this.ioTimeoutSeconds = Math.max(1, ioTimeoutSeconds);
        this.stopTimeoutSeconds = Math.max(1, stopTimeoutSeconds);
        this.decoderThreads = Math.max(0, Math.min(16, decoderThreads));
        this.skipNonReferenceFrames = skipNonReferenceFrames;
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
            stopSideAudio();
            sharedSource.resetAudio();
        }
    }

    private ReceiveResult receiveFrames() throws Exception {
        state.set("connecting");
        reconnects.incrementAndGet();
        LiveStreamResolver.LiveStream live = source.type() == SourceType.LIVE
                ? resolveLive() : null;
        String input = live != null ? live.video() : plugin.resolveSourceInput(source);
        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(input)) {
            boolean audio = sharedSource.wantsAudio();
            // YouTube lives deliver audio as its own playlist: decode it on a second grabber.
            boolean separateAudio = audio && live != null && live.separateAudio();
            boolean inlineAudio = audio && !separateAudio;
            configure(grabber, inlineAudio, plugin.audio().sampleRate());
            configureVideo(grabber);
            grabber.start();
            if (separateAudio) {
                sideAudioThread = startSideAudio(live.audio(), plugin.audio().sampleRate());
            }

            // Read the real stream size before setImageWidth/Height change what the grabber reports.
            int nativeWidth = grabber.getImageWidth();
            int nativeHeight = grabber.getImageHeight();
            if (nativeWidth <= 0 || nativeHeight <= 0) {
                throw new IllegalStateException("video dimensions are missing");
            }
            sourceWidth.set(nativeWidth);
            sourceHeight.set(nativeHeight);
            applyOutputSize(grabber, nativeWidth, nativeHeight);
            state.set("live");
            lastError.set("none");
            plugin.getLogger().info(plugin.messages().plain(
                    "logs.source-connected",
                    "type", source.type().id(),
                    "value", source.displayValue(),
                    "width", nativeWidth,
                    "height", nativeHeight));

            double frameRate = grabber.getFrameRate();
            long frameNanos = (long) (TimeUnit.SECONDS.toNanos(1)
                    / (frameRate > 1 && Double.isFinite(frameRate) ? Math.min(frameRate, 120) : 30));
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
                    // Screens may have been resized, enabled or disabled.
                    applyOutputSize(grabber, nativeWidth, nativeHeight);
                }

                // Every frame has to be decoded, but only the ones that will be shown need the
                // colour conversion. With inline audio the grabber processes both together.
                boolean wantPicture = inlineAudio
                        || System.nanoTime() + frameNanos >= nextPublish;
                Frame frame = inlineAudio
                        ? grabber.grab()
                        : grabber.grabFrame(false, true, wantPicture, false, false);
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
                boolean hasAudio = inlineAudio && frame.samples != null && frame.samples.length > 0;
                boolean isVideo = frame.type == Frame.Type.VIDEO;
                // Unprocessed video frames keep imageWidth 0 and a stale buffer: never show them.
                boolean hasImage = isVideo && frame.image != null
                        && frame.imageWidth > 0 && frame.imageHeight > 0;
                if (!hasAudio && !isVideo) {
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
                    if (!isVideo) {
                        continue;
                    }
                }

                lastFrameAt.set(System.currentTimeMillis());
                if (!hasImage) {
                    continue;
                }
                long now = System.nanoTime();
                long interval = (long) (TimeUnit.SECONDS.toNanos(1)
                        / Math.max(0.1, Math.min(20, sharedSource.targetFps())));
                // Half a source frame of tolerance keeps the requested rate instead of
                // rounding down to every other frame.
                if (now + frameNanos / 2 < nextPublish) {
                    continue;
                }
                nextPublish = Math.max(nextPublish + interval, now + interval / 2);
                SharedVideoFrame picture = toScreenFrame(frame);
                if (picture != null) {
                    sharedSource.publish(picture);
                }
            }
        }
        return ReceiveResult.STOPPED;
    }

    /**
     * Decodes a separate live audio playlist (YouTube) next to the video grabber. Paced to
     * real time like the video, so both stay roughly aligned.
     */
    private Thread startSideAudio(String url, int sampleRate) {
        return Thread.ofPlatform().daemon().name("MTV-live-audio").start(() -> {
            try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(url)) {
                configure(grabber, true, sampleRate);
                grabber.start();
                long firstTimestamp = -1;
                long started = 0;
                short[] scratch = new short[0];
                while (running.get() && !Thread.currentThread().isInterrupted()) {
                    Frame frame = grabber.grabSamples();
                    if (frame == null) {
                        sharedSource.reportAudioError("live audio playlist ended");
                        return;
                    }
                    if (frame.samples == null || frame.samples.length == 0
                            || !(frame.samples[0] instanceof ShortBuffer buffer)) {
                        continue;
                    }
                    if (frame.timestamp >= 0) {
                        if (firstTimestamp < 0) {
                            firstTimestamp = frame.timestamp;
                            started = System.nanoTime();
                        }
                        long target = started + Math.max(0, frame.timestamp - firstTimestamp) * 1_000L;
                        if (frame.timestamp < firstTimestamp
                                || target - System.nanoTime() > TimeUnit.SECONDS.toNanos(2)) {
                            firstTimestamp = frame.timestamp;
                            started = System.nanoTime();
                            target = started;
                        }
                        if (!sleepUntil(target)) {
                            return;
                        }
                    }
                    ShortBuffer view = buffer.duplicate();
                    int count = view.remaining();
                    if (count < 1) {
                        continue;
                    }
                    if (scratch.length < count) {
                        scratch = new short[count];
                    }
                    view.get(scratch, 0, count);
                    sharedSource.publishAudio(scratch, count);
                }
            } catch (Exception exception) {
                // The video grabber owns reconnects; audio simply stops until the next one.
                if (running.get() && !Thread.currentThread().isInterrupted()) {
                    sharedSource.reportAudioError("live audio: " + compactError(exception));
                }
            }
        });
    }

    private void stopSideAudio() {
        Thread thread = sideAudioThread;
        sideAudioThread = null;
        if (thread == null) {
            return;
        }
        thread.interrupt();
        try {
            thread.join(TimeUnit.SECONDS.toMillis(Math.max(1, ioTimeoutSeconds + 1)));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private LiveStreamResolver.LiveStream resolveLive() throws Exception {
        state.set("resolving live stream");
        String executable = plugin.getConfig().getString("youtube.yt-dlp-path", "yt-dlp");
        String format = plugin.getConfig().getString("live.format",
                LiveStreamResolver.DEFAULT_FORMAT);
        long timeout = Math.max(10, plugin.getConfig().getLong("live.resolve-timeout-seconds", 30));
        LiveStreamResolver.LiveStream address = LiveStreamResolver.resolve(
                executable == null || executable.isBlank() ? "yt-dlp" : executable.trim(),
                LiveStreamResolver.format(format),
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
            // YouTube HLS segment URLs carry no file extension; FFmpeg 7's HLS demuxer rejects
            // them with "Invalid data" unless the extension check is relaxed.
            grabber.setOption("extension_picky", "0");
            grabber.setOption("allowed_extensions", "ALL");
            grabber.setOption("allowed_segment_extensions", "ALL");
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

    /**
     * Video options: decoder threads and skipping of non-reference frames. Non-reference
     * frames are never used to predict other frames, so dropping them causes no artefacts;
     * map screens show at most 20 of the source's 25-60 frames per second anyway.
     */
    private void configureVideo(FFmpegFrameGrabber grabber) {
        if (decoderThreads > 0) {
            grabber.setVideoOption("threads", Integer.toString(decoderThreads));
        }
        if (skipNonReferenceFrames) {
            grabber.setVideoOption("skip_frame", "noref");
        }
        // BGRA bytes are little-endian ARGB ints: frames go straight into MapEngine buffers.
        grabber.setPixelFormat(avutil.AV_PIX_FMT_BGRA);
        grabber.setImageScalingFlags(swscale.SWS_BILINEAR);
    }

    /**
     * Makes the grabber's single colour-conversion pass also downscale to the largest
     * screen size, so Java never touches full-resolution frames. Never upscales.
     */
    private void applyOutputSize(FFmpegFrameGrabber grabber, int nativeWidth, int nativeHeight) {
        int[] size = fitInside(nativeWidth, nativeHeight, sharedSource.targetPixelSize());
        if (grabber.getImageWidth() != size[0] || grabber.getImageHeight() != size[1]) {
            grabber.setImageWidth(size[0]);
            grabber.setImageHeight(size[1]);
        }
    }

    /** Largest even size that keeps the aspect ratio and fits inside target; package-private for tests. */
    static int[] fitInside(int sourceWidth, int sourceHeight, int[] target) {
        if (target == null || target[0] < 1 || target[1] < 1
                || (sourceWidth <= target[0] && sourceHeight <= target[1])) {
            return new int[]{sourceWidth, sourceHeight};
        }
        double scale = Math.min(target[0] / (double) sourceWidth,
                target[1] / (double) sourceHeight);
        int width = Math.max(2, (int) Math.round(sourceWidth * scale) & ~1);
        int height = Math.max(2, (int) Math.round(sourceHeight * scale) & ~1);
        return new int[]{Math.min(width, target[0]), Math.min(height, target[1])};
    }

    /** Copies a processed BGRA frame into a fresh opaque ARGB frame. */
    static SharedVideoFrame toScreenFrame(Frame frame) {
        if (frame.image == null || !(frame.image[0] instanceof ByteBuffer bytes)) {
            return null;
        }
        int width = frame.imageWidth;
        int height = frame.imageHeight;
        int stride = frame.imageStride / 4;
        if (width < 1 || height < 1 || stride < width) {
            return null;
        }
        IntBuffer source = bytes.duplicate().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
        if (source.capacity() < (height - 1) * stride + width) {
            return null;
        }
        int[] pixels = new int[width * height];
        if (stride == width) {
            source.position(0);
            source.get(pixels, 0, pixels.length);
        } else {
            for (int y = 0; y < height; y++) {
                source.position(y * stride);
                source.get(pixels, y * width, width);
            }
        }
        return SharedVideoFrame.opaque(pixels, width, height);
    }

    private static String compactError(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        if (message.contains("Could not open input")) {
            // Keep FFmpeg's error code (e.g. 403, invalid data) but never the stream URL.
            java.util.regex.Matcher code = java.util.regex.Pattern
                    .compile("error (-?\\d+)").matcher(message);
            return code.find() ? "source offline (ffmpeg " + code.group(1) + ")"
                    : "source offline";
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
