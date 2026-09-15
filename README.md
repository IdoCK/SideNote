# SideNote

SideNote is a native Android, local-first voice and text capture app for Pixel 8. Capture writes ordinary daily Markdown tasks; Review edits those same files. There is no SideNote account, backend, note database, project index, analytics, AI categorization, or retained audio.

Automated results and the separately pending physical-device checklist are in [Pixel 8 acceptance](docs/pixel-8-acceptance.md). Emulator automation is not physical Pixel acceptance.

## Toolchain

- Android Studio Quail 2 (2026.1.2), the project's installed baseline; not a claim that it is the latest Studio. The project pins AGP 9.2.0. See [Android Studio compatibility](https://developer.android.com/studio/releases).
- Install Android SDK Platform 37, platform-tools, and the Android Emulator through SDK Manager. The app targets/compiles API 37 and requires Android 14 / API 34 or later.
- Use **JDK 17** as the Gradle JDK (Settings → Build, Execution, Deployment → Build Tools → Gradle). Do not assume the IDE's bundled runtime is JDK 17.
- Use the checked-in Gradle wrapper; no global Gradle installation is needed. Open this repository root in Studio and sync.

PowerShell example for this workstation (adjust paths on another machine):

```powershell
$env:JAVA_HOME = 'C:\Users\Ido\scoop\apps\temurin17-jdk\current'
$env:ANDROID_HOME = 'C:\Users\Ido\Android\Sdk'
$env:JAVA_OPTS = '-Duser.home=C:\Users\Ido'
./gradlew.bat --stop
./gradlew.bat clean testDebugUnitTest lintDebug assembleDebug connectedDebugAndroidTest
```

Start an API 37 AVD (the verified AVD is `Pixel_8_API_37`) or connect a USB-debugging device before connected tests. Gradle uses the normal user cache (`C:\Users\Ido\.gradle` here). Keep machine-specific `local.properties`, signing keys, build output, and device logs out of Git. The debug APK is `app/build/outputs/apk/debug/app-debug.apk`.

Focused acceptance gate (quote the `-P` argument in PowerShell):

```powershell
./gradlew.bat connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.package=com.sidenote.app.e2e' testDebugUnitTest --tests 'com.sidenote.app.ArchitectureBoundaryTest'
```

## First run and everyday capture

### Installable release APK

Version 1.0.12 refines Review: larger tappable dates replace Previous/Next and Browse dates; white rectangular active tabs span Dates, Projects, and Settings. Notes show wider rotating chevrons only when text overflows, with smooth expansion and collapse. Release build and 226 unit tests pass; Review UI checks pass after updating Settings test scrolling and synchronization. The broader accessibility suite still has an unrelated Capture speech-unavailable message assertion failure.

Run `./gradlew.bat assembleRelease`, then `./scripts/sign-release.ps1` with `JAVA_HOME` and `ANDROID_HOME` set as above. This produces `releases/SideNote-1.0.12.apk`, verifies its signature and alignment, and uses a dedicated release key rather than the Android debug key. Send that APK to the phone and open it to install. Version 1.0.11 preserves rapid activations during saving, finalizes shared audio with EOF, and moves reminder refresh off the capture-close path. See docs/rapid-capture-handoff.md. Version 1.0.10 adds the default-assistant entry point for holding Power to open Capture while locked, without a background gesture detector. See docs/power-button-capture.md. Version 1.0.9 preserves dictated phrases across pauses, adds automatic punctuation with explicit spoken insertion commands, and animates Settings navigation. See docs/continuous-dictation-and-settings.md. It keeps the speech blob static during silence and drives its morphing from live volume, pitch and spectral brightness. A single in-memory microphone stream feeds recognition and analysis; providers that reject it fall back to volume-only feedback. Review notes expand smoothly, days slide directionally, and tabs/project details transition with a short slide and fade. See docs/speech-and-review-motion.md for validation and device limitations. A stable status label distinguishes Starting, Listening, Processing, and Reconnecting. The textbox retains its size and follows Android keyboard insets directly, avoiding competing resize animations and layout jumps on the first transcript. Startup checks no longer block stop taps or physical completion; successful support is reused between utterances. Temporary recognition failures retry with backoff until the user stops capture, while permission/model failures still leave typing available. Selection-only text-field callbacks no longer accidentally stop recognition. Tap-to-speak guidance and the simplified Review remain. The app icon still opens Review; the dedicated Capture shortcut keeps repeated launches in Capture. It includes the duplicate-save fix and SideNote icon, and uses the same signing key as earlier releases so it can update them in place; do not uninstall the release first.

