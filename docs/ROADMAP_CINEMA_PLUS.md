# Cinema Plus roadmap

This roadmap preserves the Phase 0 baseline and describes possible follow-up
work. Items after Phase 0 are planning only and are not implemented by this
branch.

## Guiding principles

- Keep the server authoritative and require no client mod.
- Prefer measurable playback reliability over headline resolution.
- Treat CPU time, memory pressure, disk space, and network traffic as explicit
  budgets.
- Preserve existing screens and configuration across upgrades.
- Keep managed-host limitations visible in design decisions.

## Phase 0 - reproducible baseline

Status: prepared by `codex/phase-0-baseline`.

Scope:

- Java 25 toolchain and `--release 25`;
- Paper 26.2 development bundle, run-server target, and plugin API declaration;
- Gradle Wrapper checked into version control;
- deterministic artifact path `build/libs/MCCinema-2.3.3.jar`;
- Jenkins and GitHub Actions builds using the Wrapper;
- restoration of the three production classes present in the official 2.3.3
  release but absent from the upstream source tag;
- architecture, managed-host compatibility, and roadmap documentation.

Exit criteria:

- all baseline files are versioned;
- `git diff --check` is clean;
- CI invokes `clean build` through the Wrapper on Java 25;
- no Phase 1 command or gameplay feature is included.

## Phase 1 - verification and observability

Planned topics:

- focused automated tests for map-color parsing and tile dirty regions;
- smoke tests for plugin metadata and artifact naming;
- structured diagnostics for decoder, audio conversion, and resource-pack
  failures;
- clearer startup reporting of Java, Paper, architecture, and native library
  compatibility;
- documented performance test fixtures and repeatable measurements.

This phase should establish evidence before changing playback behavior.

## Phase 2 - playback resilience

Candidate work:

- recovery from decoder stalls and malformed media;
- bounded retries and cancellation for downloads and audio conversion;
- safer cleanup of incomplete cached media;
- reconnect and late-viewer synchronization tests;
- explicit resource-pack timeout and fallback behavior;
- long-running playback and seek stress tests.

Compatibility with existing commands and saved screens is a release gate.

## Phase 3 - performance profiles

Candidate work:

- host-oriented presets for small, medium, and large screens;
- adaptive limits based on tick time and measured packet throughput;
- profiling of dithering modes and dirty-region strategies;
- configurable worker limits with safe defaults for shared hosting;
- benchmark reports covering CPU, allocation rate, and bytes per frame.

Changes must be driven by measurements from representative video types rather
than synthetic best cases alone.

## Phase 4 - operator experience

Candidate work:

- preflight checks for media, storage, and resource-pack reachability;
- actionable admin-facing error messages;
- status output for current viewers, bandwidth, audio cache, and host mode;
- backup/migration guidance for screens and configuration;
- deployment documentation for common managed-host constraints.

## Phase 5 - optional Cinema Plus features

Ideas to evaluate only after the reliability and performance gates:

- playlists and scheduled screenings;
- per-screen access policies and moderation controls;
- reusable media library metadata;
- configurable intermissions, posters, or standby frames;
- remote-control integrations with explicit authentication and permissions.

Each feature requires a separate design, threat model where relevant, and
backward-compatibility review.

## Non-goals for the baseline branch

The Phase 0 branch does not:

- add or change player commands;
- add permissions or remote APIs;
- change the packet protocol or playback algorithm;
- introduce a database;
- claim universal GPORTAL support;
- redesign resource-pack hosting;
- implement any Phase 1-5 roadmap item.

## Release gates for later phases

Every later phase should meet these gates before merge:

1. The Java and Paper baselines are explicit.
2. The Gradle Wrapper build is reproducible in CI.
3. Existing configuration has a documented migration path.
4. Failure behavior is bounded and observable.
5. Performance impact is measured at multiple screen sizes.
6. Managed-host assumptions are documented.
7. The produced plugin JAR has the expected name and checksum recorded for the
   release artifact.
