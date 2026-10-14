# DevxyzRemote

**Your Device. Your Control. Anywhere.**

AnyDesk-style Android-to-Android remote view + control — with the owner's consent,
built in. See the other phone's screen in real time and control it: tap, swipe,
scroll, type, file transfer, in-session chat and voice call.

- Works on all Android devices, **no root required**
- Real-time P2P streaming (WebRTC, hardware H.264, adaptive 720p/1080p)
- End-to-end encrypted (DTLS-SRTP, built into WebRTC)
- Every session needs an explicit **Accept** on the controlled phone
- Device ID is the identity — no account needed

## Screens

Splash → Welcome → Permissions → Home (Device ID `DEVXYZ-XXXX-XXXX`, QR share) →
Connect (ID / QR scan) → Incoming (permission toggles) → Remote Control /
Sharing → Files → Chat → Voice → History → Devices → Profile (Dark/Light mode,
loopback test mode).

## Build

1. Import this folder into **DevxyzIDE** (or Android Studio) and let Gradle sync
   (first sync needs internet for dependencies).
2. Build & install on **two** Android phones — or use **loopback test mode**
   (Profile → Test loopback mode) to exercise the real pipeline on one phone.

No Firebase needed to build or run. For real two-phone sessions over the
internet, add Firebase Realtime Database signaling — exact steps in
[`BUILD_NOTES.md`](BUILD_NOTES.md).

## Project

- Package `com.jepongdevxyz.remote` · Kotlin · Jetpack Compose (Material3)
- minSdk 26 · targetSdk 35
- `app/src/main/java/com/jepongdevxyz/remote/`
  - `ui/` — all screens + theme + navigation
  - `core/` — session coordinator, WebRTC protocol, device ID, prefs
  - `service/` — screen capture (MediaProjection), remote input
    (AccessibilityService), WebRTC, signaling (Firebase + loopback), file transfer
  - `data/` — Room database (sessions, messages, devices)

See [`BUILD_NOTES.md`](BUILD_NOTES.md) for the full build/test/verification notes.

Built by Spidey for JepongDevxyz.
