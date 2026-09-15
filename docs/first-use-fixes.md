# First-use fixes — 2026-09-05

Reported device: Pixel 8, Android 17. Testing here used the API 37 Pixel emulator; the user deferred USB device testing.

## Storage

The app already requests persistent read/write access through the folder picker. Android does not require a separate storage runtime permission for this flow ([documentation](https://developer.android.com/training/data-storage/shared/documents-files)).

The actual save defect was the staging file's `text/plain` MIME type. Android's external-storage provider appends `.txt` to names without a matching extension ([FileUtils](https://android.googlesource.com/platform/frameworks/base/+/master/core/java/android/os/FileUtils.java)). The subsequent exact-name lookup failed before writing. Internal stages now use `application/octet-stream`, preserving their names before the verified rename to the final `.md` file. The provider fixture now reproduces MIME extension normalization; the exact UTF-8 round-trip test failed before the fix and passes afterward. Existing recovery and conflict protections remain in use.

## Voice

`ERROR_CANNOT_CHECK_SUPPORT` means a service does not implement the optional query, not that it cannot recognize speech ([RecognitionService](https://developer.android.com/reference/android/speech/RecognitionService)). SideNote previously converted that result into typed-only mode. It now attempts recognition on the available path, with online selection still consent-gated and actual recognition errors still handled normally. Explicit model-download requests can also proceed when that optional query is unavailable. The UI no longer presents probe readiness as proof of bilingual accuracy.

Regression coverage includes a local service without the query returning text, the consented default service returning mixed text, and permission-query failure still blocking recognition. Real Pixel microphone/provider behavior and the original on-device error are not yet available; this is a verified compatibility fix, not certification that the user's speech problem is fully resolved.

## Layout

MainActivity applied safe-area padding outside the dark Surface, exposing the light window behind transparent system bars. The background now paints before inset padding. A Settings screenshot test checks the status/navigation backgrounds. Scrollable content stays within safe drawing bounds.

## Quick Tap: still open

Capture already sets `showWhenLocked` and `turnScreenOn`; it requests keyguard dismissal only for unfinished onboarding or Review. The existing locked-capture test passes and excludes historical notes/settings. It does not exercise physical Pixel Quick Tap. No speculative keyguard or gesture change was made. Next step: observe ActivityTaskManager/SideNote activity launches while physically tapping the locked Pixel, then distinguish a system launch gate from incorrect activity routing. The user chose emulator-only testing for now.

## Validation and release

- 193 JVM tests passed.
- 26 focused API 37 emulator cases passed: storage, speech intents, and capture-to-review.
- 2 Settings/navigation tests passed; a direct rerun also passed Settings rendering and locked-capture semantics.
- Debug lint: 0 errors, 15 warnings. Release build passed.
- Signed update: `releases/SideNote-1.0.1.apk`, version code 2, target API 37. Signature and alignment verified; certificate matches version 1.0.
- SHA-256: `6EF15835C69A76277C4FEF44D3B6BA92078684BAB48AEE5236BD8FCBD8A0EC55`.

The complete emulator suite was not rerun. Hardware Quick Tap and real audio recognition remain physical acceptance gaps.

## Physical follow-up

Pixel 8 connected over ADB, Android 17 build CP2A.260805.005, SideNote 1.0.2 installed. Microphone and notification permissions granted. Quick Tap targets CaptureActivity. User reports fingerprint authentication on Quick Tap; inspection showed keyguard visible and no Capture activity. A direct explicit launch brought CaptureActivity to the foreground with keyguard still showing and occluded=true; user confirmed seeing Capture without unlocking. This isolates the remaining failure to the gesture launch path; direct ADB launch is not physical Quick Tap acceptance. Speech provider is GoogleTTSRecognitionService; live voice test is next.


### Save-and-close follow-up (1.0.3)

The user confirmed real voice transcription while Capture was above the lock screen, and that the test note reached Review. They then reported Capture remaining open after screen-off and a duplicate when going Home. A regression reproduced a race: onProjectSuggestions(emptyList()) during a suspended append incremented transitionVersion, so the successful write was never marked Saved and the closer was skipped. Project suggestions now update auxiliary state without invalidating the committed draft revision. The regression checks Saved, one close, and exactly one append even after a later Backgrounded signal. Unit suite: 194 passed. Release build/lint passed. Physical retest pending installation of 1.0.3.


### Launch routing follow-up (1.0.4)

User requires ordinary launches to open Review and every delivered back-tap launch to open Capture. MainActivity now owns MAIN/LAUNCHER and static shortcut metadata. Capture has a dedicated singleTask task affinity, excluded from recents, and a Capture shortcut; the Review shortcut targets Main directly. New Main intents reset navigation to Review. Repeated Capture intents preserve the current draft instead of completing it; screen-off/background/face-down saving remains.

The connected Pixel does not deliver its Quick Tap action with the screen off. User explicitly rejected an always-running detector because background use should remain minimal. The provisional detector, tests, foreground service and special permissions were removed before release. No new background sensor service ships. Screen-off back-tap capture and secure-keyguard Quick Tap remain unresolved platform-route limitations.

Regression tests first failed for launcher destination, Settings relaunch, and repeated Capture saving. Final: 194 unit tests and 16 focused emulator tests pass; release build/lint, signature and alignment checks pass. Earlier broader 39-test run passed 37; remaining failures were test synchronization (notification intents not yet delivered) and ActivityScenario intent filter/flags; both corrected and verified in the final focused run.

Signed releases/SideNote-1.0.4.apk (code5), SHA256 EEC7E91FBF2FB5537F41F82DA1A7F572B58913D8D2A2F940F6D3F3B719D8FD49. Installed in place on Pixel 8. PackageManager resolves launcher to MainActivity; SystemUI reports MainActivity shortcut capture as its Quick Tap action. Physical double-tap acceptance after shortcut remapping remains to be checked. Existing note data was retained.

### Capture and Review refinement (2026-09-08, 1.0.5)

Awake Quick Tap is confirmed by the user. Screen-off double-back-tap remains a hard requirement, alongside the prior preference for minimal background use.

Design: replace equal-height Capture regions with a vertically centered, scrollable group. Keep the 132 dp circle and 84% card width; use a 24 dp circle-to-writing-region gap rather than the observed 144 dp gap on the tall emulator. Voice-off/retry states display “Tap the circle to use speech.” instead of asserting permanent unavailability. Actual save/recovery errors remain visible. The raw Original Markdown block is removed from Review; underlying documents and their non-SideNote content remain intact.

The three regression tests failed before implementation (spacing 143.6 dp instead of 24; guidance absent; raw Markdown shown). Revised Capture tests include enlarged text and English/Hebrew. Screenshot: acceptance-artifacts/ui-2026-09-08/capture-speech-guidance.png.

Screen-off investigation: physical Pixel 8 remains on stock Android 17. Sensor inventory exposes non-wake-up accelerometers, lift-to-wake, double-twist, and screen-touch wake gestures; no back-tap wake sensor is exposed through SensorManager. Existing secure Columbus settings contain the enabled flag, action, app and Capture shortcut; no screen-off override was found. No phone security settings were weakened and no background detector was added.

Android's sensor-hub client interface requires signature/privileged ACCESS_CONTEXT_HUB: https://android.googlesource.com/platform/system/chre/+/HEAD/doc/nanoapp_clients.md . Non-wake-up sensors cannot wake the processor from suspend: https://source.android.com/docs/core/interaction/sensors/suspend-mode . The custom-ROM Columbus implementation supports a screen-off option, but its documented integration is a system build, not an ordinary APK update: https://github.com/AxionAOSP/android_packages_apps_ColumbusService . The notes role can provide a system lock-screen note entry but does not enable a disabled hardware gesture: https://developer.android.com/develop/ui/views/touch-and-input/stylus-input/create-a-note-taking-app . No supported stock-Pixel solution satisfying both constraints has been established.

Final validation: 194 unit tests passed; 24 Capture/Review UI and save-to-review instrumentation tests passed. Release build/lint, signature and alignment checks passed. Installed 1.0.5 (code6) on physical Pixel in place; Capture shortcut selection retained. APK SHA256 FB8D3E212AEF43BD31EF7B1990C46E8EF28A348E7B617EA85FAF9B2912B894CF. Physical acceptance of revised UI is pending; screen-off back taps remain unresolved.

### Speech-first Capture (2026-09-08, 1.0.6)

Replaces the rejected centered group: a 220 dp speech control dominates the upper region; the 90%-width black editor sits at the bottom with a 1 dp gray border and white text. Focusing it pauses speech and expands the writing panel over the circle in 280 ms, above the keyboard. Back to speech restores the circle and listening; keyboard dismissal restores the resting layout. The editor fades while voice is enabled. The hidden circle is removed from accessibility traversal during typing. Enlarged text and reduced motion are supported.

Blob deformation and glow follow smoothed recognition RMS. Phase advances only while microphone levels exceed the silence threshold, with faster movement and larger deformation at higher levels; silence settles to a still circle. No separate microphone recorder or background listener is added.

Reproduced duplicate recognition starts and immediate shutdown on transient Busy in unit tests. An active-session guard prevents replacement starts; two bounded retries handle Busy/Client/Audio while preserving the voice toggle. Typing/off invalidates pending restarts and late callbacks. Physical confirmation of the user's three-tap symptom remains pending.

Keyboard screenshot: acceptance-artifacts/speech-first/capture-keyboard.png. The real keyboard test initially exposed an emulator System UI ANR stealing focus; after closing that system dialog the keyboard check passed.

Validation: 196 unit tests passed. Of 36 capture/lifecycle/end-to-end emulator tests, 33 passed in the first broad run; the three failures were old interaction expectations and an asynchronous project-list wait. After correcting those tests, all eight tests in the affected UI case and end-to-end class passed. The real keyboard visibility/focus check passed as part of the broad run. Release lint: zero errors (16 warnings, one hint). Signature and alignment verified. APK SHA256: 6B1B508065DB76DE68B909304450B1EAFD878EB66C90F35DEEEFD097C061D215.
Installed in place on physical Pixel 8; package confirms 1.0.6/code7 and Quick Tap shortcut remains capture. Real voice persistence and animation responsiveness await user acceptance.
