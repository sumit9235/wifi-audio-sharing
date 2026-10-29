# Architecture and baseline

The real-device-tested Phase 2 baseline is commit `1322c68`. An exact archive is
preserved outside the source tree at `/tmp/wifi-audio-tools/phase2-baseline.tar`.
Use the Git commit for durable recovery; the archive is temporary.

## Audited Phase 2 pipeline

Single `app` module, Android 10–15 (min 29, target/compile 35), Kotlin 2.2.21,
AGP 8.13.2, Gradle 8.13, Java bytecode 17. Host requests RECORD_AUDIO then
MediaProjection consent. CaptureService starts a mediaProjection foreground
service and uses AudioPlaybackCaptureConfiguration for MEDIA/GAME/UNKNOWN.
AudioRecord reads 48 kHz mono PCM16, 480 samples per packet. UDP port 39555
accepts JOIN/LEAVE_WIFI_AUDIO_V1 heartbeats. Datagrams carry a big-endian uint32
sequence followed by 960 little-endian PCM bytes. Host expires peers after 6 s.
AudioListener plays directly into AudioTrack with at least 19,200 bytes (~200 ms)
of capacity. Network receive and blocking playback share a thread; kernel and
AudioTrack queues can accumulate delay. Listener dies with MainActivity.
There is no baseline latency recording; the only measured evidence supplied is
the user's successful two-phone test and audible slight listener delay.

## Phase gates

For each phase run unit tests and assembleDebug before the next phase. Preserve
the exact Phase 2 wire mode as an explicit fallback. Physical discovery timing,
speaker latency, lock-screen behavior and 15-minute stability cannot be proven
with a desktop build; record these as pending rather than fabricate results.
