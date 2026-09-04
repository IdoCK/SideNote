# SideNote MVP acceptance — 2026-09-03

## Scope and status

Automation runs on `Pixel_8_API_37` **AVD**, Android 17 / API 37. It is not a physical Pixel 8. The physical acceptance gate is **PENDING: 0/7 executed, 7 NOT RUN**. No physical Android build number or physical Markdown evidence has been collected. The final 2026-09-03 `adb devices -l` recheck still returned only `emulator-5554` (`sdk_gphone64_x86_64`). Emulator-only fingerprint: `google/sdk_gphone64_x86_64/emu64xa:17/CE2A.260420.019/15611780:userdebug/dev-keys`.

The automated clean gate **PASSED**. The APK is a debug build, not a signed production release; the complete physical MVP acceptance remains pending.

## Automated evidence

Environment: Windows PowerShell; JDK 17 at `C:\Users\Ido\scoop\apps\temurin17-jdk\current`; SDK `C:\Users\Ido\Android\Sdk`; Gradle user cache `C:\Users\Ido\.gradle`. The `JAVA_HOME`, `ANDROID_HOME`, and optional `JAVA_OPTS=-Duser.home=C:\Users\Ido` setup is in the README.

Required clean gate:

```powershell
./gradlew.bat --stop
./gradlew.bat clean testDebugUnitTest lintDebug assembleDebug connectedDebugAndroidTest
```

Final clean run: **exit 0**, `BUILD SUCCESSFUL in 7m 15s`; **83 actionable tasks, 83 executed**. XML reports confirm **154 unit + 69 instrumentation tests**, **0 failures, 0 errors, 0 skipped**. This includes 15 new end-to-end/accessibility cases and the architecture unit guard. Android lint: **0 errors, 14 warnings** (11 dependency/tool version notices, the existing missing application icon, and two version-catalog suggestions). No lint suppressions or baseline were added. Existing compiler/native-library notices: deprecated Compose test API in `AppLaunchTest`, and two native libraries packaged without symbol stripping.

APK verified at `app/build/outputs/apk/debug/app-debug.apk`, **33,608,358 bytes**. SHA-256:

```text
D4A8E675D906497409B5643356476FDF182493ADB472FEA5C6AFAE5433F71877
```

Generated evidence: `app/build/test-results/testDebugUnitTest/TEST-*.xml`, `app/build/outputs/androidTest-results/connected/debug/TEST-Pixel_8_API_37(AVD) - 17-_app-.xml`, and `app/build/reports/lint-results-debug.xml`. These build artifacts are intentionally ignored in Git. The first clean run had all tests passing but stopped on a test-fixture StateFlow lint error; that fixture was corrected and the exact clean gate rerun successfully.

Final pre-commit repeat, `./gradlew.bat testDebugUnitTest lintDebug assembleDebug connectedDebugAndroidTest`: **exit 0**, `BUILD SUCCESSFUL in 7m 9s`; **82 tasks: 2 executed, 80 up-to-date**. All **69 instrumentation tests re-executed and passed**; unit/lint/build outputs were up to date from the clean run. `git diff --check` and the staged diff check passed.

The new end-to-end fixtures run the actual Capture/Main Activities, ViewModels, Markdown repository/codec, SAF store, `ContentResolver`, and file-backed `DocumentsProvider`. Assertions independently open the provider's UTF-8 documents and compare complete literal strings; they do not ask a repository mock what was saved. Clock, external speech callbacks, completion events, settings inputs, and lock state are controlled. Recovery uses the production atomic-file store. Provider-write failure is injected before truncation; arbitrary provider power loss mid-write is not certified.

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

TalkBack spoken output on physical hardware, OEM/recognizer accuracy, real sensor false-positive rates, actual Quick Tap repeated delivery, secure keyguard frame behavior, and haptic perceptibility remain manual checks. Automated semantics and controlled recognizer callbacks do not certify those external behaviors. This document does not claim a WCAG conformance audit, a measured coverage percentage, or a production release approval.
