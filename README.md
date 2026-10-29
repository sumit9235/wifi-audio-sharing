# Wi-Fi Music Sharing - Phase 1: Android internal-audio capture test

This is a **source project**, not a built APK. It tests whether ordinary Android APIs can capture YouTube ReVanced playback, without using the microphone. **No Wi-Fi streaming has been implemented yet.**

## Requirements
- Android 10 (API 29) or later phone
- Android Studio with Android SDK 35 and JDK 17
- Gradle 8.13, Android Gradle Plugin 8.13.2, Kotlin 2.2.21

## Build and run
1. Open the `WifiAudioCapturePhase1` folder in Android Studio.
2. Let Android Studio sync Gradle and install SDK dependencies as needed.
3. Select a physical Android device, enable USB debugging, and run `app`.
4. Play music using ReVanced; return to this app and press **Record 10 seconds**.
5. Grant `RECORD_AUDIO` (required by Android even though this app does **not** record the microphone) and approve the Android screen capture prompt.
6. While recording, return to ReVanced and keep the song playing.
7. Return to the capture app; press **Listen to saved sample**. If the result says silent, ReVanced may not permit third-party playback capture. System screen recording working does not guarantee this test will work.

## File output
App-specific external music directory: `Android/data/com.example.wifiaudiocapture/files/Music/revanced-capture.wav` (modern Android file managers may hide this path). `Listen` plays it in-app.

## Architecture
MainActivity -> MediaProjection consent -> mediaProjection foreground service -> AudioPlaybackCaptureConfiguration -> AudioRecord (48 kHz mono PCM16) -> 10-second WAV + RMS level.

## Security and limitations
- On Android, capturing media depends on the *source application's* capture policy. This sample never bypasses such a policy.
- Consent is needed for every new MediaProjection session on recent Android versions.
- Captures all eligible media/game playback in the profile during the 10-second interval, not exclusively ReVanced; close other audio apps for a clean test.
- Recording remains on-device in an app-specific directory. No upload or networking.
- One active capture at a time. Designed as a feasibility test, not production code.
- Recording media you do not own or have rights to redistribute may be restricted by copyright and service terms.

## Next milestone
Only after you verify audible playback in this APK, we can implement LAN discovery, Opus encoding, UDP audio sender/receiver, buffering, and synchronization.
