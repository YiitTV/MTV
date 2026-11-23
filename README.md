# MTV

MTV is a server-side Paper plugin for billboards: it displays live streams,
videos, images and GIFs on configurable walls of Minecraft maps, with optional
positional audio through Plasmo Voice.

MTV is an **unofficial, modified build based on LuigiScreen Free** by
unknown_56. It is not endorsed by the LuigiScreen project.

MTV loads the selected media source, decodes its latest frame and
renders it through MapEngine. Players do not need a client mod.

> MTV is currently alpha software. Back up the server and test new
> builds away from production.

## Features

- In-game Control Studio opened with `/mtv menu`
- Browser Web Studio opened securely with `/mtv web`
- One-time login links, revocable sessions, CSRF protection and role-based API access
- Preview/Program Live Studio with bounded live source thumbnails
- Clean hover help and accessible descriptions without permanent info icons
- Dashboard, screen details, Live Control Room and diagnostics
- Media Library with automatic folder watching, validation and map thumbnails
- Draft and Publish editing with config snapshots and one-click undo
- Content queues, screen groups, schedules, templates and audience voting
- Per-role permissions for every Control Studio section and action
- Playback explanations, eligibility diagnostics and aggregate usage statistics
- RTMP and MJPEG live streams
- Looping local videos and GIFs
- Local and URL images
- Per-screen playlists with weighted random media rotation
- Manual event scenes that temporarily interrupt normal playback
- Random media folders for videos, images and GIFs
- Multiple named screens with independent settings
- Shared decoding/loading for screens using the same source type and value
- Configurable dimensions, locations and orientation
- Automatic reconnect with exponential backoff
- Decoder pause when no players are near the screen
- Adaptive FPS and map-update limits
- Guided MediaMTX setup for five network situations
- Czech and English localization
- Granular permission for every management command
- Optional `mtv.see.<screen>` visibility permission per screen
- Performance boss bar and debug sidebar
- Asynchronous Modrinth update notifications for the console and authorized players
- Persistent screen configuration
- Per-screen positional audio through Plasmo Voice (optional)
- Masked remote-source credentials in plugin output
- Windows x86_64 and Linux x86_64 FFmpeg natives

## Requirements

- Paper 1.21.11
- Java 21
- MapEngine 1.8.12
- Plasmo Voice (optional, only for screen audio; players need the Plasmo Voice mod)
- MediaMTX and OBS Studio only when using RTMP
- Windows x86_64 or Linux x86_64 server

MTV uses the Bukkit plugin ecosystem but currently targets Paper APIs.
Install it on Paper, not on a plain Spigot or CraftBukkit server.

MapEngine is a required runtime dependency:
https://modrinth.com/plugin/mapengine

## Installation

1. Stop the Paper server completely.
2. Install MapEngine 1.8.12.
3. Put the MTV JAR in `plugins/`.
4. Start the server.
5. Create a screen while looking at the upper-left block of a vertical wall.
6. Select a source with `/mtv source`.

Example:

```text
/mtv create main 7 4
/mtv source main image logo.png
/mtv start main
```

Upstream LuigiScreen documentation (commands use `/screen` there; in MTV use `/mtv`):
https://unknown-56-works.gitbook.io/luigiscreen/

## Commands

| Command | Description |
| --- | --- |
| `/mtv menu` | Open MTV Control Studio |
| `/mtv web [open\|status\|revoke]` | Open, inspect or revoke Web Studio sessions |
| `/mtv create <name> [width] [height]` | Create a named map screen |
| `/mtv clone <source> <new-name>` | Create another screen that shares its typed source |
| `/mtv list` | List all screens and masked source values |
| `/mtv start <name\|all>` | Enable one or every screen |
| `/mtv stop <name\|all>` | Disable streaming without deleting screens |
| `/mtv remove <name>` | Remove one screen |
| `/mtv status [name]` | Show the registry summary or detailed screen state |
| `/mtv source <name> <type> <value>` | Switch RTMP, MJPEG, video, image, URL image or GIF |
| `/mtv playlist list|set|clear ...` | Assign or clear a configured media playlist |
| `/mtv event list|play|stop ...` | Play or stop a configured manual event scene |
| `/mtv set <name> <url\|fps\|distance\|enabled\|permission\|audio\|audio-distance> <value>` | Change an independent screen setting |
| `/mtv reload` | Reload configuration and localization without destroying screens |
| `/mtv debug` | Toggle live performance statistics |
| `/mtv mediamtx <situation>` | Generate a MediaMTX configuration |
| `/mtv vote <screen> <option>` | Vote for the next media item |
| `/mtv vote start\|status\|end <screen> [options...]` | Manage an audience vote |

