# Bedrock audio feasibility and design

## Decision

Full Bedrock audio is **not implemented in this branch**. The image work is
independent and complete for real-client evaluation, while audio remains an
explicitly blocked subsystem.

The current MCCinema audio pipeline produces a Java Edition resource pack and
uses Java custom sound keys. Official Geyser documentation states that Geyser
does not convert Java resource packs to Bedrock packs. Sending the existing ZIP
to a detected Bedrock viewer would therefore be a false solution. This branch
withholds that Java pack and its Java sound playback from detected Bedrock
viewers, and reports `BEDROCK_PACK_REQUIRED` /
`BEDROCK_PACK_UNAVAILABLE` in diagnostics.

The official references checked on 2026-08-06 are:

- [Using resource packs with Geyser](https://geysermc.org/wiki/geyser/packs/)
- [Geyser API pack overview](https://geysermc.org/wiki/geyser/api/)
- [Geyser API dependency setup](https://geysermc.org/wiki/geyser/getting-started-with-the-api/)
- [Geyser extensions](https://geysermc.org/wiki/geyser/extensions/)

## Current Java subsystem

`AudioExtractor` writes Vorbis audio at the configured bitrate and either one
file or time-based chunks. `AudioManager` builds a Java pack with `pack.mcmeta`,
`assets/mcc/sounds.json`, and OGG files. `ResourcePackManager` hosts the ZIP
through MCPacks or a local HTTP server. Paper sends that pack to Java viewers,
waits for terminal pack statuses, and starts one shared video clock.

Pause stops all current sound keys. Resume selects the chunk containing the
paused time, but starts that chunk from its beginning. Seek does the same at the
target time. Therefore synchronization precision is bounded by the configured
chunk duration; this is an existing Java behavior that any Bedrock design must
not make worse. Stop cancels the chunk scheduler and stops all keys.

## Required Bedrock pack contents

A technically possible Bedrock solution needs a genuine Bedrock resource pack,
including a stable manifest, Bedrock sound definitions, and the audio assets.
It must be present before the sound keys are triggered. The Java ZIP cannot be
renamed to `.mcpack`.

At the default 192 kbit/s audio bitrate, compressed audio is approximately:

- 24 kB/s;
- 1.44 MB/minute;
- 86.4 MB/hour;

ZIP metadata and manifests add little for already-compressed OGG files, so pack
size is dominated by track duration. A catalog containing many videos grows
linearly. Disk planning must include source video, extracted cache, Java pack,
Bedrock pack, and Geyser's pack cache. During conversion and ZIP creation, a
conservative operational allowance is two to three times the final audio size,
in addition to the video and FFmpeg working set. These are planning estimates,
not Bedrock protocol maxima.

## Evaluated approach A: static Bedrock resource pack

### Shape

- Preprocess approved media before players connect.
- Write all required MCCinema sound definitions and OGG chunks into a Bedrock
  pack.
- Install the pack in Geyser's `packs` directory and restart or reload Geyser.
- Keep MCCinema's Java pack for Java viewers.

### Advantages

- Uses Geyser's documented pack directory and has the smallest runtime surface.
- Pack contents are deterministic and can be validated before deployment.
- Works even when the Paper plugin has no public raw Bedrock-packet API.

### Limits

- New or changed media changes the pack and normally requires a reconnect so
  the Bedrock client receives the new version.
- A complete media catalog may become too large for practical connection time,
  mobile storage, GPORTAL disk quota, and outbound traffic.
- The Java custom-sound-to-Bedrock-name translation must be verified with the
  exact Geyser build and pack naming scheme.
- Per-video dynamic creation during `/mcc play` is incompatible with a pack
  that was already negotiated at login.

This is the safest proof-of-concept for one fixed test video, but it is not a
complete dynamic cinema workflow.

## Evaluated approach B: Geyser API pack definition

The public API exposes `GeyserDefineResourcePacksEvent` for packs offered to all
sessions and `SessionLoadResourcePacksEvent` for packs selected for one joining
session. Packs can be created from a `PackCodec`, including a path-based codec.

This makes dynamic *definition* possible, but the relevant session event is
part of connection setup. Official pack guidance states that Bedrock only adds
or removes packs on initial connection. A pack generated after a player is
fully connected cannot simply be injected into that live session. The practical
workflow is:

1. build and publish the Bedrock pack;
2. make it available to the Geyser event before the next connection;
3. reconnect or transfer the player;
4. verify the new pack version is active;
5. only then start audio.

Remote packs add Geyser's documented requirements: a direct URL, exact
`Content-Length`, `Content-Type: application/zip`, and a stable downloadable
file. Geyser downloads configured remote packs at boot. The default MCCinema
MCPacks Java upload neither converts the pack nor guarantees a Bedrock manifest.

Therefore the API events do not remove the reconnect boundary and do not by
themselves solve sound playback or synchronization.

## Evaluated approach C: companion Geyser extension

A small extension is the cleanest long-term boundary when Geyser runs where the
extension can be installed. Its responsibilities would be limited to:

- register versioned Bedrock packs during Geyser's pack-definition/session-load
  events;
- expose whether a session accepted and loaded the required catalog version;
- translate MCCinema play/stop/seek intents to verified Bedrock sound output if
  normal Java custom sound translation is insufficient;
- never decode video or duplicate `FrameProcessor`.

The Paper plugin/extension contract should be versioned and minimal:

```text
catalogVersion() -> immutable content hash/version
sessionPackStatus(playerUuid) -> MISSING | OFFERED | LOADED | FAILED
play(playerUuid, soundKey, playbackEpoch, chunkIndex)
stop(playerUuid, playbackEpoch)
```

`playbackEpoch` prevents a delayed command from an old play/seek operation from
starting after a newer one. Pause maps to `stop`; resume and seek select the
chunk for the authoritative MCCinema time and start a new epoch. Stop invalidates
the epoch and stops every active MCCinema sound key.

The transport depends on topology:

- Geyser-Spigot in the same JVM may support a directly registered service;
- proxy or standalone Geyser needs an authenticated cross-process channel;
- GPORTAL may not permit installing extensions or operating that channel.

No extension or empty service stub is added now because the public, supported
sound-emission mechanism and proxy/GPORTAL topology have not yet been proven.

## Recommended next implementation phase

1. Choose one fixed, short, redistributable test clip.
2. Generate and validate a minimal Bedrock pack offline, with deterministic
   manifest UUIDs/versioning and matching MCCinema sound keys.
3. Install it through Geyser's documented pack directory or register it before
   login with `GeyserDefineResourcePacksEvent` /
   `SessionLoadResourcePacksEvent`.
4. Reconnect a real Bedrock client and prove pack load status.
5. Verify whether current Geyser translates MCCinema's custom Java play/stop
   sound packets to those exact Bedrock sound definitions. Record exact Geyser
   and client versions.
6. If normal translation is insufficient, prototype a companion extension
   using only supported public APIs; do not bind MCCinema to Geyser internals.
7. Test play, pause, resume, forward/backward seek, stop, late join, reconnect,
   stale-epoch rejection, pack decline, and pack download failure.
8. Only after this proof select static catalog, API-defined pack, or extension
   for production implementation.

## Synchronization risks

- Chunk-granularity resume/seek can replay up to one chunk of audio unless
  chunks are shortened or offset playback is proven.
- Pack download and client application time are unrelated to the server video
  clock; playback must not start until the required pack version is confirmed.
- A reconnect changes player/session state and must not accidentally resume an
  old playback epoch.
- Mobile and console clients may load large packs slowly or reject them.
- Packet translation or custom sound naming can change between Geyser/Bedrock
  releases.
- Mixed Java/Bedrock audiences must share the same authoritative playback time
  even though their pack delivery mechanisms differ.

## GPORTAL constraints

Before implementation, verify that the selected GPORTAL product permits:

- installing the required current Geyser/Floodgate builds;
- writing to and retaining Geyser's Bedrock `packs` and `extensions` folders;
- restarting/reloading Geyser after a catalog update;
- enough disk for both Java and Bedrock packs plus media caches;
- enough outbound traffic for every Bedrock client to download the pack;
- a proxy or inter-process channel if Geyser is not in the Paper JVM;
- any extra listening port, if a local pack server or service is proposed.

MCPacks mode only proves Java-pack hosting. A managed host may block arbitrary
ports or extension installation, and those restrictions must not be inferred
from the successful Phase 0 Java test.

## Acceptance boundary

Bedrock audio remains unavailable until a real client has loaded a genuine
Bedrock pack and passed synchronized play, pause, resume, seek, stop, reconnect,
and failure tests. Until then MCCinema must continue to report image and audio
results separately.
