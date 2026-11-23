package com.yiittv.mtv;

import de.pianoman911.mapengine.api.MapEngineApi;
import de.pianoman911.mapengine.api.clientside.IMapDisplay;
import de.pianoman911.mapengine.api.drawing.IDrawingSpace;
import de.pianoman911.mapengine.api.util.Converter;
import de.pianoman911.mapengine.api.util.FullSpacedColorBuffer;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

final class ManagedScreen {

    private final MtvPlugin plugin;
    private final Object renderLock = new Object();
    private final Set<UUID> spawnedViewers = new HashSet<>();
    private final AtomicReference<SharedVideoFrame> pendingFrame = new AtomicReference<>();
    private final AtomicBoolean forceFullFrame = new AtomicBoolean();
    private final AtomicBoolean destroyed = new AtomicBoolean();
    private final AtomicLong receivedFrames = new AtomicLong();
    private final AtomicLong renderedFrames = new AtomicLong();
    private final AtomicLong replacedFrames = new AtomicLong();
    private final AtomicLong totalRenderNanos = new AtomicLong();
    private final AtomicLong lastRenderNanos = new AtomicLong();

    private volatile ScreenDefinition definition;
    private volatile List<Player> receiverSnapshot = List.of();
    /** Nearby viewers that can see the picture right now; only they get map packets. */
    private volatile List<Player> packetReceivers = List.of();
    /** Receivers whose client already shows the last flushed buffer. Render thread only. */
    private final Set<UUID> syncedReceivers = ConcurrentHashMap.newKeySet();
    private volatile boolean cullLookingAway;
    private volatile double lookAwayAngle = 105;
    private volatile String lastRenderError = "none";
    private volatile long nextRenderNanos;
    private volatile double effectiveFps;
    private volatile int maxDeltaMaps;
    private volatile IMapDisplay display;
    private volatile IDrawingSpace drawing;
    private volatile boolean audioEnabled;
    private volatile int audioDistance = 32;
    private volatile boolean audioListeners;
    private boolean glowing;
    private boolean dithering;
    private FullSpacedColorBuffer previousFrame;
    private RenderSurface renderSurface;
    private World displayWorld;

    ManagedScreen(MtvPlugin plugin, MapEngineApi mapEngine,
                  ScreenDefinition definition, World world) {
        this.plugin = plugin;
        this.definition = definition;
        this.displayWorld = world;
        this.display = mapEngine.displayProvider().createBasic(
                definition.location(), definition.secondCorner(), definition.facing());
        this.drawing = mapEngine.pipeline().createDrawingSpace(display);
        this.drawing.ctx().buffering(false);
        this.drawing.ctx().bundling(false);
        this.glowing = plugin.getConfig().getBoolean("screen.glowing-frames", false);
        this.dithering = ditheringSetting();
        this.display.glowing(glowing);
        this.drawing.ctx().converter(
                dithering ? Converter.FLOYD_STEINBERG : Converter.DIRECT);
        reloadPerformanceSettings();
    }

    String id() {
        return definition.id();
    }

    ScreenDefinition definition() {
        return definition;
    }

    void updateDefinition(ScreenDefinition value) {
        definition = value;
        nextRenderNanos = 0;
        reloadPerformanceSettings();
        // The source type decides whether dithering applies (see ditheringSetting).
        applyDithering();
    }

    private void applyDithering() {
        IDrawingSpace currentDrawing = drawing;
        boolean updated = ditheringSetting();
        if (currentDrawing != null && updated != dithering) {
            currentDrawing.ctx().converter(updated ? Converter.FLOYD_STEINBERG : Converter.DIRECT);
            dithering = updated;
            forceFullFrame.set(true);
        }
    }

