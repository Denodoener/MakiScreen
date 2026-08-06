# MCCinema architecture

This document records the Phase 0 baseline of MCCinema 2.3.3. It describes
the code that already exists; it is not a Phase 1 design or an implementation
commitment.

## Runtime baseline

- Server API: Paper 26.2
- Java toolchain and bytecode target: Java 25
- Build system: Gradle Wrapper 9.2.1
- Plugin entry point: `de.erethon.mccinema.MCCinema`
- Final artifact: `build/libs/MCCinema-2.3.3.jar`

The project uses paperweight userdev for Paper internals, Shadow for bundled
runtime dependencies, run-paper for local development, and plugin-yml for the
generated Bukkit plugin descriptor.

## Main components

### Plugin lifecycle

`MCCinema` owns the managers and command registration. On startup it loads the
configuration and persisted screens. On shutdown it stops active playback and
releases background resources.

### Screens and maps

The `screen` package models a cinema screen as a rectangular collection of
Minecraft maps:

- `Screen` stores identity, placement, dimensions, viewers, and tiles.
- `MapTile` stores one 128 x 128 map and tracks sent pixels and dirty regions.
- `ScreenManager` creates, loads, saves, fills, and removes screens.
- `ScreenMapRenderer` supplies the current tile image to Bukkit map rendering.
- `MapColorUtil` parses configured blank colors and maps RGB colors to the
  internal Minecraft map palette.

Screen persistence is stored below the plugin data directory. Server-side map
data and explicit map packets are both updated so newly connected or nearby
players receive a consistent image.

### Video pipeline

The video path is split into decoding, frame conversion, change detection, and
packet delivery:

1. `VideoPlayer` controls playback state and decoder timing.
2. `FrameProcessor` scales frames to the screen and converts pixels to map
   palette indices.
3. Dithering and temporal reuse reduce visible banding while limiting churn.
4. `MapTile` compares the new frame with the last transmitted frame.
5. `PacketDispatcher` sends full maps or dirty regions to current viewers.

Frame processing uses worker threads. Bukkit world, entity, scheduler, and map
state operations must remain on the primary server thread unless the Paper API
explicitly documents them as thread-safe.

### Audio pipeline

Minecraft map playback cannot carry video audio directly. MCCinema therefore
uses resource-pack sounds:

1. `AudioExtractor` reads the video audio track through JavaCV/FFmpeg.
2. Multi-channel input is reduced to stereo, or mono for positional mode.
3. Audio is encoded as Vorbis and optionally split into configured chunks.
4. `AudioManager` builds a resource pack and coordinates chunk playback.
5. `ResourcePackManager` publishes the pack through MCPacks or the optional
   local HTTP server.

Completed conversions have a cache marker. Incomplete or outdated conversion
output is cleared before a retry. FFmpeg native libraries for Windows and Linux
x86-64 are included in the shaded plugin.

### Downloads

The `download` package wraps yt-dlp setup, consent, progress, and video
conversion. Downloads are optional; administrators may place supported video
files directly in the plugin's video directory.

### Resource-pack hosting

`ResourcePackManager` selects the configured hosting mode. MCPacks avoids an
inbound server port. `ResourcePackServer` is the local-hosting alternative and
requires an externally reachable address, port forwarding, and host support
for an additional listening port.

## Data and control flow

```text
command -> Screen / VideoPlayer -> decoder -> FrameProcessor
                                      |             |
                                      |             +-> MapTile dirty regions
                                      |                         |
                                      +-> AudioExtractor        v
                                              |          PacketDispatcher
                                              v                 |
                                      resource pack             v
                                                        nearby players
```

## Concurrency rules

- Commands and Bukkit callbacks begin on the server thread.
- Video decoding, image processing, downloads, and audio conversion may run on
  worker threads.
- Screen creation, entity access, Bukkit scheduling, and persistent map writes
  return to the server thread.
- Mutable playback state must not be shared without the existing synchronization
  or atomic-state mechanisms.
- Executors and FFmpeg grabbers/recorders must be closed when work completes or
  fails.

## Build and delivery

The checked-in Wrapper is the only supported build entry point:

```text
./gradlew --no-daemon clean build --stacktrace
```

`reobfJar` writes `MCCinema-2.3.3.jar` to `build/libs/`. Jenkins and GitHub
Actions both use Java 25, invoke the Wrapper, and archive artifacts only from
`build/libs/`.

## Phase 0 boundaries

Phase 0 restores build reproducibility, missing 2.3.3 production sources,
baseline documentation, and CI. It does not add commands, playback modes,
permissions, protocol behavior, or other gameplay features.
