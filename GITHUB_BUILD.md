# Build APK without Android Studio

This repository includes `.github/workflows/build-apk.yml`, which builds an installable **debug APK** using GitHub Actions.

## Upload to GitHub from Ubuntu

1. Extract this ZIP on Ubuntu.
2. Create a new empty GitHub repository (do not initialize with README).
3. In a terminal, run these commands from **inside** the extracted project folder:

```bash
git init
git branch -M main
git add .
git commit -m "Initial Android audio capture test"
git remote add origin https://github.com/YOUR_USERNAME/YOUR_REPOSITORY.git
git push -u origin main
```

GitHub may prompt you to authenticate. Replace the example URL with your repository address. If you already have a nonempty repository, create a fresh one to avoid conflicts.

## Download APK

1. Open the repository on GitHub.
2. Select **Actions** > **Build Android APK**.
3. Click the most recent run (wait until it completes successfully).
4. Under **Artifacts**, download `WifiAudioCapture-Phase1-debug-apk`.
5. Unzip the artifact and transfer `app-debug.apk` to your phone.
6. Install the APK. Android may ask you to allow installations from that source.

## Run the test

Play music in ReVanced, start a 10-second recording in the test app, approve Android capture permission, then play the WAV sample in the app. A system screen recorder working is not a guarantee third-party capture works.

## Notes

- This is Phase 1 only; there is **no Wi-Fi broadcast** yet.
- The APK is signed automatically with Android's debug key; intended only for personal testing.
- Requires Android 10 (API 29) or later.
- Actual APK compilation and device recording have **not** been verified in this environment.