`mtv.admin` is granted to operators and includes every command plus
access to all protected screens. Individual permissions include
`mtv.create`, `mtv.remove`, `mtv.start`,
`mtv.stop`, `mtv.status` and equivalent nodes for the other
commands.

`mtv.update` receives a clickable notification when a newer public
version is available on Modrinth. It defaults to operators and is included in
`mtv.admin`. The asynchronous checker can be configured or disabled
under `updates:` in `config.yml`.

Screens are public by default. Enable protection with:

```text
/mtv set main permission true
```

Then grant `mtv.see.main` or `mtv.see.*` through the server's
permission plugin.

## YouTube videos on billboards

Billboards are named screens. Create each one while looking at its wall, then
point one or more billboards at a YouTube link:

```text
/mtv create spawn_billboard 7 4
/mtv create market_billboard 5 3
/mtv source spawn_billboard,market_billboard youtube https://youtu.be/VIDEO_ID
/mtv source spawn_billboard youtube https://youtu.be/VIDEO_ID refresh
```

- MTV downloads the video once with [yt-dlp](https://github.com/yt-dlp/yt-dlp)
  into `media/youtube/<id>.mp4` and loops it as a local video. Every billboard
  showing the same link shares that file and one decoder.
- yt-dlp must be installed on the server (`youtube.yt-dlp-path` in `config.yml`).
- `refresh` downloads the video again, for example after you re-upload it.
- Only show videos you own or have the right to show; YouTube's terms apply.

## Looping several videos

`/mtv loop` builds a playlist from YouTube links and media files and loops it on
one or more billboards:

```text
/mtv loop spawn_billboard sequence https://youtu.be/AAA https://youtu.be/BBB intro.mp4
/mtv loop spawn_billboard,market_billboard shuffle https://youtu.be/AAA https://youtu.be/BBB
/mtv loop spawn_billboard stop
```

- `sequence` plays the videos in the given order and starts again after the last.
- `shuffle` plays every video once per round in a new random order and never
  repeats the last video at the start of the next round.
- Each video plays for its full length (`duration: auto`, read from the file).
- The playlist is saved as `loop_<billboard>` in `config.yml`. Any playlist can use
  `order: random|sequence|shuffle`, and video items can use `duration: auto`.
- A single video on a billboard already loops by itself.

## Audio (Plasmo Voice)

MTV can play the audio of videos and RTMP streams at the screen position
through [Plasmo Voice](https://modrinth.com/plugin/plasmo-voice). Install the
PlasmoVoice server plugin; players hear the screen when they use the Plasmo
Voice client mod. Without Plasmo Voice MTV runs as a silent billboard.

Audio is off by default and is enabled per screen:

```text
/mtv set main audio true
/mtv set main audio-distance 48
/mtv status main
```

- Audio is decoded only while at least one screen using that source has audio on.
- Screens sharing one source share one decoder and one Opus encoder; each
  screen gets its own emitter at the centre of its map wall.
- Images, MJPEG cameras and GIFs have no audio.
- The decoder keeps running while a player is within the audio distance, even
  outside the viewer distance.
- Turning audio on or off reconnects that source, so local and YouTube videos
  restart from the beginning.
- Cloning a screen copies its audio settings.
- Global settings live under `audio:` in `config.yml` (`enabled`,
  `default-enabled`, `default-distance`, `volume`). Web Studio can stage
  `audio.enabled` and `audio.distance` per screen.
- Players with the Plasmo Voice mod can change the MTV volume in the mod's
  volume menu.

## Building

Builds require JDK 21 and Maven:

```text
mvn clean verify
```

The shaded plugin JAR is created in `target/`.

## Verification

The current suite contains 78 automated tests covering:

- RTMP URL and error-message sanitization
- Screen corner calculation for every vertical direction
- Screen dimensions and total-map validation
- Configuration limit clamping
- Adaptive FPS, minimum FPS and maximum FPS limits
- Named screen validation and legacy geometry migration helpers
- Typed source validation, aliases and shared-source grouping
- Reference-counted shared video-frame lifetime
- Granular command permission metadata
- Per-screen visibility permission naming and defaults
- MediaMTX generation
- Localization files and debug text formatting
- Duration parsing for playlist and event timing
- Zero-copy MapEngine render-surface wrapping
- Semantic alpha, beta and release version ordering
- Modrinth version-feed selection
- Control Studio language and permission resources
- media thumbnail generation
- Web Studio JSON serialization, login security and bundled resources
- Audio framing, volume and audio distance limits
- YouTube link parsing and yt-dlp command construction
- Loop playlist order parsing and generated playlist names

## Platform limits

The current artifact supports Windows x86_64 and Linux x86_64. It does not
support ARM, macOS or Folia.

The default per-screen limit is 10x6 maps and 60 maps. Larger screens can
consume substantial CPU, memory and network bandwidth.

Each screen stores its own `source.type`, `source.value`, `playlist`, `fps`,
`distance`, `world`, `location`, `width`, `height`, `enabled` and
`permission-required` value. Screens with the exact same active source type and
value automatically use one loader and independently render the shared latest
frame.

## Media sources

Relative local paths are resolved inside `plugins/MTV/media/`.

```text
/mtv source main rtmp rtmp://127.0.0.1:55556/screen
/mtv source main mjpeg http://camera.local/video
/mtv source main video intro.mp4
/mtv source main image poster.png
/mtv source main url-image https://example.com/poster.png
/mtv source main gif animation.gif
```

Local videos and GIFs loop automatically. Existing `url:` config entries and
`/mtv set <name> url ...` remain supported as legacy RTMP syntax.

## Control Studio

Use `/mtv menu` to open the in-game administration interface. The dashboard
shows running screens, errors, active events, viewers and indexed media.

Control Studio includes:

- per-screen Now Playing, reason, next item, timing, health and location tools
- hold, resume, skip, repeat, return-to-automation and queue controls
- a Live Control Room for cueing media, events and emergency mode
- a watched Media Library with generated map thumbnails
- playlist probability simulation and current eligibility explanations
- event timelines with media, wait, manual, command, broadcast, sound, title
  and screen-group steps
- screen groups, recurring schedules, conflict warnings and templates
- Draft and Publish changes, config snapshots, audit history and undo
- audience voting with permission and distance checks
- aggregate play, viewer-time, skip and failure statistics

Generated studio state is stored in `plugins/MTV/studio.yml`. Cached
thumbnails are stored under `plugins/MTV/media/.thumbnails/`, and
pre-publish backups are kept in `plugins/MTV/history/`.

## Web Studio

Use `/mtv web` to create a short-lived, one-time browser login. The embedded
server listens on `127.0.0.1:8765` by default and provides dashboards, screen
details, Media Library, playlist and event editors, Preview/Program Live
Studio, schedules, groups, monitoring, diagnostics and structured drafts.

Every property and field includes contextual hover help and an accessible
description without filling the interface with permanent icons. Live state uses a
compact server-sent event stream, while preview capture is downscaled,
rate-limited and inactive when no browser is connected.

Do not expose the HTTP port directly to the internet. Remote use requires an
HTTPS reverse proxy or authenticated VPN. See the complete
[Web Studio guide](https://unknown-56-works.gitbook.io/luigiscreen/studio/web-studio).

## Playlists and events

Playlists and events are defined in `config.yml`.

```yaml
playlists:
  spawn_rotation:
    history-window: 3
    category-history-window: 1
    items:
      stream:
        type: rtmp
        value: "rtmp://127.0.0.1:55556/screen"
        weight: 10
        duration: 60s
      random_media:
        type: folder
        folder: trailers
        media-types: [video, image, gif]
        weight: 3
        duration: 20s
        cooldown: 2m
        category: trailers
        guaranteed-after: 30m

events:
  update_reveal:
    sequence:
      title:
        type: text
        text: "Update starts soon"
        duration: 5s
      trailer:
        type: video
        value: "trailers/update.mp4"
        duration: 30s
      operator:
        type: wait-manual
        text: "Waiting for operator"
        duration: 1s
```

Use them with:

```text
/mtv playlist set main spawn_rotation
/mtv event play main update_reveal
/mtv event stop main
```

When two screens select the same active source, MTV still shares one
loader. Text event steps render an internal MTV frame and then return to
the configured playlist or fallback source.

The Media Library watches local folders automatically. New, changed and removed
files are validated and reflected in the GUI without `/mtv reload`.

## Runtime performance

- MapEngine render surfaces are reused instead of reconstructed for every frame.
- Delta buffers are allocated once per screen and updated in place.
- Player locations are captured once per viewer refresh and shared by all screens.
- Screens sharing a source use one decoder or image loader.
- Static image workers sleep until stopped after their frame is loaded.
- `/mtv start all` and `/mtv stop all` save the config once per batch.
- Decoder shutdown waits longer than the configured remote I/O timeout.

## License

MTV is a modified version of LuigiScreen Free, copyright (C) 2026 unknown-566,
and is licensed under the GNU Affero General Public License v3.0 only. See
[LICENSE](LICENSE) and [NOTICE](NOTICE). Modifications for MTV are by YiitTV.

The LuigiScreen name and logo belong to their owner and are not used to brand
this build; see [TRADEMARKS.md](TRADEMARKS.md).

Third-party components and their licenses are documented in
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). Plasmo Voice is an optional
runtime dependency and is not bundled.
