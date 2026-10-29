# Wi-Fi Music Sharing — Phase 2 (Android 10+)

A single Android app with **Host** and **Listener** modes, built on the previously tested AudioPlaybackCapture API.

## Build without Android Studio

Upload all folders (including `.github`) to the **root** of your existing GitHub repository on branch `main`. Open **Actions -> Build Android APK**, run it, and download artifact `WifiAudioSharing-Phase2-debug-apk` after a successful build. The ZIP artifact contains `app-debug.apk`.

Keep `app/`, `settings.gradle.kts`, `build.gradle.kts`, and `gradle/` in the repository root; a workflow YAML alone does not build an app.

## Test on two phones

1. Install the same Phase 2 APK on both phones. Put both on the same Wi-Fi (not a guest/isolated network).
2. On the music phone, open app, note **Host local IP**, and tap **Start live sharing**; grant Android playback capture permission. Return to ReVanced and play a song.
3. On a friend's phone, open app, enter the music phone's displayed IP, and tap **Join and listen**. Connect headphones.
4. Stop with the in-app buttons; the host also shows a foreground-service notification.

## Implementation / limitations

- Audio is 48-kHz, mono, 16-bit **raw PCM** over local UDP port 39555, 10-ms chunks (< MTU). Approximate payload bitrate: **768 kbps per listener**. No server or internet necessary, but Wi-Fi must allow peer traffic.
- The host uses AudioPlaybackCapture with explicit user consent. ReVanced must still allow capture; Phase 1 success on the same phone is encouraging.
- **Prototype**: manual IP connection, no encryption/authentication, no adaptive jitter buffer, no tight sync, no auto-discovery, and no codec compression. Use only on a Wi-Fi network you trust, and only for audio you are authorized to retransmit.
- Android may change local IP when changing networks. Battery optimization can terminate background work. Audio stream currently plays only while the listener app remains alive.
- Run two-phone testing first; report phone Android versions plus build/runtime errors if any.
