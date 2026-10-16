# SideNote

**Capture the thought. Come back to it later.**

SideNote is a native Android app for quickly capturing a thought by voice or text, then reviewing it when you are ready. Capture is deliberately separate from Review: put the thought down, keep going, and return to dated notes and checkboxes later.

The implementation is local-first. Daily Markdown files in a folder you choose are the source of truth. There is no SideNote account, backend, note database, analytics SDK, or AI categorization service. Android's configured speech provider and your chosen document provider have their own privacy boundaries, described below.

This repository begins with an initial public source release after private development. It contains the current app, tests, build tools, browser demo, and curated product documentation; earlier development history and internal work logs are not part of this public snapshot. App version 1.0.13 is preserved from the existing implementation, rather than reset to imply a new product build.

## What it does

- Capture English, Hebrew, or mixed text; request bilingual speech recognition through Android.
- Finish a non-empty capture by turning the screen off, placing the phone face down, or backgrounding Capture. Speech pauses do not save. A single save gate coordinates completion signals.
- Review notes by day, check them off, and browse projects derived from visible `@ProjectName` tokens. Projects are rescanned from Markdown rather than stored in a separate index.
- Keep one app-private recovery draft when a write fails. A save confirmation appears only after a confirmed Markdown write.
- Open Capture from its launcher shortcut or Android's default-assistant entry point. The ordinary app icon opens Review.

The current source declares version **1.0.13**, version code **14**, and supports **Android 14 / API 34 or later**. Pixel 8 is the primary device described in the acceptance documentation; gesture and recognition behavior depends on the device and provider.

## First run

1. Install a debug build or an appropriately signed release and unlock the phone for setup.
2. Choose a dedicated writable notes folder through Android's system folder picker. Folder changes do not migrate existing notes.
3. Grant microphone and notification permissions if wanted. Typing and Review remain available without them.
4. Read the voice disclosure. Online fallback starts disabled; accepting the disclosure enables it. You can turn **Allow online voice recognition** off in Settings. Check English/Hebrew model support and request downloads if the provider offers them.

On a supported Pixel, configure **Settings → System → Gestures → Quick Tap → Open app → SideNote → Capture**. You can also select SideNote as the default digital assistant for the supported Power-button entry point; see [Power-button capture](docs/power-button-capture.md).

Capture has no Save/Done button. Typing stops listening; the grayscale blob toggles it. Discard removes the active draft without creating a note. Review, Settings, folder selection, and project suggestions require unlock. **The active capture or restored recovery draft can be shown over the lock screen.** Treat this as a capture convenience, not a confidential vault.

## Markdown format

The local date and time of the save attempt select `YYYY-MM-DD.md` and a 24-hour timestamp:

```markdown
# 2026-09-14

- [ ] **09:41** Try a folded paper shade for the desk light. @Studio

- [x] **10:05** Review the sketch.
  Keep the second line with this capture.
```

`[ ]` means unprocessed and `[x]` means processed. Review updates the verified target checkbox and preserves unrelated Markdown. Project names accept Unicode letters/digits, hyphens, and underscores; a note may have multiple project tokens. Explicit commands such as `tag project Studio` insert visible tokens. Rename or merge projects by editing the Markdown externally.

The selected folder is outside private app storage. Its documents survive uninstall, but provider availability, cloud synchronization, access permissions, and backups are your provider's responsibility. App-private settings and recovery drafts do not survive clearing app data or uninstalling.

## Architecture

The app uses Kotlin, Jetpack Compose, coroutines, DataStore preferences, Android's Storage Access Framework, and WorkManager.

| Area | Responsibility |
| --- | --- |
| `app/src/main/java/com/sidenote/app/capture/` | Capture lifecycle, completion signals, speech sessions, in-memory audio analysis, recovery handoff, and Compose capture UI |
| `data/markdown/` | Daily-file parsing, checkbox rewrites, and project/voice-command syntax |
| `data/documents/` | Repository operations and provider-backed document reads/staged replacements |
| `data/recovery/` | AtomicFile-backed temporary recovery draft and debounced persistence |
| `data/settings/` | Folder URI, onboarding, and voice preferences in DataStore |
| `review/`, `navigation/`, `privacy/` | Review state, navigation, and keyguard gates |
| `assistant/`, `notification/` | System assistant entry and derived unprocessed-note reminders |
| `AppContainer.kt` | Production dependency wiring and shared repository/recovery lifetimes |

Paths other than the first row are relative to `app/src/main/java/com/sidenote/app/`. `AppContainer` wires production dependencies without a dependency-injection framework. Repository operations serialize in-process mutations; recovery handoff keeps a finite save transaction alive across activity replacement.

A document replacement writes and verifies a new sibling, rechecks the original, renames the original to an owned backup, installs the replacement, and verifies it. This avoids truncating the authoritative file in place. Storage Access Framework providers do **not** offer a universal atomic replacement or compare-and-swap guarantee. Missing capabilities fail closed; post-mutation uncertainty preserves recovery and asks you to inspect Markdown before retrying. Keep independent backups of important notes.

