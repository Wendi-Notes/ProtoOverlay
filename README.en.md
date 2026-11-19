# ProtoOverlay

ProtoOverlay is an Android companion app that places the animated mascot “Proto” over other apps and connects it to a PC-side AI agent through LiveKit.

> **In development:** This repository contains the Android app only. It does not include the PC agent, token endpoint, or LiveKit server. Those components must be set up separately.

![ProtoOverlay dashboard on a connected Android device](docs/images/settings-screen.png)

## Features

- Persistent animated mascot overlay
- LiveKit voice calls, connection state, and transcription display
- Share images and links from Android to the PC-side agent
- Optional camera and screen sharing, subject to Android permissions and server support
- Appearance, idle, mute, and pause settings

## Components and networking

The Android app requests a token from the configured PC-side service at `/token?character=proto`. The endpoint must return JSON containing `url` and `token`. The app then connects to the LiveKit room at that URL. The PC-side agent and server are not part of this repository.

Tailscale can provide private reachability between the phone and PC. The app does not directly control Tailscale or embed its SDK. LiveKit media connectivity, TLS, firewall rules, UDP, and TURN configuration depend on the server deployment; not all media traffic automatically travels through Tailscale.

See the [user setup guide](docs/SETUP.md) before installing.

## Install

Download an APK from [GitHub Releases](https://github.com/Wendi-Notes/ProtoOverlay/releases) when one is available. If no release is listed, a distributable APK has not been published yet.

Requires Android 11 (API 30) or later. Overlay, microphone, and notification permissions are needed; camera and screen capture are optional.

## Build and modify

This is a complete Android application project, not a standalone Android SDK/library.

Requirements: Android Studio, Android SDK Platform 36, and JDK 17.

```powershell
git clone https://github.com/Wendi-Notes/ProtoOverlay.git
cd ProtoOverlay
.\gradlew.bat assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. See the [developer guide](docs/DEVELOPMENT.md) for signing and release-key handling. Never commit a private signing key or its passwords.

## License

The original project is licensed under the [Apache License 2.0](LICENSE). Third-party components and assets may have separate terms; see [NOTICE](NOTICE).