Version 1.0.1 fixes temporary-file MIME naming that prevented saves on Android's external-storage provider, paints the Settings/Review system-bar areas dark, and permits a recognition attempt when a speech provider explicitly reports that it does not implement support queries. Physical Pixel voice transcription is confirmed; Quick Tap before unlock and with the screen off remains unresolved. See [first-use fixes](docs/first-use-fixes.md).

The script creates the signing key once in ignored `.signing/`; never commit or share that directory. Its password is protected with Windows user encryption in `password.xml` (usable only by the same Windows account on this computer). Preserve the key and arrange a secure, portable password backup before migrating computers; losing either prevents signing compatible updates. Build artifacts are ignored too.

If the debug app is already installed, finish any unsaved capture before uninstalling it to install the differently signed release. Shared-folder Markdown survives uninstall, but private recovery drafts and settings do not. Choose the same notes folder during setup. A signed release package does not imply physical Pixel acceptance is complete.

Unlock the phone for setup. Choose a dedicated writable notes folder using Android's system folder picker, grant microphone/notification permissions as desired, and read the voice disclosure. Accepting it enables online fallback; **Allow online voice recognition** can be turned off in Settings. Check English/Hebrew support and request model downloads if offered. Typing remains usable when microphone permission or bilingual speech support is unavailable.

