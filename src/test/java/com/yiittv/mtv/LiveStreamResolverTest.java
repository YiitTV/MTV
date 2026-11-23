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
        assertTrue(command.contains("-g"));
    }
}
