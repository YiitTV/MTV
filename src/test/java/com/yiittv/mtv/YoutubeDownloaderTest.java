package com.yiittv.mtv;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YoutubeDownloaderTest {

    @Test
    void extractsIdsFromCommonLinks() {
        String id = "dQw4w9WgXcQ";
        for (String link : List.of(
                "https://www.youtube.com/watch?v=" + id,
                "https://youtube.com/watch?feature=share&v=" + id + "&t=10",
                "youtube.com/watch?v=" + id,
                "https://m.youtube.com/watch?v=" + id,
                "https://youtu.be/" + id,
                "https://www.youtube.com/shorts/" + id,
                "https://www.youtube.com/embed/" + id + "?start=3",
                id)) {
            assertEquals(Optional.of(id), YoutubeDownloader.videoId(link), link);
        }
    }

    @Test
    void rejectsOtherHostsAndMalformedLinks() {
        for (String link : List.of(
                "https://example.com/watch?v=dQw4w9WgXcQ",
                "https://youtube.com.evil.net/watch?v=dQw4w9WgXcQ",
                "https://www.youtube.com/watch?v=short",
                "file:///etc/passwd",
                "https://youtu.be/dQw4w9WgXcQ/../x",
                "--exec=rm",
                "")) {
            assertTrue(YoutubeDownloader.videoId(link).isEmpty(), link);
        }
    }

    @Test
    void buildsACanonicalCommandWithoutUserText() {
        List<String> command = YoutubeDownloader.command("yt-dlp", "18", "500M",
                "dQw4w9WgXcQ");
        assertEquals("yt-dlp", command.getFirst());
        assertEquals("--", command.get(command.size() - 2));
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", command.getLast());
        assertTrue(command.contains("--no-playlist"));
        int output = command.indexOf("-o");
        assertEquals("dQw4w9WgXcQ.download.%(ext)s", command.get(output + 1));
        assertTrue(command.contains("--merge-output-format"));
        assertEquals("youtube/dQw4w9WgXcQ.mp4", YoutubeDownloader.mediaPath("dQw4w9WgXcQ"));
    }
}