    void reloadRenderingSettings() {
        IMapDisplay currentDisplay = display;
        IDrawingSpace currentDrawing = drawing;
        boolean updatedGlowing =
                plugin.getConfig().getBoolean("screen.glowing-frames", false);
        boolean updatedDithering = ditheringSetting();
        if (currentDisplay != null && updatedGlowing != glowing) {
            despawnTrackedViewers(currentDisplay);
            currentDisplay.glowing(updatedGlowing);
            glowing = updatedGlowing;
        }
        if (currentDrawing != null && updatedDithering != dithering) {
            currentDrawing.ctx().converter(
                    updatedDithering ? Converter.FLOYD_STEINBERG : Converter.DIRECT);
            dithering = updatedDithering;
        }
        reloadPerformanceSettings();
        forceFullFrame.set(true);
    }

    /**
     * Per-screen dithering override, falling back to the global screen.dithering. Moving
     * pictures (video, streams, GIFs) are never dithered: Floyd-Steinberg costs a lot of CPU
     * on every frame, and its noise changes every frame, so nearly every pixel is resent.
     * Still images keep it, because they are converted only once.
     */
    private boolean ditheringSetting() {
        if (!ditheringAllowed(definition.source().type())) {
            return false;
        }
        boolean global = plugin.getConfig().getBoolean("screen.dithering", false);
        return plugin.getConfig().getBoolean(
                "screens." + definition.id() + ".dithering", global);
    }

    static boolean ditheringAllowed(SourceType type) {
        return type == SourceType.IMAGE || type == SourceType.URL_IMAGE;
    }

    void prepareViewerResync() {
        IMapDisplay currentDisplay = display;
        if (currentDisplay != null) {
            despawnTrackedViewers(currentDisplay);
        }
        forceFullFrame.set(true);
    }

