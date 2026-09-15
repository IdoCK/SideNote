# SideNote 1.0.7

September 10, 2026 · version code 8

## Changes

- Replaced the symmetric speech waveform with an organic liquid blob. Three moving lobes blend into one continuous surface. Quiet listening gently breathes; microphone levels produce stronger deformation. The touch target stays fixed, and reduced motion remains supported.
- Separated microphone startup from the lock used by taps, typing and completion. A slow support lookup can no longer delay a stop tap, screen-off or face-down completion. Successful support is reused between utterances. Silence and completed utterances restart listening; temporary provider, audio and network errors reconnect with bounded backoff instead of silently disabling voice after two failures. The status distinguishes Starting, Listening, Processing and Reconnecting.
- Prevented selection-only text-field callbacks from accidentally stopping a newly activated microphone. Focus-driven pause is idempotent. Repeated lifecycle starts cannot strand a pending support check. Stale recognition callbacks remain excluded from the current draft.
- Kept the textbox size and its surrounding control slots stable. Android's IME insets now drive its movement directly. Capture explicitly uses `adjustResize`, preventing window panning from applying a second upward movement. The first transcript no longer shifts the editor.

Typing still pauses voice to protect manual edits; Back to speech resumes it. Blob-off pauses recognition without saving. Screen-off and stable face-down completion retain the existing save-once behavior. Permission or unsupported-model failures leave typing available. Existing disclosure and online-fallback settings remain in force.

## Verification

- 205 JVM tests passed, including regression cases first observed failing for blocked stop taps, interrupted startup, temporary failures, accidental selection-triggered stops and repeated starts during support lookup.
- 31 capture UI and lifecycle tests passed on the Pixel 8 API 37 emulator.
- The animated-rendering test verifies changing blob pixels with a stable tap target.
- The keyboard regression measures physical screen coordinates: unwanted window pan changed from -594 px to 0 px, with the textbox bottom 72 dp above the IME. Its height remains unchanged. Screenshots were visually inspected.
- Release build and release lint passed with 0 errors, 17 warnings and 1 hint. The warnings concern dependency versions and existing style/resource recommendations.
- APK signature, alignment, package name and version were verified. The signing certificate matches SideNote 1.0.6, allowing an update in place.

The first emulator run encountered a System UI ANR dialog that blocked window focus. It was cleared before successful verification. Animation-test clock setup was corrected before validating the rendered frames.

No physical phone was connected during this task. Actual microphone-provider behavior, long dictation and physical flip detection still need confirmation on the user's Pixel. Emulator and simulated recognizer tests do not establish those physical-device results.

## Install

Open `SideNote-1.0.7.apk` on the phone and install it as an update. It uses the existing release key; uninstalling the previous release is unnecessary.

SHA-256: `D123373333BC8817FCC5A0A73316401B9744EEF10FC4784118B548B3381AB2A7`

The keyboard handling follows Android's [Compose inset guidance](https://developer.android.com/develop/ui/compose/system/insets-ui), which provides native synchronization with the IME animation.
