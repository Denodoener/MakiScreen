# Audio compatibility test matrix

This matrix covers the shared MCCinema audio catalog introduced by the Bedrock
audio fix. The Java ZIP and native Bedrock `.mcpack` are generated from the
same manifest; no file is copied into a hard-coded Geyser directory.

## Automated coverage

| Area | Expected result |
| --- | --- |
| Playback replacement | A newer screen epoch invalidates every late callback from the replaced session. |
| Callback/timeout race | Completion is idempotent and can start at most once. |
| Cache reuse | Size, timestamp, source SHA-256, cache format, chunk mode, and every OGG must match. |
| Source/cache change | Only the affected video entry is rebuilt; unrelated catalog entries remain. |
| OGG validation | Chunks are probed, durations are logged, and repeated full-source payloads are rejected. |
| Java single-file mode | `stream: true` and `preload: false`. |
| Java chunk mode | Each chunk has a unique key and file; the previous chunk is stopped only for measured overlap. |
| Radius | Leaving stops the current sound immediately; entering becomes active on the next chunk. |
| Shared packs | Java and Bedrock archives contain all catalog sounds without key collisions. |
| Failed rebuild | Previously published archives remain available. |
| Platform routing | Java receives only the Java pack; Geyser Bedrock receives only the native Bedrock pack. |

## Manual Java test

1. Join after the startup catalog reports `READY` and accept the single shared
   Java pack.
2. Run `/mcc audiopack status`; verify the catalog version, Java SHA-256, host
   state, and `READY` state.
3. Play two different videos with audio in sequence, including a quick replace
   while the first video is loading. Exactly one audio session may remain.
4. Test both `--audio single` and chunk mode. The single track must continue for
   its full duration; chunks must not repeat the complete track.
5. Walk outside `audio.radius-blocks` and verify immediate silence. Walk back in
   and verify audio begins at the next chunk boundary.
6. Change one source file and rebuild. Verify its SHA-256/cache entry changes,
   while unrelated videos remain in the catalog. Reconnect if connected-pack
   updates are disabled.

## Manual Bedrock/Geyser test

1. Start Paper with Geyser-Spigot (Floodgate optional) and verify the console
   reports the active platform APIs plus native Bedrock pack registration.
2. Run `/mcc bedrockdebug` for the real client. It must report
   `BEDROCK_VIA_GEYSER`, native Bedrock audio routing, and the catalog hashes.
3. Reconnect after a catalog version change. Geyser must attach the current
   `.mcpack` through `SessionLoadResourcePacksEvent`; no Java pack prompt may be
   sent to the Bedrock client.
4. Play two videos, replace a loading playback, and repeat the radius test. No
   duplicate or delayed stale audio session may start.
5. Run `/mcc audiopack rebuild`. If it fails, verify the previous Java and
   Bedrock archives are still usable and the failure is shown by the status
   command. Do not merge the draft PR until this matrix passes on the target
   G-Portal server.
