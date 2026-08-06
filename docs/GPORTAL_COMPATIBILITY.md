# GPORTAL compatibility baseline

This document separates what MCCinema requires from what must be verified in a
specific GPORTAL product and tariff. Hosting capabilities can change, so the
GPORTAL control panel and support information remain authoritative.

## Required runtime

MCCinema 2.3.3 in this Phase 0 baseline requires:

- Paper 26.2, not Spigot or a Paper-compatible fork with an older API baseline;
- Java 25 for the server process;
- an x86-64 Windows or Linux runtime for the bundled FFmpeg natives;
- write access below `plugins/MCCinema/`;
- enough CPU and outbound bandwidth for the selected screen size and content.

The built plugin is `build/libs/MCCinema-2.3.3.jar`.

## Control-panel checks

Before deployment, confirm all of the following in the GPORTAL panel:

1. Java 25 can be selected for the Minecraft server.
2. Paper 26.2 is offered, or a custom Paper 26.2 server JAR is permitted.
3. The server has enough free storage for videos, extracted audio, cached
   chunks, and generated resource packs.
4. The selected plan permits the required outbound traffic.
5. File access is available for uploading the plugin and video files.

If Java 25 or Paper 26.2 is unavailable, this baseline must not be represented
as compatible. Do not silently downgrade Java, Paper, or the compiled bytecode
target.

## Resource packs and networking

The default MCPacks mode only needs outbound HTTPS from the game server and is
the preferred option on managed hosting.

The `LOCAL` mode starts an additional HTTP listener. It works only when the
provider allows the configured port, routes it to the Minecraft instance, and
players can reach the configured public address. Many managed hosts do not
expose arbitrary extra ports, so local hosting must be tested rather than
assumed.

The generated pack must also remain reachable for every player for the full
playback session. A private address such as `localhost`, `127.0.0.1`, or a LAN
address is not valid for remote players.

## Downloads and external services

The optional download feature may require:

- outbound HTTPS access to the video provider and yt-dlp release source;
- permission to execute the downloaded yt-dlp binary;
- a supported JavaScript runtime when required by the provider;
- sufficient temporary storage for source and converted video files.

If a managed host blocks executable downloads or process creation, upload
videos manually instead. This does not affect local-file playback.

## CPU, memory, and bandwidth

MCCinema is CPU- and network-intensive. Load scales mainly with:

- the number of maps in the screen;
- source resolution and frame rate;
- motion and color complexity;
- simultaneous viewers;
- dithering and compression settings;
- audio conversion concurrency.

Start with a modest screen, use the balanced quality profile, and observe the
plugin debug metrics before increasing resolution. Static content is much
cheaper than fast, full-color footage.

Audio conversion and video processing use worker threads. A plan with several
fast cores is preferable to one that advertises only a large memory allowance.

## Deployment checklist

1. Build with the checked-in Gradle Wrapper and Java 25.
2. Verify the artifact name is `MCCinema-2.3.3.jar`.
3. Stop the server before replacing an existing plugin JAR.
4. Back up `plugins/MCCinema/`, especially configuration and `screens.yml`.
5. Upload the JAR to the server's `plugins` directory.
6. Start Paper 26.2 with Java 25 and inspect the complete startup log.
7. Create a small test screen and play a local video without audio.
8. Test audio with MCPacks mode.
9. Test downloads only if the host permits the required executable/network
   behavior.
10. Monitor tick time, CPU, disk use, and traffic under realistic viewer load.

## Expected failure modes

- `UnsupportedClassVersionError`: the server is not running Java 25.
- Paper API or remapping errors: the server is not on the supported Paper 26.2
  baseline.
- Missing FFmpeg native library: the host architecture is unsupported or the
  shaded JAR is incomplete.
- Resource pack download failure: the URL is unreachable, expired, or blocked.
- Local pack server unreachable: the extra port is not routed publicly.
- Download/convert failure: outbound access, process execution, runtime, or disk
  space is restricted.

## Support boundary

Phase 0 documents compatibility requirements but does not claim certification
for every GPORTAL plan. A successful local build and a controlled deployment
test on the actual server are both required before production use.
