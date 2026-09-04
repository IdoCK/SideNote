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

Unlock the phone for setup. Choose a dedicated writable notes folder using Android's system folder picker, grant microphone/notification permissions as desired, and read the voice disclosure. Accepting it enables online fallback; **Allow online voice recognition** can be turned off in Settings. Check English/Hebrew support and request model downloads if offered. Typing remains usable when microphone permission or bilingual speech support is unavailable.

The selected folder contains the authoritative notes. It is outside SideNote's private app storage, so uninstalling SideNote does not delete those documents; reinstall and choose the same folder to read them again. Provider availability, backups, and cloud synchronization are the folder provider's responsibility. Changing folders does not migrate existing notes. See [Android shared-document storage](https://developer.android.com/training/data-storage/shared/documents-files).

Configure Pixel 8 Quick Tap:

**Settings → System → Gestures → Quick Tap → Open app → SideNote**

Enable **Use Quick Tap**, then use the settings icon next to **Open app** to choose SideNote. Google documents Quick Tap as an action to open an app, not an app-specific hardware event. See [Pixel gesture instructions](https://support.google.com/pixelphone/answer/7443425?hl=en).

After setup, **Voice on at launch** defaults to on. The grayscale blob toggles listening; typing turns it off. English and Hebrew text, including mixed paragraphs, remain editable. There is no Save/Done control and pausing speech does not save. A non-empty draft saves when:

- The screen turns off (power button or timeout).
- The accelerometer reports stable face-down placement for at least 750 ms; ordinary handling should not trigger it and requires physical verification.
- The already-visible Capture activity receives a repeated launch intent.
- Capture backgrounds after setup prompts have completed (loss-prevention fallback).

Concurrent completion signals pass through one save gate. Success appears only after a confirmed write, followed by one haptic confirmation, `Saved · HH:mm`, and closing Capture. Discard intentionally removes the draft and recovery copy without writing a note.

Lock-screen launch depends on Pixel/Android policy; unfinished setup requires unlock. Capture exposes only the active capture/recovery draft. Review, previous days, Projects, Settings, and folder choice require unlock. A **second Quick Tap** saves only if the Pixel launcher delivers a new intent to the single-top Capture activity. This is not yet verified on a physical Pixel 8. If unsupported, use screen-off or stable face-down; SideNote does not request an accessibility service or modify the launcher.

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

Dates has explicit Previous/Next buttons, and Projects can jump back to the source day. The ongoing unprocessed reminder is derived by scanning recognized unchecked tasks; disabling notification permission does not prevent capture or Review.

## Failed writes and recovery

Permission loss or a failed document write must not be treated as a saved note. SideNote retains a single temporary app-private recovery draft; reopen Capture to restore it, repair folder access through unlocked Settings, then finish the capture again to retry. It clears recovery only after a confirmed Markdown write or deliberate Discard. Do not uninstall or clear app data while a draft is unsaved: the temporary recovery copy is app-private and will be lost.

Concurrent external edits cause a conflict/reload instead of a broad overwrite. Unknown Markdown is left untouched. SAF providers cannot promise universal atomic replacement: provider/device failure during truncating writes remains a storage-provider risk, so maintain independent backups of important notes. The automated failure fixture specifically rejects writes **before truncation**; it does not certify arbitrary provider crash recovery.
