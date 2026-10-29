# Validation record

## Phase 3

- Local `:app:testDebugUnitTest :app:assembleDebug`: PASS, Gradle 8.13,
  Android SDK 35, OpenJDK 21 compiling Java 17 bytecode.
- Peer capacity, idempotent joins/leaves and monotonic expiry: 1 JUnit test PASS.
- Installable APK preserved at `/tmp/wifi-audio-tools/phase3-debug.apk`.
- V1 UDP wire format and manual-IP flow retained. NSD advertises `_wifiaudio._udp.`
  in this milestone because the current endpoint is UDP; Phase 4 will advertise
  the new real TCP control endpoint instead.
- Three/five-phone capacity, NSD timing, Wi-Fi changes: physical tests pending.
- GitHub Actions workflow updated to run tests before publishing the debug APK.

## Phase 4

- Local `:app:testDebugUnitTest :app:assembleDebug`: PASS (7 JUnit tests).
- Packet/header bounds, sequence wrap, reorder/duplicates/loss, clock validation,
  join compatibility, registry and 90,000-frame synthetic 15-minute trace PASS.
- Synthetic test checks queue capacity and continued playout, not Android output,
  CPU, real network stability or acoustic latency.
- Installable APK: `/tmp/wifi-audio-tools/phase4-debug.apk`.
- Phase 3 GitHub Actions: PASS, run 40868436697.
- No physical Phase 4 measurements yet. Opus/stereo deferred; PCM tradeoffs and
  estimated latency semantics documented in PROTOCOL.md.

## Phase 5 (final local verification)

- `./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`:
  **PASS**, 12 JUnit tests, zero failures, zero lint errors. Remaining warnings
  concern pinned older target/dependency versions and English-only UI strings.
- Real loopback TCP/UDP integration: three concurrent receivers received valid
  PCM packets; disconnecting one preserved the remaining receivers; five-peer
  capacity and rejection of a sixth PASS; denied approval received no audio.
- Approval accept/decline/timeout/bounded pending/shutdown PASS. V1 golden wire
  packet, V2 parser bounds, wraparound, clock validation and join compatibility PASS.
- Two separate 90,000-frame simulations (15 minutes each of simulated media):
  packet loss/jitter and 100 ppm capture-clock drift PASS. These are accelerated
  algorithm tests, not 15-minute Android/network/acoustic measurements.
- Gradle wrapper generated and actual wrapper build PASS; Gradle distribution
  SHA-256 matches the official checksum and is pinned in wrapper properties.
- Android 15 / API 35 x86_64 Pixel 6 emulator: APK install, app launch, home
  layout visual inspection, RECORD_AUDIO prompt, notification prompt, and
  canceling MediaProjection consent leaving host stopped were observed PASS.
  No emulator audio-quality, active capture, lock-screen, notification-action,
  discovery timing or end-to-end playback result is claimed.
- Phase 3 GitHub Actions PASS: https://github.com/sumit9235/wifi-audio-sharing/actions/runs/40868436697
- Phase 4 GitHub Actions PASS: https://github.com/sumit9235/wifi-audio-sharing/actions/runs/40869199908
- Phase 5 GitHub Actions will run on push; use the run linked to the final Phase 5
  commit. Do not treat a previous green run as validation of this revision.
- Final local installable APK: `app/build/outputs/apk/debug/app-debug.apk`.
  Milestone copy: `/tmp/wifi-audio-tools/phase5-debug.apk`.
- Physical acceptance remains pending: see DEVICE_TESTS.md. No median/p95 latency,
  <=100 ms alignment, 15-minute acoustic stability, five-phone Wi-Fi capacity,
  vendor power-management or projection lock-screen result has been measured.
- `scripts/emulator_pcm_host.py` is an optional synthetic developer fixture,
  Python syntax checked only. It is not a tested production server or phone test.