The selected folder contains the authoritative notes. It is outside SideNote's private app storage, so uninstalling SideNote does not delete those documents; reinstall and choose the same folder to read them again. Provider availability, backups, and cloud synchronization are the folder provider's responsibility. Changing folders does not migrate existing notes. See [Android shared-document storage](https://developer.android.com/training/data-storage/shared/documents-files).

Configure Pixel 8 Quick Tap:

**Settings → System → Gestures → Quick Tap → Open app → SideNote → Capture**

Enable **Use Quick Tap**, then use the settings icon next to **Open app** to choose SideNote, then choose its Capture shortcut. The ordinary app icon opens Review. Google documents Quick Tap as an action to open an app, not an app-specific hardware event. See [Pixel gesture instructions](https://support.google.com/pixelphone/answer/7443425?hl=en).

After setup, **Voice on at launch** defaults to on. The grayscale blob toggles listening; typing turns it off. English and Hebrew text, including mixed paragraphs, remain editable. There is no Save/Done control and pausing speech does not save. A non-empty draft saves when:

- The screen turns off (power button or timeout).
- The accelerometer reports stable face-down placement for at least 750 ms; ordinary handling should not trigger it and requires physical verification.
- Capture backgrounds after setup prompts have completed (loss-prevention fallback).

Concurrent completion signals pass through one save gate. Success appears only after a confirmed write, followed by one haptic confirmation, `Saved · HH:mm`, and closing Capture. Discard intentionally removes the draft and recovery copy without writing a note.

Lock-screen launch depends on Pixel/Android policy; unfinished setup requires unlock. Capture exposes only the active capture/recovery draft. Review, previous days, Projects, Settings, and folder choice require unlock. Repeated Capture launches keep the current draft open; they no longer save it. The physical Pixel does not deliver Quick Tap while its screen is off. The user chose minimal background use, so SideNote does not run an always-on tap detector, hold a wake lock, or request overlay/accessibility access. Screen-off back-tap capture remains unsupported by the current system gesture route.

## Voice processing and privacy

SideNote tries Android's on-device recognizer first. Its bilingual request enables balanced automatic switching constrained to `en-US` and `he-IL`; there is no language selector. Supported local model downloads are requested during setup. Android warns that recognition implementations may ignore switching options, and the required models must be available. A support check is not a guarantee of accurate mixed-language transcription on every provider/device. See [RecognizerIntent language switching](https://developer.android.com/reference/android/speech/RecognizerIntent#EXTRA_ENABLE_LANGUAGE_SWITCH).

After disclosure acceptance, if fallback is allowed and a recoverable local support/network failure occurs, SideNote makes one attempt with Android's configured default recognizer. That recognizer may send microphone audio to its provider's servers. SideNote itself retains no raw audio, but cannot promise that Google or another configured provider has zero retention or end-to-end encryption. Provider processing and retention are governed by that provider's policies. Disable online fallback to avoid SideNote selecting this path; unavailable local bilingual recognition then leaves typed capture available. See [Android SpeechRecognizer](https://developer.android.com/reference/android/speech/SpeechRecognizer).

If recognition fails, typed content and usable partial transcript stay in the draft. An explicit voice command such as `tag project SideNote` or `תייג פרויקט בית` adds a visible project token; ordinary “at” does not.

## Markdown and projects

The successful save attempt's **local** date selects `YYYY-MM-DD.md`; local time uses 24-hour `HH:mm`. A new file begins with the date heading and a blank line:

```markdown
# 2026-08-27

- [ ] **18:26** רעיון חדש @SideNote

- [ ] **18:30** @Home-Renovation First line
  Second line in the same capture.
```

`[ ]` is unprocessed; `[x]` is processed. Review changes only the verified target checkbox in the original document. Existing unrelated Markdown is preserved. Projects are rescanned from these files, case-insensitively, across dates; no independent project/status store exists.

`@ProjectName` names contain Unicode letters/digits, with hyphens or underscores instead of spaces. A note can contain multiple tokens; the first observed spelling is used for display during a scan. Explicit English/Hebrew voice commands remove the command phrase and insert the corresponding token. Ambiguous commands remain prose. Rename/merge is performed by editing Markdown externally, not through a hidden project database.

After unlock, typing `@` in Capture offers matching project names rescanned from the Markdown source; no project index is created. Dates has a scrollable date browser and horizontal swipe alongside explicit Previous/Next buttons, and Projects can jump back to the source day. Review is also available from the launcher's static **Review SideNote** long-press shortcut even when there are zero notes or notifications are denied. The ongoing unprocessed reminder is derived by scanning recognized unchecked tasks; disabling notification permission does not prevent capture or Review.

## Failed writes and recovery

Permission loss or a failed document write must not be treated as a saved note. SideNote retains a single temporary app-private recovery draft; reopen Capture to restore it, repair folder access through unlocked Settings, then finish the capture again to retry. It clears recovery only after a confirmed Markdown write or deliberate Discard. If Android reports an uncertain result after a provider operation may already have changed the folder, SideNote keeps the draft/recovery copy and asks you to inspect the Markdown file before retrying. Do not uninstall or clear app data while a draft is unsaved: the temporary recovery copy is app-private and will be lost.

Concurrent external edits detected before replacement cause a conflict/reload instead of a broad overwrite. Unknown Markdown is left untouched. SideNote never truncates the authoritative target in place: on providers that advertise sibling creation and rename, it writes and verifies a uniquely named app-owned stage, immediately revalidates the target, renames the original to an app-owned backup, then installs and verifies the replacement. An interrupted owned backup is restored only when the target is absent; these artifacts are ignored as note/project/status truth and are removed only when their private ownership marker and contents are verified.

Android's generic Storage Access Framework does not provide cross-provider compare-and-swap or a universal atomic-replace guarantee. A provider may misreport capabilities, violate rename behavior, or race an external writer after SideNote's last validation. SideNote therefore fails closed on missing capabilities, reports post-mutation ambiguity as uncertain, preserves recoverable artifacts, and never calls this process atomic. Keep independent backups of important notes; providers without the required create/rename behavior are unsupported for writes.
