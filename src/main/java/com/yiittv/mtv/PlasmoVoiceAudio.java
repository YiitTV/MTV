package com.yiittv.mtv;

import su.plo.slib.api.server.position.ServerPos3d;
import su.plo.slib.api.server.world.McServerWorld;
import su.plo.voice.api.addon.AddonInitializer;
import su.plo.voice.api.addon.InjectPlasmoVoice;
import su.plo.voice.api.addon.annotation.Addon;
import su.plo.voice.api.audio.codec.AudioEncoder;
import su.plo.voice.api.audio.codec.CodecException;
import su.plo.voice.api.server.PlasmoVoiceServer;
import su.plo.voice.api.server.audio.line.ServerSourceLine;
import su.plo.voice.api.server.audio.provider.AudioFrameProvider;
import su.plo.voice.api.server.audio.provider.AudioFrameResult;
import su.plo.voice.api.server.audio.source.AudioSender;
import su.plo.voice.api.server.audio.source.ServerStaticSource;
import su.plo.voice.api.server.config.ServerConfig;
import org.bukkit.World;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * The only class that references the Plasmo Voice API. It is loaded reflectively by
 * {@link ScreenAudioManager} and only when the PlasmoVoice plugin is installed.
 */
@Addon(id = "pv-addon-mtv", name = "MTV", version = "1.0.0", authors = {"YiitTV"})
public final class PlasmoVoiceAudio implements ScreenAudioOutput, AddonInitializer {

    private static final int DEFAULT_SAMPLE_RATE = 48_000;

    private final MtvPlugin plugin;

    @InjectPlasmoVoice
    private PlasmoVoiceServer voiceServer;

    private volatile ServerSourceLine sourceLine;

    public PlasmoVoiceAudio(MtvPlugin plugin) {
        this.plugin = plugin;
        PlasmoVoiceServer.getAddonsLoader().load(this);
    }

    @Override
    public void onAddonInitialize() {
        PlasmoVoiceServer server = voiceServer;
        if (server == null) {
            return;
        }
        try {
            sourceLine = server.getSourceLineManager()
                    .createBuilder(this, "mtv", "MTV", new ByteArrayInputStream(icon()), 10)
                    .build();
            plugin.getLogger().info("Plasmo Voice detected: MTV screen audio is available.");
        } catch (RuntimeException | IOException exception) {
            sourceLine = null;
            plugin.getLogger().warning("Could not register the MTV Plasmo Voice source line: "
                    + StreamUrlSanitizer.maskError(exception));
        }
    }

    @Override
    public void onAddonShutdown() {
        sourceLine = null;
    }

    @Override
    public boolean ready() {
        return voiceServer != null && sourceLine != null;
    }

    @Override
    public int sampleRate() {
        PlasmoVoiceServer server = voiceServer;
        ServerConfig config = server == null ? null : server.getConfig();
        int configured = config == null ? DEFAULT_SAMPLE_RATE : config.voice().sampleRate();
        return configured > 0 ? configured : DEFAULT_SAMPLE_RATE;
    }

    @Override
    public String status() {
        if (voiceServer == null) return "waiting for Plasmo Voice";
        if (sourceLine == null) return "source line unavailable";
        return "ready (" + sampleRate() + " Hz)";
    }

    @Override
    public AudioFrameEncoder createEncoder() {
        PlasmoVoiceServer server = voiceServer;
        if (server == null) {
            return null;
        }
        AudioEncoder encoder = server.createOpusEncoder(false);
        try {
            if (!encoder.isOpen()) {
                encoder.open();
            }
        } catch (CodecException exception) {
            encoder.close();
            return null;
        }
        return new AudioFrameEncoder() {
            @Override
            public byte[] encode(short[] samples) {
                try {
                    return encoder.encode(samples);
                } catch (CodecException exception) {
                    return null;
                }
            }

            @Override
            public void close() {
                encoder.close();
            }
        };
    }

    @Override
    public ScreenAudioChannel open(World world, double x, double y, double z,
                                   ScreenAudioChannel.DistanceSupplier distance) {
        PlasmoVoiceServer server = voiceServer;
        ServerSourceLine line = sourceLine;
        if (server == null || line == null || world == null) {
            return null;
        }
        McServerWorld voiceWorld = server.getMinecraftServer().getWorld(world);
        ServerStaticSource source = line.createStaticSource(
                new ServerPos3d(voiceWorld, x, y, z), false);
        Channel channel = new Channel(line, source);
        AudioSender sender = source.createAudioSender(channel,
                () -> (short) Math.max(1, Math.min(Short.MAX_VALUE, distance.distance())));
        channel.sender = sender;
        sender.start();
        return channel;
    }

    @Override
    public void shutdown() {
        try {
            PlasmoVoiceServer.getAddonsLoader().unload(this);
        } catch (RuntimeException ignored) {
        }
        sourceLine = null;
    }

    private static byte[] icon() throws IOException {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(new Color(44, 212, 112));
            graphics.fillRect(1, 3, 14, 9);
            graphics.setColor(new Color(10, 18, 14));
            graphics.fillRect(3, 5, 10, 5);
            graphics.setColor(new Color(44, 212, 112));
            graphics.fillRect(7, 12, 2, 2);
            graphics.fillRect(4, 14, 8, 1);
        } finally {
            graphics.dispose();
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    private static final class Channel implements ScreenAudioChannel, AudioFrameProvider {

        private static final int MAX_BUFFERED_FRAMES = 50;

        private final ServerSourceLine line;
        private final ServerStaticSource source;
        private final BlockingQueue<byte[]> frames = new ArrayBlockingQueue<>(MAX_BUFFERED_FRAMES);
        private volatile AudioSender sender;
        private volatile boolean closed;

        private Channel(ServerSourceLine line, ServerStaticSource source) {
            this.line = line;
            this.source = source;
        }

        @Override
        public AudioFrameResult provide20ms() {
            if (closed) {
                return AudioFrameResult.Finished.INSTANCE;
            }
            try {
                byte[] frame = frames.poll(20, TimeUnit.MILLISECONDS);
                if (frame != null) {
                    return new AudioFrameResult.Provided(frame);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return AudioFrameResult.Finished.INSTANCE;
            }
            return closed ? AudioFrameResult.Finished.INSTANCE
                    : AudioFrameResult.EndOfStream.INSTANCE;
        }

        @Override
        public void offer(byte[] frame) {
            if (closed || frame == null) {
                return;
            }
            while (!frames.offer(frame)) {
                frames.poll();
            }
        }

        @Override
        public void flush() {
            frames.clear();
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            frames.clear();
            AudioSender current = sender;
            if (current != null) {
                current.stop();
            }
            try {
                line.removeSource(source);
            } catch (RuntimeException ignored) {
            }
        }
    }
}
