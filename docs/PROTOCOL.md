# Local streaming protocol and latency tradeoffs

## Compatibility

Phase 2 compatibility is explicit on BOTH phones. UDP 39555, V1 JOIN/LEAVE every
2 s, uint32 big-endian sequence + 960 PCM bytes. Host expires peers at 6 s, max 5.
Legacy mode is unauthenticated, unencrypted, manually joined and retains the
original playback buffer for A/B comparisons. Never expose it on an untrusted LAN.

## Version 2

NSD `_wifiaudio._tcp.` advertises TCP control 39556 with version TXT metadata.
IPv4 private/link-local peers only; no public IP destinations or cloud endpoint.
TCP is bounded to 8 concurrent handshakes, lines <=256 bytes, 6 s heartbeat
inactivity, max 5 joined peers; protocol/codec/session/UDP-port/name validation.
A new random 64-bit session ID and 32-bit stream ID are generated on host start.

Listener sends `INFO 2`, host returns `SESSION 2 <session> <stream> PCM16 48000 1`.
Listener sends `HELLO 2 <session> <udp-port> PCM16 <base64url-display-name>`.
After join validation/authorization, host replies `WELCOME 2 <session> <stream>
PCM16 48000 1`. `ERROR <reason>` ends incompatible requests. Control stays open;
`PING <listener-t1>` returns `PONG <t1> <host-t2> <host-t3>`; listener records t4.
Offset = ((t2-t1)+(t3-t4))/2, RTT=(t4-t1)-(t3-t2). Least RTT sample is preferred,
refreshed every 2 s and aged after 10 s, stale after 15 s. Offset uncertainty is
at least half RTT; asymmetry and clock drift prevent an acoustic sync guarantee.

Audio UDP is 1004 bytes, below usual 1500-byte LAN MTU. Header network order:

| Offset | Field | Bytes |
|---|---|---|
| 0 | magic WAS2 | 4 |
| 4 | protocol 2, codec PCM16=1 | 1+1 |
| 6 | flags=0 | 2 |
| 8 | session ID | 8 |
| 16 | stream ID | 4 |
| 20 | wrapping sequence | 4 |
| 24 | monotonically increasing first media sample | 8 |
| 32 | first sample host monotonic timestamp in ns | 8 |
| 40 | payload size=960, samples=480 | 2+2 |
| 44 | little-endian signed mono PCM16 | 960 |

Version, lengths, codec, flags, timestamps, source address, session and stream
are checked. IDs prevent accidental session mixing; **they are not encryption or
cryptographic packet authentication**. TCP control and UDP audio are plaintext.
The host and listener must use a trusted LAN. No port forwarding/UPnP is used.

## Chosen audio profile

48 kHz / mono / PCM16 / 10 ms. Lossless capture quality and the proven Phase 2
format are retained. Encode/decode is a PCM byte copy; no lossy codec algorithmic
delay. Payload rate = 96,000 bytes/s per listener; 5 peers ~3.84 Mbps plus headers.
Android documents Opus encoding on Android 10+, but codec capability, batching,
encoder priming and packet-loss behavior require hardware validation. This
version deliberately defers compressed Opus and stereo rather than add an
unvalidated native dependency or assume a specific MediaCodec configuration.

## Buffering and synchronization

Capture sends through independent 8-packet queues (drop oldest). Network receive
never blocks on AudioTrack. Reorder window <=24 packets (240 ms). Playout target
is 80 ms (or configurable balanced 120/resilient 160 ms), adaptive up to 180 ms
using RFC3550-style transit variation. Network timestamp offsets cancel in
successive transit differences. Missing audio becomes 10 ms silence with a short
edge ramp; this is not codec packet-loss concealment. Frames >30 ms behind the
shared host-clock playout timeline are discarded. Sequence gaps beyond the window
clear/re-anchor the buffer. Output uses the platform low-latency request and a
small effective AudioTrack buffer; support and actual size depend on hardware.

Every listener targets the same capture-time + buffer deadline, compensating for
its queued output frames. Per-device adaptive targets can differ by up to 100 ms.
Drop/insert is the drift control strategy; no playback-rate resampling is claimed.
AudioTrack timestamps are sampled every 10 s to expose presentation availability.
Approximate captured-to-enqueued-output age includes queued frames, with clock
uncertainty; it excludes speaker/DAC/Bluetooth delay. Host speaker output precedes
capture by device-specific time. Capture timestamps use AudioRecord's monotonic
frame timestamp where available, otherwise read completion minus 10 ms. Reported
numbers must not be labeled actual speaker-to-speaker latency.

Latency goals <250 ms and <=100 ms listener alignment are pending physical
measurement. Buffer tuning is not proof that these goals have been achieved.
