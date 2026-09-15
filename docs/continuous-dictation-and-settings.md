# SideNote 1.0.9

September 10, 2026 · version code 10

## Continuous dictation

The shared microphone source now explicitly requests Android's segmented-session mode. Completed segment callbacks are accumulated separately from the current hypothesis. New partial results replace only that live phrase; they cannot replace completed phrases. At session completion the full accumulated transcript is finalized. Repeated identical phrases remain repeated. Ordinary recognizer sessions retain their existing final-result behavior, and stale callbacks from earlier sessions are ignored.

## Punctuation

SideNote requests Android's quality-oriented automatic formatting, including punctuation and capitalization. Availability depends on the installed recognition provider.

Explicit commands work in recognized text: “insert period”, “insert question mark”, “insert comma”, “insert exclamation mark/point”, “insert colon”, “insert semicolon”, “new line”, and “new paragraph”. “Insert new line/paragraph” also works. Bare “period” remains ordinary text so phrases such as “a period of time” are preserved. Matching punctuation already supplied by the recognizer is not doubled. Spoken project commands accept sentence punctuation and preserve paragraph breaks.

## Settings

Settings opens and closes with the same 220 ms slide/fade as Review. Both Android Back and the on-screen Back control return to the selected Review day/tab/project. Outgoing actions are guarded and outgoing content is hidden from accessibility. Locking removes protected content immediately, outside the animation.

## Verification

Pause/resume and repeated-segment regressions were observed failing before the accumulation fix. Additional tests cover final-result punctuation, paragraph preservation with project tags, explicit punctuation commands, and saving the complete transcript on an end event. Settings navigation, intermediate transition positions, stale actions, and immediate privacy gating are checked on the emulator. 225 JVM tests pass. 14 targeted emulator checks pass (Settings navigation/privacy, speech intent and Settings controls). Release build/lint pass with 0 errors, 17 warnings and 1 hint. Signature and alignment verified with the existing release key. Installed in place on the Pixel 8 as version 1.0.9/code10; live diagnostics confirm the new completed-segment callback is being delivered. User confirmation of pause/resume behavior remains pending.

References: [Android segmented sessions and formatting](https://developer.android.com/reference/android/speech/RecognizerIntent), [segment callbacks](https://developer.android.com/reference/android/speech/RecognitionListener).

APK SHA-256: 2E670D27FFD169AE093226B5F118EF60EE2FE8AADB8266B66D75F9D38DD1FF3A.
