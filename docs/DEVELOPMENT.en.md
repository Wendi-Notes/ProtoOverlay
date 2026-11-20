# Developer guide

## Development environment

- Android Studio
- JDK 17
- Android SDK Platform 36 (install any additional components requested by Gradle)
- An Android 11 or later device or emulator

```powershell
git clone https://github.com/Wendi-Notes/ProtoOverlay.git
cd ProtoOverlay
.\gradlew.bat assembleDebug
```

Android Studio can open the cloned project. Do not commit `local.properties`; Android Studio generates it with the local SDK path.

## APK builds and signing

Build a debug APK for development and device testing:

```powershell
.\gradlew.bat assembleDebug
```

The debug APK is signed with Android's debug key and is not suitable for ongoing public distribution. A release build requires an explicit signing key. Release builds fail when signing secrets or the keystore are missing; they never fall back to debug signing.

### Publishing to GitHub Releases

Pushing a `v*` tag starts GitHub Actions, which builds the signed `release` variant and attaches the APK and its SHA-256 file to a GitHub Release.

1. Create a dedicated Android release keystore (`.jks`). To update an existing installation, keep using the same key as previous releases.
2. In the repository, open **Settings > Secrets and variables > Actions** and add these Repository secrets:

   | Secret | Value |
   |---|---|
   | `ANDROID_KEYSTORE_BASE64` | Base64-encoded contents of the `.jks` file |
   | `ANDROID_KEYSTORE_PASSWORD` | Keystore password |
   | `ANDROID_KEY_ALIAS` | Key alias |
   | `ANDROID_KEY_PASSWORD` | Key password |

   Generate the Base64 value locally and paste it directly into GitHub Secrets. Treat it as sensitive as the keystore itself; never put the key or passwords in chat, the repository, or logs. For example, use `base64 -w 0 release-key.jks` on Linux, `base64 release-key.jks` on macOS, or `[Convert]::ToBase64String([IO.File]::ReadAllBytes("release-key.jks"))` in PowerShell.
3. Confirm that GitHub Actions is enabled, then push a version tag:

   ```powershell
   git tag v0.2.0
   git push origin v0.2.0
   ```

4. After the workflow succeeds, check GitHub Releases for `app-release.apk` and `app-release.apk.sha256`. Verify the downloaded APK's SHA-256 before installing it. On Windows, run `Get-FileHash .\app-release.apk -Algorithm SHA256`.

Back up the release keystore in multiple secure, encrypted locations and restrict access. Losing the key may prevent updates from being installed over existing copies of the app. Never commit the keystore, passwords, or local signing configuration.

## Project structure

- `app/src/main/java/com/wendinotes/proto/`: Android application source
- `app/src/main/res/`: Android resources, icons, and mascot images
- `app/src/main/AndroidManifest.xml`: permissions, activities, and services
- `app/build.gradle.kts`: Android build configuration and dependencies

This repository is a complete Android application project, not an SDK or library to add as a dependency to another application.

## Connection protocol

The Android app sends an HTTP GET request to the configured URL with `token?character=proto` appended. The PC-side endpoint must return JSON in this shape:

```json
{
  "url": "wss://livekit.example.invalid",
  "token": "<short-lived LiveKit access token>"
}
```

Do not put real LiveKit URLs or short-lived tokens in examples, documentation, or source control. Preserve compatibility with the PC-side agent's `lk.transcription`, `lk.chat`, and `proto.image` data streams and participant-attribute status updates.

## Dependencies and licenses

Dependencies and versions are listed in `app/build.gradle.kts`. Follow each dependency's distribution terms. The Apache License 2.0 does not grant rights to third-party libraries or character assets; review their licenses and attribution requirements before redistribution.
