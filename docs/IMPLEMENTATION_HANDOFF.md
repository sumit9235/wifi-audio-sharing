# Phases 3–5 implementation handoff

## Delivered

- **Phase 3:** Android NSD Wi-Fi session advertisement/browsing, tap-to-join,
  refresh/stale removal, manual IPv4 fallback, max five listeners, heartbeat
  expiry and isolated bounded per-peer sender queues. Phase 2 baseline remains
  recoverable at commit 1322c68 and as an explicit compatibility mode.
- **Phase 4:** versioned fixed-length UDP audio packets with session/stream IDs,
  sequence/media/capture timestamps; TCP negotiation and clock exchange; bounded
  adaptive reorder/deadline buffer; drop/insert/ramped-silence recovery; small
  output buffering with startup safeguards; stream/clock/queue diagnostics.
- **Phase 5:** foreground host/listener services and notification actions,
  listener focus/route interruption handling, scoped shutdown, CPU/Wi-Fi session
  locks, Wi-Fi socket routing, explicit host approval, bounded pending requests,
  day/night interface, settings, listener volume, separate diagnostics/help,
  Gradle wrapper/checksum, test/lint/APK CI artifacts and signing documentation.

## Evidence and source map

See ARCHITECTURE.md for the audited baseline, data-flow diagram and source map.
See PROTOCOL.md for bytes, negotiation, timing assumptions and threat model.
See VALIDATION.md for per-phase local builds/tests and verified CI links. Final
local verification: 12 tests PASS, lint zero errors, debug APK built. Physical
verification is intentionally separate from unit/loopback/emulator results.

## APK

`app/build/outputs/apk/debug/app-debug.apk` locally. On GitHub Actions download
`WifiAudioSharing-debug-apk` from the latest successful Phase 5 build; it contains
`app-debug.apk` and its checksum. `WifiAudioSharing-verification` has test/lint
reports. GITHUB_BUILD.md explains installation, debug signing changes and secure
release signing later.

## Required phone actions

Install the same APK on all phones, connect trusted local Wi-Fi, grant host
RECORD_AUDIO and fresh Android projection consent, allow notifications, start
sharing, discover/tap on listeners, approve requests on the host. Use headphones.
Normal and Phase 2 modes must match on both ends. Device Wi-Fi isolation and
capture policy cannot be overridden. Follow DEVICE_TESTS.md for exact two-phone,
three-phone, three-listener (four-phone) and optional five-listener runs.

## Pending / deliberately not implemented

Real-device baseline/current latency median/p95, discovery time, inter-listener
alignment, 15-minute audible stability and lock-screen/battery results are all
pending. Algorithms alone do not prove <250 ms latency or <=100 ms alignment.

PCM remains mono/lossless at 768 kbps per listener. Opus/stereo, LAN encryption,
cryptographic packet protection/host authentication, seamless automatic
reconnect, IPv6-only networking and calibrated hardware/Bluetooth output latency
are not implemented. Host approval controls sending but does not encrypt audio.
Use only trusted LANs; legacy mode additionally has no approval. Android may end
projection on screen lock depending on version; fresh consent is required to
restart. Unusually large output startup buffers may require another output route
or compatibility mode. UI is English-only. Release signing is not configured.
