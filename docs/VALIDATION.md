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
