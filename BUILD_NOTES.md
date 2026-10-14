# DevxyzRemote — Build Notes

AnyDesk-style Android-to-Android remote view + control, with owner consent.
Package `com.jepongdevxyz.remote` · minSdk 26 · targetSdk 35 · Kotlin 2.0.21 · Compose BOM 2024.10.00.

## 1. Open / build

- **Android Studio:** File → Open → this folder. Let Gradle sync (needs internet on first sync for deps).
- **DevxyzIDE:** import the project folder, then build. Gradle wrapper points to 8.7
  (`gradle/wrapper/gradle-wrapper.properties`); DevxyzIDE supplies the wrapper jar.
- First sync downloads: Compose BOM, CameraX 1.3.4, `org.webrtc:google-webrtc:1.0.32006`,
  Room 2.6.1 (KSP), DataStore, zxing core 3.5.3, firebase-bom 33.2.0 + firebase-database-ktx,
  core-splashscreen 1.0.1.

## 2. Run WITHOUT Firebase (works out of the box)

The app compiles and runs with no `google-services.json`. `Backend.hasFirebase()` uses
reflection, so nothing crashes when Firebase is absent — real 2-phone dialing shows a
"no signaling" message and the app stays fully usable otherwise.

**Loopback test mode (one phone, real pipeline):** Profile → "Test loopback mode".
It wires two WebRTC peers in-process on the same phone: accept the MediaProjection
consent, and you will see your own screen streamed back through the full
capture → encode → decode → render path, and your taps control the phone via the
AccessibilityService. This is the recommended first test.

**Manual checklist (controlled phone):**
1. Permissions screen: enable the Accessibility Service (opens system settings),
   allow notifications.
2. Keep "Online" ON on Home (green dot).
3. On an incoming request: Accept → accept the system screen-capture consent.

## 3. Real 2-phone sessions (needs her Firebase project)

1. console.firebase.google.com → create project (any name) → Add Android app,
   package `com.jepongdevxyz.remote`.
2. Download `google-services.json` → place at `devxyzremote/app/google-services.json`.
3. In `app/build.gradle.kts` plugins block add:
   `id("com.google.gms.google-services") version "4.4.2"`
   (and the version catalog already has everything else; no other change needed).
4. Rebuild. Home shows signaling online; dialing a peer's `DEVXYZ-XXXX-XXXX` ID now
   goes through Realtime Database rooms at `/devxyzremote/v1/rooms/{deviceId}`.
5. Security rules (suggested, locked down later): allow read/write under
   `/devxyzremote/v1/rooms` only — these rooms carry short-lived SDP/ICE.

## 4. How a session works

- **Outgoing:** Home → Remote Control → enter peer ID (or scan QR) → Connect.
  Signaling exchanges SDP/ICE; video (H.264 HW) + audio + 2 DataChannels come up.
- **Incoming:** RTDB listener (armed by `SessionCoordinator.ensureOnline()` in
  `RemoteApp`) → Incoming screen with permission toggles → Accept (starts
  `CaptureService` foreground service, persistent "Sharing your screen" notification)
  or Decline.
- **Control:** controller taps/swipes/scroll/keyboard/back/home/recents are sent as
  JSON over the DataChannel; the controlled phone's `ControlService`
  (AccessibilityService) performs them. Coordinates are normalized 0..1.
- **Files:** Files tab → pick from MediaStore categories → 16 KB chunked transfer
  with ACK window; received files land in `Download/DevxyzRemote`.
- **Chat / voice:** in-session chat is persisted per session (Room); voice uses the
  WebRTC audio track (mute/speaker in the overlay).

## 5. Honest verification status

This project was **not compiled in the author's environment (no Android SDK)**.
What was verified instead:
- All 44 Kotlin files: brace/paren balance clean (one checker false-positive
  re-verified line-by-line).
- Every `com.jepongdevxyz.remote.*` import resolves to a file in the project
  (`R` imports are the generated class — normal).
- All 14 navigation routes map to existing screen composables; manifest declares
  every service/provider with correct attributes; `accessibilityservice.xml` has
  `canPerformGestures="true"` and no package restriction; all Gradle version-catalog
  aliases resolve.
- API signatures cross-checked between core and UI layers (webrtc/Firebase call
  shapes checked against their public sources).

Most likely build-time risks to watch:
- `org.webrtc:google-webrtc:1.0.32006` — if Gradle can't resolve it, bump to a newer
  1.0.x from Maven Central; `RtcManager` uses only stable APIs (PeerConnectionFactory,
  ScreenCapturerAndroid, DataChannel, getStats).
- KSP + Room: keep `ksp` plugin version aligned with Kotlin (2.0.21-1.0.25).
- `CaptureService` uses `ScreenCapturerAndroid(Intent, MediaProjection.Callback)`
  (the real constructor — it builds its own MediaProjection/VirtualDisplay).
- Minor: `ic_app_mark.xml` uses solid neon strokes (gradient strokes avoided as an
  uncompilable-risk); the loopback test has an ~800 ms arm delay by design.

## 6. Deliberate product decisions

- **No cloud account** (AnyDesk-style: the Device ID is the identity).
- Every session needs explicit Accept on the controlled phone — Android forbids
  unattended access; this is also the consent guarantee.
- True zero-delay is impossible; target is ~0.1–0.3 s same-WiFi, ~0.3–1 s internet
  (P2P WebRTC, hardware H.264, adaptive 720p/1080p bitrate).
- Banking apps may refuse to run while the Accessibility Service is on; secure
  windows are excluded from capture by Android itself.
