package com.yiittv.mtv;

import java.util.List;
import java.util.Locale;

/** Billboard quality presets used by {@code /mtv quality}. */
enum QualityPreset {
    LOW(4, false),
    MEDIUM(8, false),
    HIGH(12, true),
    ULTRA(20, true);

    static final List<Integer> SOURCE_HEIGHTS = List.of(360, 480, 720, 1080);

    private final double fps;
    private final boolean dithering;

    QualityPreset(double fps, boolean dithering) {
        this.fps = fps;
        this.dithering = dithering;
    }

    double fps() {
        return fps;
    }

    boolean dithering() {
        return dithering;
    }

    String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    static QualityPreset parse(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "low", "dusuk" -> LOW;
            case "medium", "orta", "normal" -> MEDIUM;
            case "high", "yuksek" -> HIGH;
            case "ultra", "max" -> ULTRA;
            default -> null;
        };
    }

    static String youtubeFormat(int height) {
        String cap = "[height<=" + height + "]";
        String single = height <= 360 ? "18/" : "";
        return single + "b[ext=mp4]" + cap + "/bv*" + cap + "[ext=mp4]+ba[ext=m4a]/bv*"
                + cap + "+ba/b" + cap + "/b";
    }

    static String liveFormat(int height) {
        return "best[height<=" + height + "]/best";
    }
}
