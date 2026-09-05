# SideNote MVP acceptance — 2026-09-04

## Scope and status

Automation runs on `Pixel_8_API_37` **AVD**, Android 17 / API 37. It is not a physical Pixel 8. The physical acceptance gate is **PENDING: 0/7 executed, 7 NOT RUN**. No physical Android build number or physical Markdown evidence has been collected. The final 2026-09-04 `adb devices -l` recheck returned only `emulator-5554` (`sdk_gphone64_x86_64`). Emulator-only fingerprint: `google/sdk_gphone64_x86_64/emu64xa:17/CE2A.260420.019/15611780:userdebug/dev-keys`.

The final scoped residual gate **PASSED**. A complete 85-case device suite at the final correction revision was not run: the checkpoint attempt was stopped at 55/85 after two recorded failures, while the controller-approved final scope required the complete affected SAF class plus JVM/lint/APK gates. The earlier complete 81-case device suite passed at `643cb4e`. The APK is a debug build, not a signed production release; the complete physical MVP acceptance remains pending.

## Automated evidence

Environment: Windows PowerShell; JDK 17 at `C:\Users\Ido\scoop\apps\temurin17-jdk\current`; SDK `C:\Users\Ido\Android\Sdk`; Gradle user cache `C:\Users\Ido\.gradle`. The `JAVA_HOME`, `ANDROID_HOME`, and optional `JAVA_OPTS=-Duser.home=C:\Users\Ido` setup is in the README.

Required clean gate:

```powershell
./gradlew.bat --stop
./gradlew.bat clean testDebugUnitTest lintDebug assembleDebug connectedDebugAndroidTest
```

The original final wave's clean invocation rebuilt all outputs from scratch. Its JVM, lint-analysis, and APK stages passed with **186 unit tests**, **0 failures, 0 errors, 0 skipped**; its first device pass exposed two synchronization failures at 79/81. Focused reruns then passed **2/2**, both complete affected classes passed **19/19**, and the complete post-fix device run passed **81/81**, **0 failures, 0 errors, 0 skipped**, in 9m 13s. A final focused **1/1** run explicitly verified effective 2× Activity font scale.

Scoped review then added four JVM regressions and four SAF device cases. The suggestion-lock tests and clear-after-confirmed-append tests were observed RED before their production fixes, then passed in complete affected JVM classes (**16/16** `CaptureViewModelTest`; **39/39** combined `CaptureCoordinatorTest` and `CaptureRecoveryHandoffTest`). The SAF class was observed RED for three incomplete-stage cases, then GREEN. A continuation review extended those cases through a later successful replacement plus repeated fresh-store reads/lists; they failed **3/13** on `b7def71` because retained stages still carried active transaction hashes. At final code revision `20b20c8`, the complete `SafTextDocumentStoreTest` passed **13/13** in 23s. It covers empty and partial stages, delete-unavailable quarantine, verified original/backup recovery, later writes on a fresh store, and fail-closed preservation of a conflicting target.

The clean checkpoint at `b7def71` completed compilation, **190/190** JVM tests, and APK assembly. Its device phase was stopped as obsolete at **55/85** after these two failures: `AccessibilityAndBidiTest.reusedWindowContainsOnlyUnlockSemanticsAtTheActualDismissalRequest` (unlock node not displayed) and `AccessibilityAndBidiTest.recoverableOnboardingErrorIsMonochromeAndAnnouncedPolitely` (`PixelCopy` timeout). This remains an incomplete/failed device attempt; no environmental cause is claimed. Per the controller-adjusted scope, it was not repeated after the isolated storage correction. Final `gradlew.bat testDebugUnitTest lintDebug assembleDebug` at `20b20c8` passed in 42s with **190 unit tests**, **0 failures, 0 errors, 0 skipped**, and successful APK assembly.

The final `lintDebug` report has **0 errors and 16 warnings**: 11 dependency/tool version notices, the existing missing application icon, two version-catalog suggestions, one Compose modifier-order suggestion, and one SharedPreferences KTX suggestion. No lint suppressions or baseline were added. Existing compiler/native-library notices remain: deprecated Compose test API in `AppLaunchTest`, and two native libraries packaged without symbol stripping.

APK verified at `app/build/outputs/apk/debug/app-debug.apk`, **33,803,407 bytes**. SHA-256:

