package com.yiittv.mtv;

import org.bukkit.Bukkit;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Downloads a YouTube video once with yt-dlp into {@code media/youtube/<id>.mp4}
 * so billboards can loop it as a normal local video.
 */
final class YoutubeDownloader {

    static final String FOLDER = "youtube";
    private static final Pattern VIDEO_ID = Pattern.compile("[A-Za-z0-9_-]{11}");
    private static final Pattern QUERY_ID = Pattern.compile("(?:^|&)v=([A-Za-z0-9_-]{11})(?:&|$)");
    private static final Pattern PATH_ID = Pattern.compile(
            "^/(?:shorts|embed|live|v)/([A-Za-z0-9_-]{11})(?:[/?#].*)?$");

    private final MtvPlugin plugin;
    private final Map<String, CompletableFuture<Result>> running = new ConcurrentHashMap<>();
    private ExecutorService executor;

    YoutubeDownloader(MtvPlugin plugin) {
        this.plugin = plugin;
    }

    void start() {
        if (executor == null) {
            executor = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "MTV-YouTube");
                thread.setDaemon(true);
                return thread;
            });
        }
    }

    void shutdown() {
        ExecutorService current = executor;
        executor = null;
        if (current != null) {
            current.shutdownNow();
        }
        running.clear();
    }

    /** Extracts the 11 character video id from common YouTube link forms. */
    static Optional<String> videoId(String link) {
        if (link == null || link.isBlank() || link.length() > 512) {
            return Optional.empty();
        }
        String value = link.trim();
        if (VIDEO_ID.matcher(value).matches()) {
            return Optional.of(value);
        }
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
        if (host.equals("youtu.be")) {
            Matcher matcher = Pattern.compile("^/([A-Za-z0-9_-]{11})/?$").matcher(path);
            return matcher.matches() ? Optional.of(matcher.group(1)) : Optional.empty();
        }
        if (!host.equals("youtube.com") && !host.endsWith(".youtube.com")) {
            return Optional.empty();
        }
        if (path.equals("/watch") && uri.getRawQuery() != null) {
            Matcher matcher = QUERY_ID.matcher(uri.getRawQuery());
            return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
        }
        Matcher matcher = PATH_ID.matcher(path);
        return matcher.matches() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    /** Media-relative path used as the {@code video} source value. */
    static String mediaPath(String videoId) {
        return FOLDER + "/" + videoId + ".mp4";
    }

    static List<String> command(String executable, String format, String maxFileSize,
                                Path outputTemplate, String videoId) {
        List<String> command = new ArrayList<>();
        command.add(executable);
        command.add("--no-playlist");
        command.add("--no-progress");
        command.add("--no-part");
        command.add("--restrict-filenames");
        command.add("-f");
        command.add(format);
        if (maxFileSize != null && !maxFileSize.isBlank()) {
            command.add("--max-filesize");
            command.add(maxFileSize);
        }
        command.add("-o");
        command.add(outputTemplate.toString());
        command.add("--");
        command.add("https://www.youtube.com/watch?v=" + videoId);
        return command;
    }

    /**
     * Ensures the video is present locally. The callback always runs on the main thread.
     */
    void ensure(String videoId, boolean refresh, Consumer<Result> callback) {
        ExecutorService current = executor;
        if (current == null) {
            callback.accept(Result.failed("YouTube downloader is not running"));
            return;
        }
        Path target = plugin.mediaDirectoryPath().resolve(FOLDER).resolve(videoId + ".mp4");
        if (!refresh && Files.isRegularFile(target)) {
            callback.accept(Result.ok(mediaPath(videoId), true));
            return;
        }
        CompletableFuture<Result> future = running.computeIfAbsent(videoId, ignored ->
                CompletableFuture.supplyAsync(() -> download(videoId, target), current)
                        .whenComplete((result, error) -> running.remove(videoId)));
        future.whenComplete((result, error) -> Bukkit.getScheduler().runTask(plugin, () ->
                callback.accept(error != null
                        ? Result.failed(StreamUrlSanitizer.maskError(error))
                        : result)));
    }

    boolean downloading(String videoId) {
        return running.containsKey(videoId);
    }

    private Result download(String videoId, Path target) {
        String executable = plugin.getConfig().getString("youtube.yt-dlp-path", "yt-dlp");
        String format = plugin.getConfig().getString("youtube.format",
                "18/best[ext=mp4][vcodec!=none][acodec!=none]");
        String maxSize = plugin.getConfig().getString("youtube.max-filesize", "500M");
        long timeout = Math.max(30, plugin.getConfig().getLong("youtube.timeout-seconds", 600));
        Path directory = target.getParent();
        Path temporary = directory.resolve(videoId + ".download.mp4");
        try {
            Files.createDirectories(directory);
            Files.deleteIfExists(temporary);
            Process process = new ProcessBuilder(command(
                    executable == null || executable.isBlank() ? "yt-dlp" : executable.trim(),
                    format, maxSize, temporary, videoId))
                    .directory(directory.toFile())
                    .redirectErrorStream(true)
                    .start();
            StringBuilder log = new StringBuilder();
            Thread reader = Thread.ofVirtual().start(() -> readTail(process.getInputStream(), log));
            if (!process.waitFor(timeout, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return Result.failed("yt-dlp timed out after " + timeout + "s");
            }
            reader.join(2_000);
            if (process.exitValue() != 0 || !Files.isRegularFile(temporary)) {
                Files.deleteIfExists(temporary);
                return Result.failed(lastLine(log, "yt-dlp exited with code " + process.exitValue()));
            }
            Files.move(temporary, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return Result.ok(mediaPath(videoId), false);
        } catch (IOException exception) {
            String message = exception.getMessage() == null ? "" : exception.getMessage();
            return Result.failed(message.contains("Cannot run program")
                    ? "yt-dlp was not found; install it or set youtube.yt-dlp-path"
                    : StreamUrlSanitizer.maskError(exception));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Result.failed("download interrupted");
        }
    }

    private static void readTail(InputStream input, StringBuilder log) {
        try (input) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                synchronized (log) {
                    log.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
                    if (log.length() > 8192) {
                        log.delete(0, log.length() - 8192);
                    }
                }
            }
        } catch (IOException ignored) {
        }
    }

    private static String lastLine(StringBuilder log, String fallback) {
        String text;
        synchronized (log) {
            text = log.toString().trim();
        }
        if (text.isBlank()) {
            return fallback;
        }
        String[] lines = text.split("\\R");
        for (int index = lines.length - 1; index >= 0; index--) {
            String line = lines[index].trim();
            if (line.startsWith("ERROR")) {
                return truncate(line);
            }
        }
        return truncate(lines[lines.length - 1].trim());
    }

    private static String truncate(String value) {
        return value.length() > 200 ? value.substring(0, 200) : value;
    }

    record Result(boolean ok, String mediaPath, boolean cached, String error) {
        static Result ok(String mediaPath, boolean cached) {
            return new Result(true, mediaPath, cached, "");
        }

        static Result failed(String error) {
            return new Result(false, "", false, error == null ? "unknown error" : error);
        }
    }
}
