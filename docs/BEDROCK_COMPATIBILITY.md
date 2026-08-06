# Bedrock compatibility workbench

## Status and claim boundary

This branch prepares MCCinema's image path for controlled testing through
Geyser and Floodgate. It does **not** claim that MCCinema works correctly on a
real Bedrock client. A real-client run on the target GPORTAL installation is
required before making that claim.

The Java path remains the production baseline. Geyser and Floodgate are
optional compile-time and runtime integrations. MCCinema still starts without
either plugin.

Research was checked against the official Geyser documentation and current
Geyser source on 2026-08-06:

- [Geyser API](https://geysermc.org/wiki/geyser/api/)
- [Floodgate API](https://geysermc.org/wiki/floodgate/api/)
- [API dependency setup](https://geysermc.org/wiki/geyser/getting-started-with-the-api/)
- [Geyser resource packs](https://geysermc.org/wiki/geyser/packs/)
- [supported protocol versions](https://geysermc.org/wiki/geyser/supported-versions/)
- [Geyser's Java map-packet translator](https://github.com/GeyserMC/Geyser/blob/master/core/src/main/java/org/geysermc/geyser/translator/protocol/java/level/JavaMapItemDataTranslator.java)

## Existing MCCinema architecture

### Image generation and packet delivery

1. `VideoPlayer` decodes a source frame with FFmpeg/JavaCV.
2. `FrameProcessor` scales it to the screen resolution, dithers it to the
   Minecraft map palette, divides it into 128 x 128 tiles, and calculates dirty
   regions.
3. `PacketDispatcher` converts those regions to
   `ClientboundMapItemDataPacket` objects. A packet includes the map ID, patch
   offset, dimensions, and palette bytes.
4. The packet list is built once per video frame. Delivery is nevertheless
   individual: MCCinema calls the connection of each viewer separately and
   sends either a bundle or the map packets.

Geyser's current `JavaMapItemDataTranslator` explicitly converts Java map IDs,
patch offsets, dimensions, and Java map colors into a Bedrock
`ClientboundMapItemDataPacket`. This is sufficient evidence to retain the
existing renderer as the first test path. It is not evidence that rapid,
multi-tile MCCinema updates are smooth or ordered correctly on every Bedrock
client.

No second decoder, palette converter, or `FrameProcessor` was added. Detected
Bedrock viewers receive the same Java map updates through Geyser's official
translator.

### Viewer discovery and routing

`Screen` refreshes a main-thread viewer cache from players within 32 blocks of
the screen origin. `VideoPlayer` either uses that cache or intersects playback
with the UUIDs explicitly selected in `/mcc play`.

The new routing decision is per viewer:

- `JAVA`: existing map, Java resource-pack, and Java sound paths;
- `BEDROCK_VIA_GEYSER`: existing map packets through Geyser, with Bedrock image
  limits; no Java audio pack;
- `UNKNOWN`: conservative Java behavior so an optional API failure cannot
  break ordinary Java playback.

The detector uses `GeyserApi#isBedrockPlayer(UUID)` and
`FloodgateApi#isFloodgatePlayer(UUID)`. It never uses a player-name prefix.
With neither optional plugin installed, Paper players are classified as
`JAVA`. If an installed API cannot answer and no other API gives a definitive
result, the platform is `UNKNOWN`.

The declared soft dependencies are `Geyser-Spigot` and `floodgate`. Current
compile-only API baselines follow the official setup page:

- Geyser API `2.10.0-SNAPSHOT`;
- Floodgate API `2.2.5-SNAPSHOT`.

For a proxy deployment, the backend must expose the relevant API. Floodgate's
official setup requires `send-floodgate-data: true` and matching `key.pem`
files when its API is used on a backend server. A proxy-only Geyser install
without a backend API cannot be detected by this Paper plugin and will use the
Java fallback classification.

### Playback synchronization

`VideoPlayer` owns one playback clock for all viewers. Play starts the frame
scheduler and audio manager. Pause stops frame scheduling and records the
current frame; resume reconstructs the playback start time and continues from
that frame. Seek stops audio, seeks the decoder, resets the frame clock, and
then restarts audio at the requested time. Stop cancels the scheduler, audio,
and viewer cache updater. There is no independent Bedrock playback clock.

Late joiners receive the last complete tile data after 20, 60, and 120 ticks.
Leaving clears the viewer's limiter and diagnostic state. Rejoining therefore
starts with a new full-frame resynchronization opportunity.

## Bedrock image safety limits

The limits apply only to `BEDROCK_VIA_GEYSER` viewers:

```yaml
bedrock:
  image:
    max-fps: 10.0
    max-map-width: 8
    max-map-height: 5
    max-bytes-per-second: 4194304
```

The byte limit counts map pixel payload per viewer, not TCP/UDP, Geyser, RakNet,
or encryption overhead. A screen over either dimension is withheld from that
Bedrock viewer. FPS or byte overload drops the current update. Because normal
map updates are patches against earlier frames, the next permitted delivery is
a full latest-frame resync; otherwise a throttled viewer could permanently
miss intermediate pixels.

The latest tile buffers are kept by the existing renderer. The fallback does
not queue obsolete video frames and does not create another rendering path.
If a full screen is larger than `max-bytes-per-second`, resynchronization cannot
succeed until the limit or screen size is changed. `/mcc reload` reloads these
limits and clears limiter state.

Java and `UNKNOWN` viewers bypass all Bedrock image limits.

## Diagnostics

Administrators with `mccinema.bedrockdebug` can run:

```text
/mcc bedrockdebug <player>
```

The report contains the detected platform, active integration APIs, selected
image path, last active screen, sent/dropped/failed frame counts, packet and
payload counts, last frame issue, audio mode, Java resource-pack status, and
current Bedrock limits. Resource-pack status is updated from Paper's
`PlayerResourcePackStatusEvent`.

## Automated test boundary

Unit tests cover:

- Java fallback with no optional APIs;
- simulated optional Geyser and Floodgate answers and API failure isolation;
- viewer image/audio routing;
- FPS, map-dimension, and per-viewer byte limits;
- full latest-frame recovery after a dropped patch;
- invalid limiter configuration.

These tests do not run a Bedrock protocol stack, render item frames in a real
client, or measure Geyser/RakNet throughput.

## Required real-client test matrix

Use a small test video with obvious numbered tiles, a moving diagonal line,
and a visible timecode. Keep a Java client connected beside the Bedrock client.

1. Install the tested Geyser-Spigot and Floodgate builds on Paper 26.2/Java 25.
2. Start with a 2 x 2 screen and default Bedrock limits.
3. Join with Java and confirm existing `/mcc help`, screen creation, playback,
   audio, pause, resume, seek, and stop still behave as before.
4. Join through Geyser with a real Bedrock client and run
   `/mcc bedrockdebug <player>`. Require `BEDROCK_VIA_GEYSER`.
5. Start image-only playback and verify that all four numbered tiles are in the
   correct position, not rotated, mirrored, or swapped.
6. Verify continuous motion for at least five minutes while watching dropped
   and failed frame counters.
7. Pause for ten seconds, resume, seek forward, seek backward, and stop. Confirm
   the displayed image changes only when expected.
8. Start playback before the Bedrock player joins. Verify the delayed
   last-frame resync, leave, rejoin, and verify it again.
9. Repeat at 8 x 5. Then configure a deliberately small byte/FPS limit and
   verify that dropped frames are followed by a correct full latest frame.
10. Exceed `max-map-width` or `max-map-height` and verify a
    `DROP_SCREEN_SIZE_LIMIT` diagnostic rather than uncontrolled traffic.
11. Re-run the same sequence with a Java-only server where Geyser and Floodgate
    are absent.

Record exact Paper, Geyser, Floodgate, Bedrock client, device, and network
versions; server logs; `/mcc bedrockdebug` output; screen dimensions; video FPS;
and observed bandwidth. Only after this matrix passes may project documentation
state that the tested combination works.

## Known risks

- Geyser's map translator proves packet support, not high-rate cinema behavior.
- Java bundle handling, map-update ordering, Bedrock client frame caching, and
  device performance need real-client validation.
- The byte counter excludes protocol overhead and should be set below the real
  network ceiling.
- Full resyncs are expensive: 16,384 bytes per map before protocol overhead.
- A Geyser or Bedrock update may change performance without changing the public
  API contract.
- Proxy-only deployments may not expose Geyser/Floodgate APIs to Paper.
- Managed GPORTAL plans may restrict plugin versions, proxy topology, file
  access, CPU, or outbound traffic. Those capabilities must be checked in the
  actual product panel.
- Bedrock audio is not implemented in this phase; see
  `BEDROCK_AUDIO_DESIGN.md`.
