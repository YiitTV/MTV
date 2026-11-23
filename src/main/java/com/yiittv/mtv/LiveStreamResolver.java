package com.yiittv.mtv;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Turns a YouTube, Twitch or Kick live page into a playable HLS address with yt-dlp.
 * Resolved addresses expire, so the decoder calls this again on every reconnect.
 */
final class LiveStreamResolver {

    static final String DEFAULT_FORMAT = "best[height<=480]/best";
    static final String NOT_LIVE = "this YouTube video is not live; use /mtv source <billboard> youtube <link>";

    private LiveStreamResolver() {
    }

    /** Returns a canonical https page URL for supported platforms, or empty. */
    static Optional<String> canonicalPage(String link) {
        if (link == null || link.isBlank() || link.length() > 512) {
            return Optional.empty();
        }
        String value = link.trim();
        URI uri;
        try {
            uri = URI.create(value.contains("://") ? value : "https://" + value);
        } catch (IllegalArgumentException invalid) {
            return Optional.empty();
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !scheme.equals("http")) {
            return Optional.empty();
        }
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        if (isHost(host, "twitch.tv")) {
            String channel = firstSegment(path);
            return channel != null && channel.matches("[A-Za-z0-9_]{2,25}")
                    && !List.of("videos", "directory", "settings", "search", "downloads")
                    .contains(channel.toLowerCase(Locale.ROOT))
                    ? Optional.of("https://www.twitch.tv/" + channel.toLowerCase(Locale.ROOT))
                    : Optional.empty();
        }
        if (isHost(host, "kick.com")) {
            String channel = firstSegment(path);
            return channel != null && channel.matches("[A-Za-z0-9_-]{2,40}")
                    ? Optional.of("https://kick.com/" + channel.toLowerCase(Locale.ROOT))
                    : Optional.empty();
        }
        if (isHost(host, "youtube.com") || host.equals("youtu.be")) {
            Optional<String> video = YoutubeDownloader.videoId(value);
            if (video.isPresent()) {
                return Optional.of("https://www.youtube.com/watch?v=" + video.get());
            }
            // Channel live pages: /@name/live or /channel/<id>/live
            if (isHost(host, "youtube.com")
                    && path.matches("/(@[A-Za-z0-9._-]{1,100}|channel/UC[A-Za-z0-9_-]{22})/live/?")) {
                return Optional.of("https://www.youtube.com" + path.replaceAll("/$", ""));
            }
        }
        return Optional.empty();
    }

    static boolean supported(String link) {
        return canonicalPage(link).isPresent();
    }

    static List<String> command(String executable, String format, String page) {
        List<String> command = new ArrayList<>();
        command.add(executable);
        command.add("--no-playlist");
        command.add("--no-warnings");
        command.add("-f");
        command.add(format);
        // First line: whether the page is live; then the stream address(es).
        command.add("--print");
        command.add("is_live");
        command.add("--print");
        command.add("urls");
        command.add("--");
        command.add(page);
        return command;
    }

    /** Runs yt-dlp and returns the first stream address it prints. Blocking. */
    static String resolve(String executable, String format, String page, long timeoutSeconds)
            throws IOException, InterruptedException {
        String canonical = canonicalPage(page)
                .orElseThrow(() -> new IOException("unsupported live link"));
        Process process;
        try {
            process = new ProcessBuilder(command(executable, format, canonical))
                    .redirectErrorStream(true)
                    .start();
        } catch (IOException exception) {
            throw new IOException("yt-dlp was not found; install it or set youtube.yt-dlp-path");
        }
        try {
            String output;
            try (InputStream input = process.getInputStream()) {
                if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                    throw new IOException("yt-dlp timed out while resolving the stream");
                }
                output = new String(input.readNBytes(65_536), StandardCharsets.UTF_8);
            }
            String error = null;
            Boolean live = null;
            for (String line : output.split("\\R")) {
                String trimmed = line.trim();
                if (live == null && (trimmed.equals("True") || trimmed.equals("False")
                        || trimmed.equals("NA"))) {
                    live = trimmed.equals("True");
                    continue;
                }
                if (trimmed.startsWith("https://") || trimmed.startsWith("http://")) {
                    if (process.exitValue() == 0) {
                        if (Boolean.FALSE.equals(live) && canonical.contains("youtube.com/watch")) {
                            throw new IOException(NOT_LIVE);
                        }
                        return trimmed;
                    }
                }
                if (trimmed.startsWith("ERROR")) {
                    error = trimmed;
                }
            }
            if (error != null && canonical.contains("youtube.com/watch")
                    && error.contains("Requested format is not available")) {
                // Regular uploads have no combined audio+video stream to play live.
                throw new IOException(NOT_LIVE);
            }
            if (error != null && error.toLowerCase(Locale.ROOT).contains("offline")) {
                throw new IOException("the channel is offline");
            }
            throw new IOException(error == null ? "no stream address (is the channel live?)"
                    : error.length() > 160 ? error.substring(0, 160) : error);
        } finally {
            if (process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
        }
    }

    private static boolean isHost(String host, String domain) {
        return host.equals(domain) || host.endsWith("." + domain);
    }

    private static String firstSegment(String path) {
        String[] parts = path.split("/");
        for (String part : parts) {
            if (!part.isBlank()) {
                return part;
            }
        }
        return null;
    }
}
