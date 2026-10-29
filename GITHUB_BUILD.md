# Build and download the APK

Android Studio is not required. The repository includes a pinned Gradle wrapper
and `.github/workflows/build-apk.yml`. Pushes to main/master, pull requests and
manual workflow dispatch run unit tests, Android lint and assemble an installable
debug APK with JDK 17/SDK 35.

1. Open https://github.com/sumit9235/wifi-audio-sharing/actions/workflows/build-apk.yml.
2. Select **Build Android APK → Run workflow** or open the latest successful push.
3. Under **Artifacts**, download **WifiAudioSharing-debug-apk** (14-day retention).
4. Unzip and install `app-debug.apk` on all phones. Match APKs and stream modes.
5. `WifiAudioSharing-verification` contains unit-test XML/HTML and lint reports.

Local APK path: `app/build/outputs/apk/debug/app-debug.apk`.
A clean CI runner uses a new debug signing key. If an update fails signature
validation, uninstall the previous debug build before installing the new one.
Uninstalling clears local display/volume/profile settings.

## Safely push future work

The GitHub remote is already configured. Do not reinitialize the repository.
If GitHub contains newer commits, integrate them before pushing:

```bash
git pull --no-rebase origin main
# Resolve any reported conflicts, inspect the result and commit it.
git push origin main
```

Authenticate with a GitHub credential manager, SSH key or access token. Never
paste account passwords/tokens into source files, remote URLs or chat.

## Signed release APK later

The debug APK is for testing. For stable distributed upgrades:

1. Generate a dedicated release signing key on a trusted machine. Keep encrypted
   offline backups. Never commit the keystore, passwords or key material.
2. Store a base64 keystore and its alias/passwords in protected GitHub Actions
   environment secrets, with release approvals and limited repository access.
3. Add an opt-in release job (tag/manual dispatch). Decode the keystore into a
   runner temporary directory with restrictive permissions and read signing
   credentials from environment variables in a release signingConfig. Do not
   echo them, pass them in logged command arguments or upload the keystore.
4. Run tests/lint and `:app:assembleRelease`, then verify with Android
   `apksigner verify --verbose`. Publish only the signed APK and checksum.
5. Reuse the same protected signing key for every update and increment
   versionCode. A debug-signed app must be uninstalled before switching keys.

Release signing is intentionally not configured until a private key and secure
release environment exist. No signing credentials are included in this repo.
