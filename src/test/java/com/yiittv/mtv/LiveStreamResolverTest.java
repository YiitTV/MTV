package com.yiittv.mtv;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveStreamResolverTest {

    @Test
    void normalisesSupportedPlatforms() {
        assertEquals(Optional.of("https://www.twitch.tv/somechannel"),
                LiveStreamResolver.canonicalPage("https://www.twitch.tv/SomeChannel"));
        assertEquals(Optional.of("https://www.twitch.tv/somechannel"),
                LiveStreamResolver.canonicalPage("twitch.tv/somechannel?sr=a"));
        assertEquals(Optional.of("https://kick.com/some-channel"),
                LiveStreamResolver.canonicalPage("https://kick.com/some-channel"));
        assertEquals(Optional.of("https://www.youtube.com/watch?v=dQw4w9WgXcQ"),
                LiveStreamResolver.canonicalPage("https://youtu.be/dQw4w9WgXcQ"));
        assertEquals(Optional.of("https://www.youtube.com/@MyServer/live"),
                LiveStreamResolver.canonicalPage("https://www.youtube.com/@MyServer/live"));
    }

    @Test
    void rejectsOtherSites() {
        for (String link : List.of("https://example.com/live", "https://twitch.tv.evil.net/x",
                "https://www.twitch.tv/videos/123", "ftp://kick.com/abc", "", "--exec")) {
            assertTrue(LiveStreamResolver.canonicalPage(link).isEmpty(), link);
        }
    }

    @Test
    void liveSourcesAreRemoteFfmpegSources() {
        ScreenSource source = new ScreenSource(SourceType.LIVE, "https://kick.com/abc");
        assertTrue(source.isValid());
        assertTrue(source.usesRemoteLocation());
        assertTrue(SourceType.LIVE.usesFfmpeg());
        assertFalse(SourceType.LIVE.loopsAtEnd());
        assertEquals(SourceType.LIVE, SourceType.parse("twitch"));
        List<String> command = LiveStreamResolver.command("yt-dlp", "best", "https://kick.com/abc");
        assertEquals("--", command.get(command.size() - 2));
        assertTrue(command.contains("is_live"));
    }

    @Test
    void parsesSeparateYoutubeVideoAndAudioPlaylists() throws Exception {
        LiveStreamResolver.LiveStream stream = LiveStreamResolver.parse(
                "True\nhttps://v.example/video.m3u8\nhttps://v.example/audio.m3u8\n", 0,
                "https://www.youtube.com/watch?v=dQw4w9WgXcQ");
        assertEquals("https://v.example/video.m3u8", stream.video());
        assertEquals("https://v.example/audio.m3u8", stream.audio());
        assertTrue(stream.separateAudio());

        LiveStreamResolver.LiveStream combined = LiveStreamResolver.parse(
                "True\nhttps://twitch.example/index.m3u8\n", 0, "https://www.twitch.tv/abc");
        assertFalse(combined.separateAudio());
    }

    @Test
    void reportsRealErrorsInsteadOfGuessingNotLive() {
        java.io.IOException notLive = org.junit.jupiter.api.Assertions.assertThrows(
                java.io.IOException.class, () -> LiveStreamResolver.parse(
                        "False\nhttps://v.example/a\n", 0,
                        "https://www.youtube.com/watch?v=dQw4w9WgXcQ"));
        assertEquals(LiveStreamResolver.NOT_LIVE, notLive.getMessage());

        java.io.IOException format = org.junit.jupiter.api.Assertions.assertThrows(
                java.io.IOException.class, () -> LiveStreamResolver.parse(
                        "ERROR: [youtube] x: Requested format is not available\n", 1,
                        "https://www.youtube.com/watch?v=dQw4w9WgXcQ"));
        assertTrue(format.getMessage().contains("Requested format"));
    }
}
