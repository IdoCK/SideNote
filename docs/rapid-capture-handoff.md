# Rapid capture handoff — 1.0.11

Screen-off completion previously awaited speech finalization (up to 1500 ms), durable save, and a reminder refresh. A second single-task launch arriving during this work was ignored, and completion closed the capture the user had just reopened.

Changes:

- CaptureActivity remembers a launch received while Finalizing/Saving/Saved. After the existing durable transaction succeeds, it finishes the old activity and launches a fresh Capture. A new screen-off or face-down event cancels the pending reopen. Repeated launches during ordinary recording still leave that recording alone. The pending reopen survives configuration recreation; a save failure retains the original draft rather than replacing it.
- Shared-input segmented recognition receives EOF from its worker after a pending pipe write. It no longer combines stopListening with immediate closure of both pipe ends. The existing 1500 ms fallback remains for providers that do not finalize, preserving the chance to receive the last spoken words. Cancellation/destruction still forcibly closes the pipe to unblock a stalled writer.
- Production Capture schedules reminder refresh in the existing process scope after a confirmed note commit. It no longer awaits the reminder scan before closing. No permanent worker/service or sensor was added. Startup/unlock reminder recovery remains available if the process ends before refresh.
- Diagnostic log SideNoteTiming reports completion duration without note text or audio.

Validation (2026-09-10):

- New rapid-relaunch regression failed against the old behavior, then passed after the fix. Updated the fake recovery store to actually clear its stored draft so the test can assert that the next capture is empty.
- 226 unit tests passed; release build and lint passed (0 errors, 17 warnings, 1 hint).
- 11 focused emulator checks passed: rapid relaunch, reminder refresh outliving the closed host, ordinary repeat launch, exactly-once screen-off save, no speech restart while saving, and 6 speech-intent checks.
- Broader lifecycle runs were stopped after stalling in unrelated cases; no full lifecycle-suite pass is claimed. Focused checks passed in 17.537 seconds.
- Signed with the existing release certificate and installed code 12 / 1.0.11 in place on Pixel 8. Assistant assignment remained SideNote.
- Physical two-note dictation speed/final-word confirmation pending at installation.

APK: releases/SideNote-1.0.11.apk

SHA256: 53534C2A49CAC51E4753F5269B3DAFE251C1378B13F390FA9BCBA3C72471257C
