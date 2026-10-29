# Wi-Fi Music Sharing — Codex Implementation Handoff

**Project:** Android Wi-Fi Music Sharing  
**Handoff date:** 2026-10-29  
**Starting point:** Existing working Phase 2 Kotlin Android repository  
**Next scope:** Phases 3, 4, and 5  
**Primary constraint:** Preserve the working Phase 2 audio capture and host/listener streaming functionality.

## 1. Background and confirmed baseline

This application allows an Android **host** phone to capture audio playing in YouTube ReVanced (where Android's standard playback-capture permissions permit it) and stream audio over a local Wi-Fi network to one or more Android **listener** phones. Both phones install the same APK.

**Confirmed by real-device testing:**
- Phase 1: Capturing audio from ReVanced and saving/playback of the recorded sample worked.
- Phase 2: Host-to-friend streaming over the same Wi-Fi worked. The listener manually entered the host's local Wi-Fi IP address.
- The user reported **a slight playback delay on the friend's phone**, but otherwise functional audio.

**Do not assume:** Current transport protocol details, port, package name, audio format, existing classes, minimum Android version, or repo layout. Inspect the actual repository before designing changes. The previous prototype was described as using basic PCM streaming, but confirm this in code.

## 2. Definition of done

Deliver a single installable Android APK that runs on host and listener phones, automatically discovers host sessions over a shared local Wi-Fi, supports stable multi-listener playback, reduces and measures playback latency, works responsibly in the background, offers a usable interface, and builds on GitHub Actions without Android Studio on the user's Linux laptop.

**Non-goals:** Bypassing Android or source-app playback capture restrictions; DRM circumvention; internet-based streaming; account registration or a cloud backend; media scraping/downloading; remote streaming over public internet.

## 3. Execution rules for Codex

1. **Inspect before editing.** Map Gradle modules, app structure, AndroidManifest, MediaProjection/AudioPlaybackCapture flow, audio pipeline, networking, threading, UI, and GitHub Actions workflows.
2. **Protect Phase 2.** Commit/tag or otherwise preserve a working baseline, and keep a fallback mode such as manual host IP if discovery fails.
3. Work incrementally in **Phase 3 → Phase 4 → Phase 5**, with small coherent changes. Do not perform a large blind rewrite.
4. Use idiomatic Kotlin, structured concurrency/coroutines, properly scoped lifecycle management, bounded buffers, explicit error states, and cancellation-safe teardown.
5. Prefer Android platform APIs and focused maintained dependencies. Avoid adding cloud services or excessive libraries.
6. Keep all discovery/streaming on the local LAN and do not expose an unauthenticated service on the internet.
7. After each phase: build, run available tests, document limitations, and produce an installable debug APK via GitHub Actions.
8. Ask for manual real-phone verification where Android-specific behavior cannot be tested in CI. Never claim device behavior is verified solely because compilation succeeded.

## 4. Target architecture

```text
                        SAME WI-FI / LOCAL NETWORK

 HOST ANDROID PHONE                              LISTENER ANDROID PHONE(S)
 ┌──────────────────────────────┐               ┌──────────────────────────────┐
 │ ReVanced audio playing       │               │ Host/session discovery       │
 │      ↓                       │               │ (Android NSD / mDNS)         │
 │ MediaProjection permission   │               │      ↓                       │
 │ + AudioPlaybackCapture       │               │ Join / authorize session     │
 │      ↓                       │               │      ↓                       │
 │ Audio pipeline              │   audio       │ Network receiver             │
 │ capture → encode → packetize │ ───────────→  │ reorder / jitter buffer     │
 │      ↓                       │  local Wi-Fi  │ decode → AudioTrack          │
 │ Session service + discovery  │ ←───────────  │ stats / control channel      │
 │ Listener roster + telemetry  │  controls     │ volume / status UI           │
 └──────────────────────────────┘               └──────────────────────────────┘
```

Use an interface-based separation between audio capture, codec, transport, discovery, clock/sync, session state, and UI, but adapt to the repository instead of imposing unnecessary modules.

## 5. Phase 3 — Discovery and session UX

### 5.1 Automatic discovery

- Host advertises a discoverable local service using **Android Network Service Discovery (NSD)/mDNS** on the active Wi-Fi interface; use a service type such as `_wifiaudio._tcp.` for the control endpoint (validate naming/API usage).
- Include protocol version and non-sensitive session metadata in the service name/TXT metadata where appropriate. Do not publish user identifiers or secrets in advertisements.
- Listener presents nearby sessions with human-readable host/device name and connection state.
- User taps a session to join; **no manual IP entry in the normal flow**.
- Preserve **Join by IP** as a troubleshooting fallback.
- Handle permission requirements for each supported Android version, Wi-Fi changes, discovery lifecycle, service name collisions, loss of host, and stale entries. Reconnect or allow retry when Wi-Fi changes.
- Note that AP/client isolation, guest networks, hotspot restrictions, VPNs, and mDNS filtering may prevent discovery and/or connections. Explain this in-app.

### 5.2 Multi-listener reliability

- Allow at least **3 simultaneous listeners** initially; attempt 5 on a typical home Wi-Fi if hardware permits.
- Host shows connected listener count, optionally anonymized display names, and connection health.
- Make connect/disconnect idempotent; remove dead clients; do not let one slow client block others.
- Use bounded per-client queues. Apply explicit backpressure/drop behavior to avoid unbounded memory or host latency.
- Validate basic join requests, session IDs, and protocol versions.

### 5.3 Phase 3 acceptance tests

- On the same Wi-Fi, a listener sees the host within a reasonable discovery interval (target: **≤ 5 seconds** under normal conditions; measure it).
- Listener taps host and hears audio; no manual IP required.
- Manual-IP fallback still works.
- Three listeners can join and leave without crashing or stopping playback for others.
- Discovery failure and network changes produce actionable, understandable error messages.

## 6. Phase 4 — Audio quality, delay, synchronization, and resilience

**Observed issue:** There is a slight delay between music on the host phone and playback on the listener phone. First **measure** the existing delay, identify the dominant sources, and set a reasonable tradeoff among quality, resilience, CPU, and latency.

### 6.1 Instrumentation first

Capture and log these metrics (debug-only if sensitive):
- Audio capture frames/second, encoding time, packet size and bytes/second.
- Per-packet sequence number and monotonic sample/media timestamp.
- Send-to-receive network delay estimate where feasible.
- Decoder time, queue depth, jitter-buffer fill level, underruns/overruns, dropped and late frames.
- Estimated host-to-listener offset and playback latency with explanation of what is and is not measurable without synchronized clocks.

Separate **stream latency** (host captured sound → listener output) from **multi-listener synchronization** (different listeners playing the same audio position). These are different problems.

### 6.2 Codec and transport

- Inspect the existing Phase 2 PCM protocol. Keep it as an optional troubleshooting path until the new pipeline is validated.
- Prefer **Opus** for efficient low-latency audio if an appropriate Android-supported codec integration is practical; otherwise document the chosen codec and tradeoffs. Don't assume all devices provide a platform Opus encoder in the necessary configuration; check `MediaCodec` capabilities and/or assess a maintained native codec library.
- Start with a suitable low-latency profile (for example, 48 kHz, 20 ms audio frames, stereo if capture/source permits). Tune based on hardware tests, not guesses.
- Distinguish reliable session control (TCP/WebSocket is reasonable) from audio transport (UDP is a candidate if measured helpful); choose based on validated implementation and network conditions.
- Specify packet format with protocol version, session ID, stream ID, sequence number, media timestamp, codec ID, flags and payload size. Validate lengths and input bounds.
- Ensure codec negotiation and graceful incompatibility handling.

### 6.3 Buffering and clock synchronization

- Implement a **bounded adaptive jitter buffer** instead of uncontrolled accumulation.
- Begin with a configurable target buffer (e.g., **80–200 ms**) then tune; excessive buffering is a common source of audible delay.
- Use audio presentation timestamps, sequence ordering, jitter estimates and monotonic clocks, with explicit clock offset estimation if using cross-device timestamps.
- Use a clearly described re-sync strategy when drift grows: limited rate adjustment if practical, or controlled frame drop/insert with smoothing where appropriate.
- Maintain continuous listening when packets are briefly late. Recover from underruns without runaway delay.
- Track listener-to-listener alignment, not just transport throughput.
- Explain unavoidable acoustic latency between the host's local speaker and remote headphones; wireless/Bluetooth output adds separate device-dependent delays.

### 6.4 Realistic performance goals

These are **targets, not promises**; measure on actual phones:
- Stable audio with no regular crackling/stutter during a continuous **15-minute** test on home Wi-Fi.
- No cumulative drift over 15 minutes (latency does not keep increasing).
- Aim for **< 250 ms** capture-to-listener latency on a good network and built-in/wired audio output if feasible; report median and high-percentile results and explain if not achieved.
- Aim for listener-to-listener playback alignment of **≤ 100 ms** on tested hardware if feasible; report actual measurements.
- Support host screen lock and modest network jitter without crashes, subject to current Android foreground-service/capture restrictions.

### 6.5 Phase 4 acceptance tests

- Two and three listeners run for 15 minutes without runaway memory growth, blocked UI, or progressive delay.
- Deliberate network degradation (distance/router congestion) results in bounded buffering and understandable reconnection behavior.
- Log/diagnostic screen exposes stream mode, bitrate, queue depth, drops, underruns, and approximate latency.
- A/B comparison of Phase 2 PCM baseline versus optimized implementation shows documented improvement or documented tradeoffs.

## 7. Phase 5 — Product-ready Android experience

### 7.1 UI/screens

- **Home:** `Start Sharing` and `Join a Session` prominent actions.
- **Host:** session name, local network status, share/stop button, connected listeners, elapsed session time, simple health indicator, permission state.
- **Listener:** discovered sessions with refresh/retry, tap to join, connection status, listener-side volume, leave session.
- **Troubleshooting:** manual IP entry, local-network diagnosis, permissions and common connectivity guidance.
- **Settings:** display name, audio quality/latency mode (if supported), privacy and battery guidance.
- Optional dark mode, useful empty states, accessible labels, loading and error UI.

### 7.2 Foreground / background behavior

- Preserve the working capture experience using the Android MediaProjection consent flow and a correctly declared **mediaProjection foreground service** on supported Android versions.
- Handle mandatory foreground service types, runtime foreground-service permissions, and current Android platform restrictions as dictated by the project's `targetSdk` and Android versions.
- Host notification with **Stop sharing**; listener notification with **Disconnect**, with controls that actually work.
- Carefully test minimize, lock screen, phone calls, audio focus changes, headphone changes, Wi-Fi disconnection and OS process termination. Do not assume capture survives every app change or OS update.
- Stop audio capture and release sockets/codecs/threads cleanly. Do not automatically restart screen/audio capture without user consent if the OS requires a new projection authorization.

### 7.3 Privacy / security

- Require explicit user action and Android consent before any capture starts; visible ongoing sharing state and immediate stop control.
- Never bypass AudioPlaybackCapture policies, media DRM, or protected audio content.
- Add **private room join approval or a short pairing PIN** and validate invitations/session tokens; avoid sending an unprotected PIN as the only security control if security claims are made.
- For a shared or untrusted LAN, consider authenticated encryption of stream/control traffic as part of the shipping-quality threat model. Do **not** claim a PIN alone encrypts raw audio.
- Store as little personal data as possible; never upload audio, session history or identity data to a backend.
- Clearly state that sharing music may be subject to copyright and streaming-service terms.

### 7.4 Distribution and developer experience

- Keep **GitHub Actions** building APKs on each relevant commit / manual dispatch.
- Build an **installable debug APK** artifact on CI, and document how to create a signed release APK securely later (do not put keystore passwords in source control).
- Pin meaningful Gradle/AGP/Kotlin versions compatible with the repo, JDK and Android build requirements.
- Include clear README: phone prerequisites, Wi-Fi setup, host/listener steps, permission instructions, troubleshooting and artifact download steps.
- Add unit tests for packet encode/decode, sequence wraparound, jitter buffer bounds, client lifecycle, join protocol compatibility; instrumented tests where practical.

### 7.5 Phase 5 acceptance tests

- Install same APK on two Android phones; create/find/join/leave a session without IP entry under normal Wi-Fi conditions.
- Sharing status is unmistakable, the stop control works from app and notification, and permissions fail gracefully.
- Two phones can run with app minimized; document confirmed screen-lock behavior per tested device.
- GitHub Actions build passes, APK downloads as an artifact, and manual test checklist is completed.

## 8. Suggested development milestones / deliverables

| Milestone | What to ship | Gate |
|---|---|---|
| P3-A | Repo audit + documented current pipeline | Phase 2 understood and preserved |
| P3-B | NSD session advertisement + browse/join + manual IP fallback | 2-phone discovery passes |
| P3-C | Resilient 3-listener state management | 3-phone join/leave test passes |
| P4-A | Latency diagnostics + baseline measurement | Reproducible numbers collected |
| P4-B | Codec/packet redesign + bounded jitter buffer | 15-minute stability test passes |
| P4-C | Sync and adaptive buffering tuning | Measured latency and drift reported |
| P5-A | UI and notifications + permission flow | User experience walkthrough passes |
| P5-B | Privacy/security hardening, tests, README, GitHub APK workflow | Release candidate tested on phones |

## 9. Required final handoff from Codex

When complete, provide:
1. Summary of changes for each phase and list of relevant source files.
2. Repo architecture and brief host/listener data-flow diagram.
3. Verified build/test results and CI run status; distinguish simulated, unit-tested and real-device tested items.
4. APK artifact name/location or exact steps to download it from GitHub Actions.
5. Exact two-phone and three-phone manual test checklist.
6. Reported real-device latency/jitter/synchronization metrics and known limitations.
7. Explicit list of any features not implemented (e.g., encrypted LAN audio, private rooms, fully automatic reconnect) rather than implying they work.
8. Any required user actions or Android permissions.

## 10. First instruction to Codex (copy/paste)

> Work in the existing **Wi-Fi Music Sharing Phase 2 Android Kotlin repository**. Phase 2 has been **tested successfully on two real phones**: one host captures YouTube ReVanced audio using Android's permitted audio capture APIs, and a listener hears it over the same Wi-Fi after manually entering the host IP. There is **slight audible delay on the listener**, but playback otherwise works.
>
> Implement Phases **3, 4 and 5** according to `Wifi_Music_Sharing_Codex_Handoff.md`. Start by auditing the repository and documenting the existing flow. **Preserve working Phase 2 behavior** and keep manual-IP joining as a fallback. Do Phase 3 auto-discovery and multi-listener robustness first; Phase 4 latency measurement and audio optimization second; Phase 5 polished UI, background/notification behavior, safety, tests and CI deliverables third. Create incremental, buildable commits and never claim a real-device test passed unless it was actually performed. The user builds APKs via **GitHub Actions**, not Android Studio.