## Privacy and trust boundaries

- SideNote first requests Android's on-device recognizer. With consent and fallback enabled, it may select the configured default recognizer, which may send audio to its provider. A support check does not guarantee accurate bilingual transcription or provider retention policy.
- SideNote's shared microphone stream feeds recognition and visual analysis in memory. The app does not save raw audio. This does not establish zero retention or end-to-end encryption by a speech provider.
- Notes are ordinary plaintext Markdown in the selected folder. A local folder can be private to the device; a cloud-backed provider can sync it. SideNote does not encrypt those files.
- The recovery draft is JSON in private app storage, with Android application sandbox protection. Corrupt recovery copies can be quarantined for preservation. This is not application-level encryption or secure erasure.
- Review uses Android keyguard checks. Capture deliberately permits the current/restored draft while locked, and does not block screenshots. Notifications reveal the number of unprocessed notes, not note bodies.
- The production manifest requests microphone, notifications, and boot-completed access. Assistant services require Android's voice-interaction binding permission. No app-level Internet permission is declared; delegated speech/document providers can still use their own network access.

Do not put credentials or especially sensitive material into demo notes or published screenshots. See [device acceptance and privacy checks](docs/pixel-8-acceptance.md) for the physical verification checklist.

## Build and test

Use the checked-in Gradle wrapper with **JDK 17**, Android SDK **Platform 37**, and the appropriate Android build tools. The project pins Gradle **9.4.1**, Android Gradle Plugin **9.2.0**, Kotlin **2.3.21**, and its AndroidX dependencies in `gradle/libs.versions.toml`. Open the repository root in Android Studio, install the required SDK through SDK Manager, and select JDK 17 as the Gradle JDK.

Configure `JAVA_HOME` and `ANDROID_HOME` for your installation, or let Android Studio generate an ignored `local.properties`. Do not commit local SDK paths.

```powershell
# From the repository root, after configuring the JDK and Android SDK:
./gradlew.bat testDebugUnitTest lintDebug assembleDebug

# Start a compatible emulator or connect a USB-debugging device first:
./gradlew.bat connectedDebugAndroidTest

# Canonical portfolio validation requires Node.js, with no package install:
node --test tests/portfolio-check.test.mjs
node scripts/portfolio-check.mjs
```

On macOS/Linux use `./gradlew` instead of `./gradlew.bat`. The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

Unit tests cover Markdown preservation, provider conflicts/recovery, capture state transitions, speech policies, notifications, settings, and architecture boundaries. Instrumented tests exercise Compose UI, permissions/navigation, provider behavior, and capture-to-review flows. These tests do not establish physical sensor, lock-screen gesture, or live speech-provider acceptance. The [device acceptance checklist](docs/pixel-8-acceptance.md) describes checks to run for the environment you intend to support; this snapshot does not publish historical device-run logs or claim fresh physical acceptance.

### Signing a release

On Windows, `scripts/sign-release.ps1` rebuilds the current release, validates its version and portfolio gate, signs with a dedicated local key, and checks signature/alignment. Set `JAVA_HOME` and `ANDROID_HOME`, install the script's configured build-tools version (default `37.0.0`), and run:

```powershell
./scripts/sign-release.ps1
```

The script creates ignored `.signing/` material and writes `releases/SideNote-<version>.apk`. Its saved password uses Windows account protection and cannot simply be moved to another account or computer. Keep a secure backup of both key and recoverable password; losing them prevents compatible updates. Never publish `.signing/`, signing keys, password exports, or device logs. A differently signed build cannot update an installed release in place; finish unsaved work before any uninstall.

## Browser demo and portfolio

[demos/sidenote-demo.html](demos/sidenote-demo.html) is a standalone browser simulation with fictional notes and in-memory state. It needs no backend, credentials, package installation, or real microphone permission. It is not the Android app. The [canonical portfolio bundle](portfolio/README.md) records native screenshots separately from browser reconstructions and staged illustrations.

Follow the [portfolio impact and validation workflow](portfolio/README.md) before changing native behavior or exported assets. The bundle checker validates version metadata, provenance fields, file hashes, and the native-source fingerprint; it cannot prove a screenshot or product claim is truthful.

## Known limitations

- Screen-off Quick Tap is unsupported through the current stock-Pixel gesture route. There is no always-on gesture detector, wake lock, overlay, or accessibility service to work around it.
- Face-down completion and locked launch require physical-device verification. Android and speech providers may ignore language-switching options or lack the required models.
- Unsupported folder-provider rename/create behavior prevents writes. A concurrent external edit can still race the final provider operation; uncertain outcomes require manual inspection before retrying.
- The app scans dated files rather than maintaining a note index. Very large folders or documents can increase latency and memory use.
- There is no built-in sync engine, app-level encryption, project rename interface, or retained-audio archive. Reminder counts can be visible on the lock screen according to Android notification settings.

See `docs/` for assistant setup and device acceptance guidance. This README describes the public source snapshot; it does not claim a newly built APK, device certification, or live portfolio deployment.