```text
826D1B7165709724A494EC666AA7F3384131A103005A9D0A1B3E59665B2EF931
```

Generated evidence: `app/build/test-results/testDebugUnitTest/TEST-*.xml` currently totals 190 passing tests; `app/build/outputs/androidTest-results/connected/debug/TEST-Pixel_8_API_37(AVD) - 17-_app-.xml` currently contains the final 13-case SAF class; and `app/build/reports/lint-results-debug.xml` contains the 0-error/16-warning result. These artifacts are intentionally ignored in Git. The command chronology above distinguishes historical full-suite results, the incomplete 55/85 checkpoint, and the current scoped XML rather than presenting focused evidence as a full-device pass.

The end-to-end fixtures run the actual Capture/Main Activities, ViewModels, Markdown repository/codec, SAF store, `ContentResolver`, and file-backed `DocumentsProvider`. Assertions independently open the provider's UTF-8 documents and compare complete literal strings; they do not ask a repository mock what was saved. Clock, external speech callbacks, completion events, settings inputs, and lock state are controlled. Recovery uses the production atomic-file store. Provider faults are injected before staging and after rename mutations to verify confirmed, rejected, and uncertain classifications; arbitrary hardware power loss and provider contract violations are not certified.

Literal integration coverage: typed Hebrew capture followed by checkbox processing; voice partial/final merged with typing; simultaneous completion signals; failed write, recovery relaunch and retry; cross-day case-insensitive project view with source-only checkbox update. Additional checks cover feedback, semantics, mixed bidi, 2× font size, reduced-motion rendering, non-gesture day controls, and reused-window content at the keyguard-dismissal request. Architecture tests scan production sources only for forbidden HTTP/analytics/note-database/audio-recording dependencies.

Example automated fixture evidence (not physical-device evidence):

```markdown
# 2026-08-27

- [ ] **18:26** רעיון חדש @SideNote
```

After the real Review checkbox action, the asserted document differs only at `[x]`:

```markdown
# 2026-08-27

- [x] **18:26** רעיון חדש @SideNote
```

## Physical Pixel 8 prerequisites

1. Connect a real Pixel 8 with USB debugging enabled; run `adb devices -l`. Do not select an `emulator-*` serial. Record serial/model, Android version/build number (Settings → About phone → Android version), Pixel Launcher version, configured speech provider/version, system languages, date/time zone, and APK SHA-256. Physical build number: **NOT RECORDED — no device**.
2. Install the verified APK on that device. Unlock, select a dedicated writable test notes folder, accept the voice disclosure only if online recognition is permitted for this test, and grant microphone/notification permissions. Record fallback setting and model-support/download results.
3. Configure **Settings → System → Gestures → Quick Tap → Open app → SideNote** and enable Voice on at launch. Use unique test tokens such as `pixel-case-3-run-1` so each attempt can be counted in the document. Do not substitute `adb am start` for physical Quick Tap.
4. Record each case's actual time, outcome, observed events, and verbatim before/after Markdown excerpt from the selected folder. Expected behavior below is a test specification, not observed evidence. If no write is expected, record unchanged-file evidence. Retain recordings only if separately authorized; no audio recordings are needed.

## Seven physical acceptance cases

### PX-01 — normal and lock-screen Quick Tap launch

Status: **NOT RUN**. Observed Android build: **not recorded**. Resulting Markdown: **not collected**.

Arrange: setup complete; phone initially unlocked on its home screen. Act: physically double-tap the back, type `pixel-case-1-unlocked`, then turn the screen off. Repeat from the lock screen after locking the phone, using a new token if launch is permitted. Assert: Capture opens, no historic content is exposed, and each completed non-empty capture yields one unchecked task. Record whether Pixel/Android permits locked launch; an OS refusal must be documented, not bypassed. Reopen after a fresh install/unconfigured state and verify setup requires unlock.

### PX-02 — default-on immediate listening

Status: **NOT RUN**. Observed Android build: **not recorded**. Resulting Markdown: **not collected**.

Arrange: setup/model check complete, microphone allowed, Voice on at launch enabled. Act: Quick Tap and immediately speak `pixel case two` without touching the blob; finish by screen-off. Repeat with the setting disabled to verify opt-out. Assert: the on state is exposed immediately and the configured recognizer receives the utterance; off-at-launch requires a deliberate blob tap. Record recognizer startup latency/limitations and resulting literal transcript rather than assuming perfect recognition.

