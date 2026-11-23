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

    /**
     * Combined stream first (Twitch, Kick, older YouTube lives), otherwise separate video
     * and audio playlists (current YouTube lives).
     */
    static final String DEFAULT_FORMAT =
            "best[height<=480]/bv*[height<=480]+ba/bv*+ba/best";
    static final String NOT_LIVE = "this YouTube video is not live; use /mtv source <billboard> youtube <link>";

    private LiveStreamResolver() {
    }

    private static final java.util.regex.Pattern COMBINED_ONLY =
            java.util.regex.Pattern.compile("best\\[height<=(\\d{3,4})]/best");

    /**
     * Upgrades the old combined-only format (saved in existing config.yml files), which fails
     * on YouTube lives that only offer separate video and audio playlists.
     */
    static String format(String configured) {
        if (configured == null || configured.isBlank()) {
            return DEFAULT_FORMAT;
        }
        String value = configured.trim();
        java.util.regex.Matcher matcher = COMBINED_ONLY.matcher(value);
        return matcher.matches()
                ? QualityPreset.liveFormat(Integer.parseInt(matcher.group(1))) : value;
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
    /**
     * Stream addresses for one live page. YouTube often serves live video and audio as
     * separate HLS playlists; Twitch and Kick serve one combined playlist.
     */
    record LiveStream(String video, String audio) {
        boolean separateAudio() {
            return audio != null && !audio.isBlank();
        }
    }

    /** Runs yt-dlp and returns the stream address(es). Blocking. */
    static LiveStream resolve(String executable, String format, String page, long timeoutSeconds)
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
            return parse(output, process.exitValue(), canonical);
        } finally {
            if (process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
        }
    }

    static LiveStream parse(String output, int exitCode, String canonical) throws IOException {
        String error = null;
        Boolean live = null;
        List<String> urls = new ArrayList<>();
        for (String line : output.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.equals("True") || trimmed.equals("False")) {
                live = trimmed.equals("True");
            } else if (trimmed.startsWith("https://") || trimmed.startsWith("http://")) {
                urls.add(trimmed);
            } else if (trimmed.startsWith("ERROR")) {
                error = trimmed;
            }
        }
        if (exitCode == 0 && !urls.isEmpty()) {
            if (Boolean.FALSE.equals(live) && canonical.contains("youtube.com/watch")) {
                throw new IOException(NOT_LIVE);
            }
            // With "bv+ba" yt-dlp prints the video playlist first, then the audio playlist.
            return new LiveStream(urls.getFirst(), urls.size() > 1 ? urls.get(1) : null);
        }
        if (error != null && error.toLowerCase(Locale.ROOT).contains("offline")) {
            throw new IOException("the channel is offline");
        }
        throw new IOException(error == null ? "no stream address (is the channel live?)"
                : error.length() > 160 ? error.substring(0, 160) : error);
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
