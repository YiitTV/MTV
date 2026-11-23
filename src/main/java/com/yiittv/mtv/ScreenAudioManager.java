package com.yiittv.mtv;

import org.bukkit.Bukkit;
import org.bukkit.World;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-screen positional audio. Screens opt in with {@code screens.<id>.audio.enabled};
 * audio is sent through Plasmo Voice when that plugin is installed.
 */
final class ScreenAudioManager {

    static final int MIN_DISTANCE = 4;
    static final int MAX_DISTANCE = 512;
    private static final String PLASMO_PLUGIN = "PlasmoVoice";
    private static final String PLASMO_BRIDGE = "com.yiittv.mtv.PlasmoVoiceAudio";

    private final MtvPlugin plugin;
    private final Map<String, OpenChannel> channels = new ConcurrentHashMap<>();
    private volatile ScreenAudioOutput output;
    private volatile String unavailableReason = "not started";
    private volatile double volume = 1;

    ScreenAudioManager(MtvPlugin plugin) {
        this.plugin = plugin;
    }

    void start() {
        reloadSettings();
        if (output != null) {
            return;
        }
        if (!plugin.getConfig().getBoolean("audio.enabled", true)) {
            unavailableReason = "disabled in config.yml (audio.enabled)";
            return;
        }
        if (Bukkit.getPluginManager().getPlugin(PLASMO_PLUGIN) == null) {
            unavailableReason = "Plasmo Voice is not installed";
            return;
        }
        try {
            output = (ScreenAudioOutput) Class.forName(PLASMO_BRIDGE)
                    .getConstructor(MtvPlugin.class)
                    .newInstance(plugin);
            unavailableReason = "";
        } catch (ReflectiveOperationException | LinkageError | RuntimeException exception) {
            output = null;
            unavailableReason = "Plasmo Voice API is incompatible";
            plugin.getLogger().warning("Could not connect to Plasmo Voice: "
                    + StreamUrlSanitizer.maskError(exception));
        }
    }

    void stop() {
        closeAll();
        ScreenAudioOutput current = output;
        output = null;
        unavailableReason = "stopped";
        if (current != null) {
            current.shutdown();
        }
    }

    void reloadSettings() {
        volume = Math.max(0, Math.min(2, plugin.getConfig().getDouble("audio.volume", 1)));
    }

    boolean available() {
        ScreenAudioOutput current = output;
        return current != null && current.ready();
    }

    int sampleRate() {
        ScreenAudioOutput current = output;
        return current == null ? 48_000 : current.sampleRate();
    }

    double volume() {
        return volume;
    }

    String status() {
        ScreenAudioOutput current = output;
        return current == null ? "off: " + unavailableReason : current.status();
    }

    ScreenAudioOutput.AudioFrameEncoder createEncoder() {
        ScreenAudioOutput current = output;
        return current == null || !current.ready() ? null : current.createEncoder();
    }

    ScreenAudioSettings settings(String screenId) {
        String path = "screens." + ScreenDefinition.normalizeId(screenId) + ".audio.";
        boolean enabled = plugin.getConfig().getBoolean(path + "enabled",
                plugin.getConfig().getBoolean("audio.default-enabled", false));
        int distance = clampDistance(plugin.getConfig().getInt(path + "distance",
                plugin.getConfig().getInt("audio.default-distance", 32)));
        return new ScreenAudioSettings(enabled, distance);
    }

    static int clampDistance(int value) {
        return Math.max(MIN_DISTANCE, Math.min(MAX_DISTANCE, value));
    }

    static boolean validDistance(double value) {
        return Double.isFinite(value) && value >= MIN_DISTANCE && value <= MAX_DISTANCE;
    }

    boolean channelOpen(String screenId) {
        return channels.containsKey(ScreenDefinition.normalizeId(screenId));
    }

    /** Opens and closes emitters to match screen settings. Main thread only. */
    void sync(Collection<ManagedScreen> screens) {
        Set<String> wanted = new HashSet<>();
        ScreenAudioOutput current = output;
        boolean ready = current != null && current.ready();
        for (ManagedScreen screen : screens) {
            ScreenAudioSettings settings = settings(screen.id());
            screen.updateAudio(settings.enabled(), settings.distance());
            if (!ready || !settings.enabled() || !screen.enabled()) {
                continue;
            }
            double[] center = screen.center();
            World world = screen.world();
            if (center == null || world == null) {
                continue;
            }
            wanted.add(screen.id());
            OpenChannel existing = channels.get(screen.id());
            if (existing != null && existing.matches(world, center)) {
                continue;
            }
            if (existing != null) {
                channels.remove(screen.id(), existing);
                existing.channel().close();
            }
            ScreenAudioChannel channel = current.open(world, center[0], center[1], center[2],
                    screen::audioDistance);
            if (channel != null) {
                channels.put(screen.id(), new OpenChannel(channel, world, center));
            }
        }
        for (Map.Entry<String, OpenChannel> entry : channels.entrySet()) {
            if (!wanted.contains(entry.getKey())
                    && channels.remove(entry.getKey(), entry.getValue())) {
                entry.getValue().channel().close();
            }
        }
    }

    void offer(String screenId, byte[] frame) {
        OpenChannel open = channels.get(screenId);
        if (open != null) {
            open.channel().offer(frame);
        }
    }

    String channelDiagnostics(String screenId) {
        OpenChannel open = channels.get(ScreenDefinition.normalizeId(screenId));
        return open == null ? "no emitter" : open.channel().diagnostics();
    }

    void flush(String screenId) {
        OpenChannel open = channels.get(ScreenDefinition.normalizeId(screenId));
        if (open != null) {
            open.channel().flush();
        }
    }

    void close(String screenId) {
        OpenChannel open = channels.remove(ScreenDefinition.normalizeId(screenId));
        if (open != null) {
            open.channel().close();
        }
    }

    private void closeAll() {
        for (String id : channels.keySet()) {
            close(id);
        }
    }

    record ScreenAudioSettings(boolean enabled, int distance) {
    }

    private record OpenChannel(ScreenAudioChannel channel, World world, double[] center) {
        boolean matches(World otherWorld, double[] otherCenter) {
            return world == otherWorld
                    && Math.abs(center[0] - otherCenter[0]) < 0.01
                    && Math.abs(center[1] - otherCenter[1]) < 0.01
                    && Math.abs(center[2] - otherCenter[2]) < 0.01;
        }
    }
}