    private void despawnTrackedViewers(IMapDisplay currentDisplay) {
        for (UUID uuid : new HashSet<>(spawnedViewers)) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                currentDisplay.despawn(player);
            }
        }
        spawnedViewers.clear();
        receiverSnapshot = List.of();
        packetReceivers = List.of();
    }

    int width() {
        IMapDisplay current = display;
        return current == null ? 0 : current.width();
    }

    int height() {
        IMapDisplay current = display;
        return current == null ? 0 : current.height();
    }

    int pixelWidth() {
        IMapDisplay current = display;
        return current == null ? 0 : current.pixelWidth();
    }

    int pixelHeight() {
        IMapDisplay current = display;
        return current == null ? 0 : current.pixelHeight();
    }

    int viewers() {
        return receiverSnapshot.size();
    }

    boolean hasViewerWithPermission(String permission) {
        if (permission == null || permission.isBlank()) {
            return true;
        }
        for (Player player : receiverSnapshot) {
            if (player.hasPermission(permission)) {
                return true;
            }
        }
        return false;
    }

    boolean allViewersHavePermission(String permission) {
        if (permission == null || permission.isBlank()) {
            return true;
        }
        return !receiverSnapshot.isEmpty()
                && receiverSnapshot.stream().allMatch(player -> player.hasPermission(permission));
    }

    void updateAudio(boolean enabled, int distance) {
        audioEnabled = enabled;
        audioDistance = distance;
    }

    boolean audioEnabled() {
        return audioEnabled;
    }

    int audioDistance() {
        return audioDistance;
    }

    /** True when a player is within the audio distance, even if outside view distance. */
    boolean hasAudioListeners() {
        return audioListeners;
    }

    World world() {
        return displayWorld;
    }

    /** Centre of the map wall, used as the audio emitter position. */
    double[] center() {
        IMapDisplay current = display;
        if (current == null) {
            return null;
        }
        return new double[]{
                (current.box().getMinX() + current.box().getMaxX()) / 2.0,
                (current.box().getMinY() + current.box().getMaxY()) / 2.0,
                (current.box().getMinZ() + current.box().getMaxZ()) / 2.0};
    }

    boolean hasViewers() {
        return !receiverSnapshot.isEmpty();
    }

    boolean enabled() {
        return definition.enabled();
    }

    double effectiveFps() {
        return effectiveFps;
    }

    void refreshViewers(List<ViewerPosition> onlinePlayers) {
        if (destroyed.get()) {
            return;
        }
        World currentWorld = displayWorld;
        IMapDisplay currentDisplay = display;
        if (currentWorld == null || currentDisplay == null) {
            return;
        }

        double maxDistance = definition.distance();
        double maxDistanceSquared = maxDistance * maxDistance;
        double centerX = (currentDisplay.box().getMinX() + currentDisplay.box().getMaxX()) / 2.0;
        double centerY = (currentDisplay.box().getMinY() + currentDisplay.box().getMaxY()) / 2.0;
        double centerZ = (currentDisplay.box().getMinZ() + currentDisplay.box().getMaxZ()) / 2.0;
        Set<UUID> shouldSee = new HashSet<>();
        List<Player> receivers = new ArrayList<>();

        ScreenDefinition currentDefinition = definition;
        double audioDistanceSquared = (double) audioDistance * audioDistance;
        boolean listeners = false;
        for (ViewerPosition viewer : onlinePlayers) {
            Player player = viewer.player();
            if (viewer.world() != currentWorld) {
                continue;
            }
            if (audioEnabled && !listeners) {
                double ax = viewer.x() - centerX;
                double ay = viewer.y() - centerY;
                double az = viewer.z() - centerZ;
                listeners = ax * ax + ay * ay + az * az <= audioDistanceSquared;
            }
            if (!ScreenPermissions.canView(player, currentDefinition)) {
                continue;
            }
            double dx = viewer.x() - centerX;
            double dy = viewer.y() - centerY;
            double dz = viewer.z() - centerZ;
            if (dx * dx + dy * dy + dz * dz > maxDistanceSquared) {
                continue;
            }
            shouldSee.add(player.getUniqueId());
            receivers.add(player);
            if (spawnedViewers.add(player.getUniqueId())) {
                // Freshly spawned frames are empty: make sure this player gets a full frame.
                syncedReceivers.remove(player.getUniqueId());
                currentDisplay.spawn(player);
            }
        }

        Set<UUID> remove = new HashSet<>(spawnedViewers);
        remove.removeAll(shouldSee);
        for (UUID uuid : remove) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                currentDisplay.despawn(player);
            }
            spawnedViewers.remove(uuid);
        }
        receiverSnapshot = List.copyOf(receivers);
        audioListeners = listeners;
        // New viewers are caught up with a full frame of their own in flushPendingFrame,
        // instead of resending every map to everybody else.
        refreshPacketReceivers(onlinePlayers);
    }

    /**
     * Recomputes which nearby viewers get pixel updates: players behind the wall and, when
     * enabled, players facing away keep their item frames but receive no map packets. Called
     * several times per second because it only does a little vector maths per viewer.
     */
    void refreshPacketReceivers(List<ViewerPosition> onlinePlayers) {
        List<Player> nearby = receiverSnapshot;
        IMapDisplay currentDisplay = display;
        if (destroyed.get() || currentDisplay == null || nearby.isEmpty()) {
            packetReceivers = List.of();
            return;
        }
        Set<UUID> nearbyIds = new HashSet<>();
        for (Player player : nearby) {
            nearbyIds.add(player.getUniqueId());
        }
        ScreenDefinition currentDefinition = definition;
        var box = currentDisplay.box();
        boolean cull = cullLookingAway;
        double angle = lookAwayAngle;
        List<Player> visible = new ArrayList<>(nearby.size());
        for (ViewerPosition viewer : onlinePlayers) {
            if (!nearbyIds.contains(viewer.player().getUniqueId())) {
                continue;
            }
            if (!ViewerCulling.inFront(currentDefinition.location(), currentDefinition.facing(),
                    viewer.x(), viewer.z())) {
                continue;
            }
            if (cull && !ViewerCulling.looksTowards(
                    viewer.x(), viewer.eyeY(), viewer.z(),
                    viewer.lookX(), viewer.lookY(), viewer.lookZ(),
                    box.getMinX(), box.getMinY(), box.getMinZ(),
                    box.getMaxX(), box.getMaxY(), box.getMaxZ(), angle)) {
                continue;
            }
            visible.add(viewer.player());
        }
        packetReceivers = List.copyOf(visible);
    }

    void removeViewer(UUID playerId) {
        spawnedViewers.remove(playerId);
        syncedReceivers.remove(playerId);
        List<Player> visible = packetReceivers;
        if (visible.stream().anyMatch(player -> player.getUniqueId().equals(playerId))) {
            packetReceivers = visible.stream()
                    .filter(player -> !player.getUniqueId().equals(playerId))
                    .toList();
        }
        List<Player> current = receiverSnapshot;
        if (current.stream().anyMatch(player -> player.getUniqueId().equals(playerId))) {
            receiverSnapshot = current.stream()
                    .filter(player -> !player.getUniqueId().equals(playerId))
                    .toList();
        }
    }

    void offerFrame(SharedVideoFrame frame) {
        if (destroyed.get()) {
            return;
        }
        receivedFrames.incrementAndGet();
        SharedVideoFrame old = pendingFrame.getAndSet(frame.retain());
        if (old != null) {
            replacedFrames.incrementAndGet();
            old.release();
        }
        if (destroyed.get()) {
            SharedVideoFrame queued = pendingFrame.getAndSet(null);
            if (queued != null) {
                queued.release();
            }
        }
    }

    void showOfflineFrame(String message) {
        if (destroyed.get()) {
            return;
        }
        int width = Math.min(pixelWidth(), 896);
        int height = Math.min(pixelHeight(), 512);
        if (width < 1 || height < 1) {
            return;
        }
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(new Color(10, 18, 14));
            graphics.fillRect(0, 0, width, height);
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            graphics.setColor(new Color(44, 212, 112));
            graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, Math.max(24, height / 10)));
            drawCentered(graphics, plugin.messages().plain("screen.offline-title"),
                    width, height / 2 - 20);
            graphics.setColor(Color.LIGHT_GRAY);
            graphics.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, Math.max(14, height / 20)));
            drawCentered(graphics, message, width, height / 2 + 35);
        } finally {
            graphics.dispose();
        }
        SharedVideoFrame frame = new SharedVideoFrame(image);
        offerFrame(frame);
        frame.release();
    }

    void flushPendingFrame(long now) {
        if (destroyed.get()) {
            SharedVideoFrame queued = pendingFrame.getAndSet(null);
            if (queued != null) {
                queued.release();
            }
            return;
        }
        List<Player> receivers = packetReceivers;
        if (receivers.isEmpty()) {
            // Nobody can see the picture: skip scaling, colour conversion and packets. The
            // newest frame stays queued and every later viewer starts with a full frame.
            syncedReceivers.clear();
            return;
        }
        List<Player> newcomers = new ArrayList<>();
        Set<UUID> receiverIds = new HashSet<>();
        for (Player player : receivers) {
            receiverIds.add(player.getUniqueId());
            if (!syncedReceivers.contains(player.getUniqueId())) {
                newcomers.add(player);
            }
        }
        syncedReceivers.retainAll(receiverIds);

        boolean fullFrame = forceFullFrame.get();
        if (!fullFrame && newcomers.isEmpty() && now < nextRenderNanos) {
            return;
        }

        SharedVideoFrame shared = pendingFrame.getAndSet(null);
        fullFrame = forceFullFrame.getAndSet(false);
        if (shared == null && !fullFrame && newcomers.isEmpty()) {
            return;
        }
        IMapDisplay currentDisplay = display;
        if (currentDisplay == null) {
            if (shared != null) {
                shared.release();
            }
            return;
        }
        if (shared != null || fullFrame) {
            long interval = Math.max(50_000_000L,
                    Math.round(1_000_000_000.0 / effectiveFps()));
            nextRenderNanos = now + interval;
        }

        long renderStarted = System.nanoTime();
        boolean renderCompleted = false;
        try {
            synchronized (renderLock) {
                IDrawingSpace currentDrawing = drawing;
                if (currentDrawing == null) {
                    return;
                }
                if (shared != null) {
                    drawFrame(shared, currentDrawing.buffer());
                }

                boolean useDelta = (long) currentDisplay.width()
                        * currentDisplay.height() <= maxDeltaMaps;
                if (fullFrame || previousFrame == null) {
                    flush(currentDrawing, receivers, null);
                } else {
                    if (!newcomers.isEmpty()) {
                        // Only the new viewers get every map; the others keep receiving deltas.
                        flush(currentDrawing, newcomers, null);
                    }
                    if (shared != null && newcomers.size() < receivers.size()) {
                        List<Player> steady = new ArrayList<>(receivers);
                        steady.removeAll(newcomers);
                        flush(currentDrawing, steady, useDelta ? previousFrame : null);
                    }
                }
                if (useDelta) {
                    capturePreviousFrame(currentDrawing.buffer());
                } else {
                    previousFrame = null;
                }
                syncedReceivers.addAll(receiverIds);
                renderedFrames.incrementAndGet();
                lastRenderError = "none";
                renderCompleted = true;
            }
        } catch (Throwable throwable) {
            lastRenderError = throwable.getClass().getSimpleName();
            forceFullFrame.set(true);
            plugin.getLogger().severe(plugin.messages().plain(
                    "logs.render-failed-screen",
                    "screen", id(),
                    "error", StreamUrlSanitizer.maskError(throwable)));
        } finally {
            if (renderCompleted) {
                long elapsed = System.nanoTime() - renderStarted;
                lastRenderNanos.set(elapsed);
                totalRenderNanos.addAndGet(elapsed);
            }
            if (shared != null) {
                shared.release();
            }
        }
    }

    private static void flush(IDrawingSpace drawing, List<Player> receivers,
                              FullSpacedColorBuffer previous) {
        drawing.ctx().receivers(receivers);
        drawing.ctx().previousBuffer(previous);
        drawing.flush();
    }

    /** Writes a frame into the MapEngine buffer, letterboxed and centred. */
    private void drawFrame(SharedVideoFrame frame, FullSpacedColorBuffer target) {
        BufferedImage image = frame.image();
        int[] pixels = frame.opaquePixels();
        if (pixels != null && copyLetterboxed(pixels, image.getWidth(), image.getHeight(),
                target.buffer(), target.width(), target.height())) {
            return;
        }
        RenderSurface surface = renderSurface;
        if (surface == null || !surface.wraps(target)) {
            surface = new RenderSurface(target);
            renderSurface = surface;
        }
        scaleIntoImage(image, surface.image());
    }

    /**
     * Fast path for decoded video that the decoder already fitted to this screen size: plain
     * row copies instead of a Java2D scale. Returns false when the frame needs real scaling.
     */
    static boolean copyLetterboxed(int[] source, int width, int height,
                                   int[] target, int targetWidth, int targetHeight) {
        boolean fits = (width == targetWidth && height <= targetHeight)
                || (height == targetHeight && width <= targetWidth);
        if (!fits || source.length < width * height || target.length < targetWidth * targetHeight) {
            return false;
        }
        int offsetX = (targetWidth - width) / 2;
        int offsetY = (targetHeight - height) / 2;
        if (width != targetWidth || height != targetHeight) {
            Arrays.fill(target, 0, targetWidth * targetHeight, 0xFF000000);
        }
        if (width == targetWidth) {
            System.arraycopy(source, 0, target, offsetY * targetWidth, width * height);
            return true;
        }
        for (int y = 0; y < height; y++) {
            System.arraycopy(source, y * width, target,
                    (y + offsetY) * targetWidth + offsetX, width);
        }
        return true;
    }

    void destroy() {
        if (!destroyed.compareAndSet(false, true)) {
            return;
        }
        IMapDisplay oldDisplay;
        synchronized (renderLock) {
            oldDisplay = display;
            display = null;
            drawing = null;
            previousFrame = null;
            renderSurface = null;
        }
        syncedReceivers.clear();
        packetReceivers = List.of();
        SharedVideoFrame queued = pendingFrame.getAndSet(null);
        if (queued != null) {
            queued.release();
        }
        receiverSnapshot = List.of();
        forceFullFrame.set(false);
        if (oldDisplay != null) {
            despawnTrackedViewers(oldDisplay);
            oldDisplay.destroy();
        }
        spawnedViewers.clear();
        displayWorld = null;
    }

    long receivedFrames() {
        return receivedFrames.get();
    }

    long renderedFrames() {
        return renderedFrames.get();
    }

    long replacedFrames() {
        return replacedFrames.get();
    }

    boolean frameQueued() {
        return pendingFrame.get() != null;
    }

    long lastRenderNanos() {
        return lastRenderNanos.get();
    }

    long averageRenderNanos() {
        long rendered = renderedFrames.get();
        return rendered == 0 ? 0 : totalRenderNanos.get() / rendered;
    }

    String lastRenderError() {
        return lastRenderError;
    }

    long estimatedImageBufferBytes() {
        long displayBytes = (long) pixelWidth() * pixelHeight() * Integer.BYTES;
        long bytes = displayBytes;
        synchronized (renderLock) {
            if (previousFrame != null) {
                bytes += displayBytes;
            }
        }
        SharedVideoFrame queued = pendingFrame.get();
        if (queued != null) {
            bytes += (long) queued.image().getWidth() * queued.image().getHeight()
                    * Integer.BYTES;
        }
        return bytes;
    }

    private void reloadPerformanceSettings() {
        IMapDisplay current = display;
        effectiveFps = ScreenPolicy.effectiveFps(
                definition.fps(),
                plugin.getConfig().getBoolean("performance.adaptive-fps", true),
                current == null ? 0 : current.width(),
                current == null ? 0 : current.height(),
                plugin.getConfig().getDouble("performance.max-map-updates-per-second", 400),
                plugin.getConfig().getDouble("performance.minimum-fps", 0.2));
        maxDeltaMaps = Math.max(0,
                plugin.getConfig().getInt("performance.delta-updates-max-maps", 256));
        cullLookingAway = plugin.getConfig().getBoolean(
                "performance.skip-viewers-looking-away", true);
        lookAwayAngle = Math.max(60, Math.min(180, plugin.getConfig().getDouble(
                "performance.look-away-angle", 105)));
    }

    private void capturePreviousFrame(FullSpacedColorBuffer current) {
        if (previousFrame == null
                || previousFrame.width() != current.width()
                || previousFrame.height() != current.height()) {
            previousFrame = new FullSpacedColorBuffer(current.width(), current.height());
        }
        System.arraycopy(current.buffer(), 0, previousFrame.buffer(), 0, current.size());
    }

    private static void scaleIntoImage(BufferedImage source, BufferedImage output) {
        int width = output.getWidth();
        int height = output.getHeight();
        Graphics2D graphics = output.createGraphics();
        try {
            graphics.setColor(Color.BLACK);
            graphics.fillRect(0, 0, width, height);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_SPEED);
            double scale = Math.min((double) width / source.getWidth(),
                    (double) height / source.getHeight());
            int scaledWidth = Math.max(1, (int) Math.round(source.getWidth() * scale));
            int scaledHeight = Math.max(1, (int) Math.round(source.getHeight() * scale));
            int x = (width - scaledWidth) / 2;
            int y = (height - scaledHeight) / 2;
            graphics.drawImage(source, x, y, scaledWidth, scaledHeight, null);
        } finally {
            graphics.dispose();
        }
    }

    private static void drawCentered(Graphics2D graphics, String text, int width, int y) {
        int x = (width - graphics.getFontMetrics().stringWidth(text)) / 2;
        graphics.drawString(text, x, y);
    }
}
