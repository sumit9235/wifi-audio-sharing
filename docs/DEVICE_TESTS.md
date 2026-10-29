# Physical Android acceptance record

Status: **pending device testing**. User confirmed Phase 2 capture and two-phone
streaming before this work. No new on-phone result has been supplied.

Record phone model, Android version, APK commit, router/hotspot model, output
route (speaker/wired/Bluetooth), power saver and Wi-Fi signal for every run.

## Phase 3

1. Install identical APKs on host and listener, same non-guest Wi-Fi.
2. Start host, grant RECORD_AUDIO and fresh MediaProjection consent, play permitted audio.
3. Start a stopwatch, find nearby sessions, record discovery time (target <=5 s).
4. Tap session, hear music; leave and rejoin. Try manual IPv4 when mDNS is blocked.
5. Repeat with three listeners. Remove/rejoin one while the other two keep playing.
6. Try five listeners if available; sixth must fail visibly without affecting the others.
7. Disconnect/reconnect Wi-Fi, change host network, test guest isolation and denied permissions.

## Phase 4

1. Record baseline and optimized modes separately using the same phones, song,
   Wi-Fi and wired/speaker output; do not compare Bluetooth with wired output.
2. Run two and three listeners continuously for 15 minutes per mode.
3. Every minute record target buffer, depth, drops, late packets, underruns,
   offset uncertainty, estimated pipeline age and AudioTrack underruns.
4. Record host and listener outputs with an independent recorder and repeated
   transients. Report median/p95 capture-to-output and pairwise listener offset.
   Clock/queue estimates alone do not measure acoustic latency.
5. Walk away from router/add congestion; buffering must stay bounded, recover
   without steadily increasing latency, and show disconnect/retry errors.
6. Check memory at start/end and audible clicks after frame loss/resynchronization.

## Phase 5

1. Create/discover/join/approve/leave using three phones; deny a pending request.
2. Stop host and disconnect listener from notifications and verify audio ends.
3. Minimize both apps and lock screens for 15 minutes. Record each phone's result.
4. Test notification denial, RECORD_AUDIO denial, canceled/revoked projection,
   phone calls, focus loss/gain, wired unplug and Bluetooth route changes.
5. Kill host/listener process; capture must never restart without new consent.
6. Rotate/reopen app during host/listener operation; status and controls must recover.
7. Verify no service remains, sockets are freed and another session starts after stop.
8. Download/install GitHub Actions artifact and repeat smoke test on Android 10,
   Android 14 and Android 15 if available.

No latency, discovery timing, drift, screen-lock or battery claim is accepted
until this record contains observed numbers. Targets are not measurements.

## Exact phone-count runs and measurements to fill in

| Run | Host | Listeners | Required checks |
|---|---|---|---|
| Two phones total | 1 | 1 | discovery, approval, audible audio, manual-IP fallback, notification stop |
| Three phones total | 1 | 2 | simultaneous playback, one leaves/rejoins, other uninterrupted |
| Full initial acceptance (four phones total) | 1 | 3 | three simultaneous listeners for 15 minutes, bounded memory/delay |
| Optional capacity (six phones total) | 1 | 5 | stable home-Wi-Fi playback and a sixth listener rejected |

Three listeners means four phones including the host. A three-phone test alone
does not establish three-listener capacity.

| Metric | Phase 2 compatibility | Optimized PCM | Evidence |
|---|---|---|---|
| Discovery time | N/A, manual IP | pending | stopwatch, router and phone models |
| Capture-to-output median / p95 | pending | pending | external acoustic recording, same route |
| Pairwise listener offset median / p95 | pending | pending | external recording of all outputs |
| Delay change from minute 1 to minute 15 | pending | pending | recorded transients + diagnostics |
| Drops, late packets, underruns, max queue depth | pending | pending | diagnostics each minute |
| Memory start/end and audible artifacts | pending | pending | device profiler + listening notes |
| Host/listener screen lock | baseline not supplied | pending | model, Android version, duration |

Emulator/unit/loopback results belong in VALIDATION.md, not in these physical
measurement cells.
