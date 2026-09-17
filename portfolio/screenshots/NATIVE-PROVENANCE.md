# SideNote native screenshot provenance

Captured 2026-09-17 from freshly rebuilt SideNote 1.0.12 (versionCode 13), debug variant, on the local Pixel_8_API_37 emulator (Android 17/API 37, 1080 x 2400, density 420). No physical phone was connected or modified.

Source checkout: C:/Users/Ido/Documents/ChatGPT/SideNote, Git HEAD 138ad7eac5a5f7d02e920d850c59e4142b3d5a2a. No Kotlin/source changes made for this capture. Built offline with `gradlew.bat assembleDebug assembleDebugAndroidTest --offline` (successful).

Debug APK SHA-256: BF44C34A24779D77534E8EDC8AEBA56BC8F9D27207ABFF1B48672BF7B3A0334D.

These are authentic native Compose screenshots with synthetic test states, not browser reconstructions or photographs. They are captured from Compose roots and omit Android system UI. They do not establish real microphone, recognition, sensor, or physical-device acceptance.

| Files | Native test source and fixture |
| --- | --- |
| capture-liquid-1.png, capture-liquid-2.png | CaptureScreenTest.liquidBlobChangesShapeWithoutMovingItsTapTarget: empty editor, voice enabled, Listening phase, synthetic RMS 0.85. Two animated blob states. |
| capture-speech-guidance.png | CaptureScreenTest.speechRetryOffersGuidanceInsteadOfClaimingVoiceIsUnavailable: empty editor, voice disabled, speech unavailable; shows current Tap the blob guidance. |
| review-note-collapsed.png, review-note-expanded.png | ReviewScreenTest.expandingAndCollapsingNoteMovesItsBottomGraduallyAndKeepsItsTopAnchored: Aug 27, 2026, 08:15, fixture text A long note with enough detail to wrap onto several lines. repeated eight times. |
| review-settings-selected.png | ReviewNavigationTest.configuredMainActivityRoutesFromReviewToSettingsAndPersistsVoiceToggle: configured MainActivity, test notes folder SideNote, Settings selected before toggling voice. |

All four targeted instrumentation tests passed: OK (4 tests), 31.699 seconds. PNGs pulled from /sdcard/Android/data/com.sidenote.app/files/. Capture listening, expanded Review, and Settings images visually inspected after pull. Original screenshots remain unedited.

The emulator's restored snapshot initially stalled installation; cold boot with -no-snapshot resolved it. Captures were taken only after both fresh APK installs succeeded and installed package version was verified as 1.0.12/code13.

## Local Review date styling update — 2026-09-17

Fresh native Compose capture on emulator-5554 on 2026-09-17 from local modified SideNote 1.0.12/code13, based on 7a5f60779b13b387036189b92c4d3f9480c0f9e1; fictional multi-date ReviewScreenTest fixture, Sep 5 selected with Sep 8/Sep 10 plain text. Lossless WebP from unedited native PNG. Verified native tests: OK (12 tests), 67.132 seconds; complete ReviewScreenTest class; debug and test APK build successful. Local working tree, not a committed or published release; see NATIVE-PROVENANCE.md.

Debug APK SHA-256: daa66f27c3b4aa0f7aec58f56494693658ec55383337e2d9bfcd3739407cc575. Captured dimensions: 1080 x 2400.

The baseline regression reproduced an inactive grey border (#555555) against the page (#111111). After the fix, native screenshots with the first and second dates selected were visually reviewed; inactive targets are transparent while the selected rectangle is retained. Existing date/tab transition tests were included in the full ReviewScreenTest class. These synthetic emulator captures make no physical-device or real-note claim. Earlier provenance above remains applicable to unchanged native Settings imagery.

## Release 1.0.13/code14 image review — 2026-09-17

The corrected Review screenshot remains the authentic fixed-source 1.0.12/code13 capture described above. Release 1.0.13 changes version metadata only; native Review styling and screenshot contents are identical to the verified source. Settings retains its earlier documented capture. No image is claimed to be a fresh 1.0.13 capture.
