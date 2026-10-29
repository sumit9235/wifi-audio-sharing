# Wi-Fi Music Sharing — Phases 3–5

One Android APK for the music host and up to **five listeners** on a trusted
local Wi-Fi network. The tested Phase 2 playback-capture flow and manual-IP PCM
mode remain available. New phone behavior still requires the device checklist.

## Install an APK without Android Studio

1. Open [GitHub Actions](https://github.com/sumit9235/wifi-audio-sharing/actions/workflows/build-apk.yml).
2. Choose the latest successful **Build Android APK** run.
3. Download **WifiAudioSharing-debug-apk**, unzip it and install `app-debug.apk`
   on **every** phone. Android may request permission to install from that source.
4. If Android rejects an update because its signing key differs, uninstall the
   old debug APK first. Clean CI runners generate different debug keys. Use a
   persistent private release key for stable distributed updates (GITHUB_BUILD.md).

Requires Android 10+ (API 29). Compile/target SDK remains 35 to preserve the
baseline; Android 16/17 behavior and future local-network permissions are not
claimed verified. Use the same build and stream mode on all phones.

## Share and listen

1. Connect phones to the same non-guest Wi-Fi. Use headphones on listeners.
2. Host: **Share → Start Sharing**. Grant audio permission and fresh Android
   screen/playback-capture consent. On recent Android, prefer entire-screen
   sharing if single-app sharing gives silence.
3. Return to ReVanced and play audio. The source app must permit standard
   Android playback capture; this app does not bypass capture/DRM policies.
4. Listener: **Listen**, select a nearby session. On the host's **Share** screen,
   **Allow** that phone's request within 30 seconds. Up to five listeners can join.
5. Listener volume is independent of host volume. **Disconnect** or **Stop
   sharing** works from the app and ongoing notification. Opening the notification
   returns to the relevant screen. Allow notifications for visible controls.
6. Sessions run in foreground services when minimized. Calls/audio-focus loss or
   headphone unplug disconnect listening; join again when ready. Network loss
   ends the session; reconnect and start/join again. Host capture never silently
   restarts after Android revokes it.

NSD/mDNS discovery uses the Wi-Fi network where available; normal browsing needs
no IP entry. Discovery may fail on isolated/guest Wi-Fi, VPNs, mDNS filters and
some hotspots. Use **Listen → Join by IP**, entering the host's displayed private
IPv4. No account, backend or internet audio service is involved.

## Settings, quality and diagnostics

- Display/room name is shared only with LAN peers. Settings store name, latency
  profile, volume and compatibility preference; no captured audio or session
  history is stored.
- **Low delay (80 ms)**, **Balanced (120 ms)** or **Resilient (160 ms)** sets the
  listener's initial buffer target; it adapts up to 180 ms. Try a higher setting
  if weak Wi-Fi crackles. A smaller buffer trades resilience for latency.
- Host shows listener count/roster, elapsed time, capture samples/s, packetization
  time, payload bytes/s and sender drops. Listener shows reorder depth, jitter,
  late/dropped/missing packets, output underruns, resyncs, clock uncertainty,
  capture-to-receive age and estimated pipeline age.
- Audio remains **48 kHz mono PCM16, 10 ms frames**: 768 kbps payload per listener,
  ~3.84 Mbps for five. Optimized mode uses small bounded queues, timestamped UDP
  audio and TCP control/clock exchange. The full [protocol and buffering strategy](docs/PROTOCOL.md)
  describe clock error and resynchronization.
- Estimates **do not measure actual speaker-to-speaker delay**. Bluetooth/DAC,
  output routing and host local-speaker timing add separate delay. The <250 ms
  latency / <=100 ms listener-alignment goals require physical measurement.

## Phase 2 fallback / A–B comparison

Enable **Settings → Phase 2 compatibility** on both phones **before** starting
sessions. This preserves the original V1 UDP PCM/manual-IP wire protocol and
original listener buffering, now hosted by the foreground listener service.
Discovery and host approval are unavailable in this mode. Disable it on both
phones to return to optimized sessions. It is never selected silently.

## Privacy and remaining limitations

Normal joins require explicit host approval, but **TCP control and UDP audio
are unencrypted and audio packets are not cryptographically authenticated**.
Session IDs prevent accidental mixing; they do not provide confidentiality.
Use only a trusted LAN. Legacy mode also lacks join authorization. No port
forwarding/UPnP is used and public-IP peers/destinations are rejected.

Only share audio you have permission to distribute; streaming services may impose
additional terms. Recordings/media are never uploaded or saved by this app.

Opus compression, stereo, encrypted rooms, authenticated packet protection,
seamless automatic reconnect, IPv6-only networks and guaranteed sample-accurate
synchronization are not implemented. Output-route latency is not calibrated.
Vendor battery policies and current Android projection rules may stop sharing
when minimized/locked; verify each phone. CPU wake lock is bounded to six hours.
The interface is English-only. This is a debug/device-test build, not a signed
production release.

## Build and validate

Pinned Gradle 8.13, AGP 8.13.2, Kotlin 2.2.21, JDK 17, SDK 35.
With Android command-line SDK installed (Android Studio is optional):

```bash
export ANDROID_HOME=/path/to/android-sdk
./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`.
CI runs tests, lint and build for pushes to main/master, PRs and manual dispatch.

- [Architecture and baseline](docs/ARCHITECTURE.md)
- [Protocol, clocks and jitter buffering](docs/PROTOCOL.md)
- [Exact two/three-phone and 15-minute test checklist](docs/DEVICE_TESTS.md)
- [Verified tests and CI results](docs/VALIDATION.md)
- [APK downloads and secure release signing](GITHUB_BUILD.md)