### PX-03 — screen-off exactly-once save

Status: **NOT RUN**. Observed Android build: **not recorded**. Resulting Markdown: **not collected**.

Arrange: start Capture and type a unique marker; note the existing task count. Act: press power once; wait, unlock, and inspect the file. Repeat once using screen timeout, and once near midnight if feasible. Assert: each completed attempt adds exactly one task, including when screen-off and lifecycle callbacks overlap; filename/time reflect local commit time, and the temporary draft does not reappear on next launch. Save the full relevant before/after excerpt, including any duplicates if failing.

### PX-04 — face-down debounce and ordinary-motion false positives

Status: **NOT RUN**. Observed Android build: **not recorded**. Resulting Markdown: **not collected**.

Arrange: start a unique non-empty typed capture. Act: hold/type/tilt normally for 30 seconds, briefly turn face-down for less than 750 ms, then return upright; verify no save. Place steadily face-down on a flat surface for more than 750 ms, leave it there for several seconds, then lift/unlock. Assert: ordinary motion/brief placement does not finish; stable placement saves exactly once. Repeat with typical phone case and holding posture. Record false positives and actual Markdown count.

### PX-05 — second Quick Tap / launcher limitation

Status: **NOT RUN**. Observed Android build: **not recorded**. Resulting Markdown: **not collected**.

Arrange: launch Capture via physical Quick Tap and type a unique marker. Act: physically Quick Tap a second time while Capture is visible. Assert: if Pixel Launcher delivers a new single-top intent, one task is saved and Capture closes. Repeat three times and record Launcher/build/version. If the launcher does not deliver a new intent, record the supported screen-off/face-down alternative and the launcher limitation; do not add accessibility-service privileges or label a synthetic intent test as this pass.

### PX-06 — unlock-protected Review, history, Projects and Settings

Status: **NOT RUN**. Observed Android build: **not recorded**. Resulting Markdown: **not collected**.

Arrange: create two dated notes, project tags and an unprocessed reminder while unlocked; use a real PIN/password keyguard. Act: lock and activate notification Review; cancel unlock; try again and unlock. Also open Review/Projects/Settings while unlocked, lock, then re-enter via the notification so an existing window is reused. Assert: no prior note, project list or Settings content is shown before successful dismissal; cancellation remains on the generic unlock gate. Successful dismissal opens the most recent unchecked day. Verify folder picker cannot open while locked. These routing attempts must leave Markdown unchanged; collect a before/after excerpt and record any brief private-content flash.

### PX-07 — English, Hebrew and mixed speech with permitted fallback

Status: **NOT RUN**. Observed Android build: **not recorded**. Resulting Markdown: **not collected**.

Arrange: record provider, downloaded English/Hebrew models, network state and fallback consent. Act: separate captures for `New idea tag project SideNote`, `רעיון חדש תייג פרויקט בית`, and `Plan שלום 42 tag project SideNote`; finish each deliberately. Repeat with fallback disabled and, where safely controllable, local recognition unavailable with fallback explicitly allowed. Assert: no manual language selector; valid explicit commands yield visible project tokens, typed edits survive; allowed fallback is at most one default-recognizer attempt, and unsupported bilingual recognition leaves typing usable. Record actual transcript errors, support status and evidence of local/fallback selection when observable. Network availability alone does not prove which recognizer path ran. Do not claim provider zero retention or end-to-end encryption.

## Limits and follow-up

The known-committed recovery suppression is process-local: if the process survives a post-append recovery-clear failure, the next session masks that stale draft and retries cleanup. Process death after Markdown commit but before a successful clear can still expose the old recovery because the temporary recovery format has no durable commit transaction id. SAF recovery preserves incomplete bytes under hidden inactive quarantine names; providers that support rename but not deletion can accumulate those hidden files, while a provider that cannot safely rename or delete an incomplete artifact remains fail-closed.

TalkBack spoken output on physical hardware, OEM/recognizer accuracy, real sensor false-positive rates, actual Quick Tap repeated delivery, secure keyguard frame behavior, and haptic perceptibility remain manual checks. Automated semantics and controlled recognizer callbacks do not certify those external behaviors. This document does not claim a WCAG conformance audit, a measured coverage percentage, or a production release approval.
