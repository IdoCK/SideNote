# SideNote Android MVP Design

**Date:** 2026-08-27  
**Status:** Approved design  
**Platform:** Native Android, optimized for Pixel 8  
**Implementation:** Kotlin and Jetpack Compose

## Product Essence

SideNote is a capture appliance for fleeting thoughts, not a general-purpose knowledge manager. Its promise is **Capture now. Think later.** It separates the low-attention act of getting an idea out of one's head from the higher-attention act of reviewing and organizing it.

The core loop is:

1. Quick Tap the back of the phone.
2. Speak, type, or do both.
3. Turn the screen off, place the phone face-down, or Quick Tap again.
4. Continue with the day knowing the thought was appended to today's Markdown file.

SideNote complements tools such as Obsidian rather than replacing them. It owns the moment before a knowledge system: fast capture into open, durable files that the user can later inspect, reorganize, synchronize, or process with other tools.

## Goals

- Make capture require no title, destination, category, or Save decision.
- Launch from Pixel Quick Tap and begin listening immediately after one-time setup.
- Support English, Hebrew, and bidirectional text from the first release.
- Keep Markdown files in a user-selected local folder as the sole source of truth.
- Support short typed notes, dictated notes, and combined typed/dictated notes.
- Preserve a chronological daily inbox with explicit processed/unprocessed checkboxes.
- Let users explicitly associate entries with projects across multiple days using `@ProjectName`.
- Keep review and organization out of the lock-screen capture surface.
- Avoid accounts, analytics, AI categorization, raw-audio retention, and proprietary note storage.

## Non-Goals

- General document editing, backlinks, reminders, collaboration, or rich media.
- Meeting recording, speaker separation, or long-form audio archiving.
- AI summaries, inferred projects, automatic categorization, or generated titles.
- Cross-device synchronization. Users may point SideNote at a folder managed by another permitted sync tool.
- Project rename/merge tooling in the MVP.
- An accessibility service or other intrusive workaround to intercept hardware gestures.

## Architecture

The app is a single native Android application with two protected destinations:

- **Capture:** may display over the lock screen where Android permits. It owns speech recognition, typed input, voice feedback, gesture completion, draft recovery, and Markdown append.
- **Review:** requires device unlock. It reads daily Markdown files, presents date and project views, and safely changes Markdown checkboxes.

Supporting units have narrow responsibilities:

- **Document repository:** accesses the persisted Storage Access Framework tree URI, creates daily files, parses recognized entries, appends captures, and rewrites only targeted checkbox markers.
- **Capture coordinator:** combines typed text with partial/final speech results and deduplicates simultaneous completion signals.
- **Speech engine:** tries Android on-device recognition first and optionally retries with the system default online-capable recognizer.
- **Gesture coordinator:** observes face-down orientation, screen-off/background transitions, and repeated launch intents.
- **Notification coordinator:** derives the unchecked count from Markdown and maintains the ongoing Review notification.
- **Settings store:** retains only configuration such as the folder URI, chosen speech language, online-fallback consent, and onboarding completion. It stores no note content or project index.
- **Recovery draft store:** temporarily mirrors an unsaved, non-empty capture in app-private storage. It is cleared immediately after a confirmed Markdown write or deliberate discard and is not a note database.

There is no Room database, project table, note index, or processed-state index. Project membership and checkbox state are derived from Markdown every time they are needed.

## First-Run Setup

Setup has three short steps:

1. Choose a notes folder using Android's system folder picker. SideNote persists read/write access to that folder.
2. Grant microphone permission and, on Android versions that require it, notification permission.
3. View illustrated Pixel instructions: **Settings → System → Gestures → Quick Tap → Open app → SideNote**.

The voice-privacy disclosure appears once during setup:

> Voice is processed on this phone when available. When necessary, Android's speech service may process it online.

The adjacent **Allow online voice recognition** setting defaults to enabled only after the user accepts this disclosure. The same control remains available in Settings. Everyday capture does not display recurring privacy warnings or a distracting local/online badge.

Setup and permission prompts require an unlocked device. If Capture is invoked from the lock screen before setup is complete, the app asks the user to unlock and finish setup.

## Capture Experience

### Layout

Capture is an edge-to-edge near-black screen without an app bar, Done button, Save button, history control, or settings control.

- The upper 48–52% contains a centered grayscale voice-feedback blob approximately 112–144 dp across.
- The lower region contains an off-white writing card approximately 80–88% of screen width and at least 144 dp high.
- The card uses near-black text and a quiet placeholder such as **Enter thought here…**.
- A bottom-centered **Discard** control is the only explicit action and appears once the draft contains content.
- When the device is locked, no existing note content, project list, history, or settings affordance is exposed.

The visual language is “an analog notebook in inverted ink”: `#050505` background, off-white primary text, restrained gray surfaces, and no decorative color. The blob uses only off-white, light gray, mid-gray, and charcoal. `@Project` tokens remain monochrome in the MVP.

Use a legible Android system/Noto sans family with complete English and Hebrew coverage. Do not use a literal handwriting font. Dates are large and expressive through scale and spacing rather than typeface novelty.

### Voice Feedback

The blob is drawn with Compose Canvas. Speech-recognizer RMS callbacks feed a smoothed amplitude value that subtly changes the outline and scale while independently animating interior waveform bars.

- Listening: gentle continuous deformation between roughly 0.96 and 1.08 scale.
- Finalizing: slower contraction.
- No input or error: still form plus concise status text or icon.
- Reduced motion: fixed outline with waveform-level or opacity changes only.

Listening, failure, and saved states are never communicated through motion alone.

### Speech and Typing

Once one-time setup and permissions are complete, a normal or Quick Tap launch starts listening immediately.

- The selected recognition language is `en-US` or `he-IL`.
- A compact EN/HE switch may change the active recognizer without navigating away.
- Android on-device recognition is attempted first.
- If the local recognizer is unavailable or fails for a recoverable support/network reason and online fallback is allowed, SideNote retries once using the system default recognizer.
- If both paths fail, all typed text and any usable partial transcript remain in the card.
- SideNote never retains raw microphone audio.

The capture coordinator treats the current speech hypothesis as an owned text span. Partial results may replace only that span; manual typing elsewhere is never overwritten. Final speech commits the span into the editable draft. This supports typing before, during, and after dictation.

Text uses content-based paragraph direction. English aligns to logical start, Hebrew aligns to logical start under RTL, and Android bidirectional handling supports mixed Hebrew, English, numbers, punctuation, and project tags. Canonical timestamps remain isolated left-to-right.

### Completion Signals

There is no automatic save merely because the speaker pauses and no on-screen Done control.

A non-empty draft is committed by any of these signals:

- The screen turns off, whether from the power button or screen timeout.
- The phone remains stably face-down for a short debounce interval.
- The already-visible single-top Capture activity receives another launch intent, expected when Pixel Quick Tap opens SideNote a second time.
- The Capture activity backgrounds for another reason, as a loss-prevention safety net after setup prompts have completed.

All completion signals flow through one idempotent save gate. Concurrent screen-off, lifecycle, and sensor events can create only one entry. After a confirmed write, SideNote gives one haptic pulse, briefly shows **Saved · HH:mm**, clears the recovery draft, and closes Capture.

Android does not expose a normal power-key callback to third-party apps; SideNote responds to screen-off/lifecycle state instead. Pixel Quick Tap exposes “open app,” not a dedicated callback. Repeated-launch completion therefore depends on the Pixel launcher delivering a new intent to the single-top activity and must be verified on a physical Pixel 8. SideNote will not request accessibility-service privileges to simulate this behavior.

Discard deliberately clears the active draft without creating a Markdown entry, clears the recovery copy, provides haptic/visual confirmation, and closes Capture. Empty captures close without writing an entry.

## Markdown Source of Truth

The user selects the containing folder. SideNote creates at most one file per local calendar day using the exact filename `YYYY-MM-DD.md`.

When first creating a file, SideNote writes:

```markdown
# 2026-08-27
```

Each capture is appended in chronological order as a standard Markdown task:

```markdown
- [ ] **14:26** @SideNote Example note text
```

Continuation lines are indented beneath the task:

```markdown
- [ ] **14:30** @Home-Renovation First line
  Second line in the same capture.
```

Rules:

- `[ ]` means unprocessed and `[x]` means processed.
- Timestamps use canonical local 24-hour `HH:mm` form.
- The filename date and timestamp are calculated at the successful save attempt, so a capture spanning midnight lands in the day on which it is committed.
- Notes are appended; existing unrelated Markdown is preserved.
- Review changes only the target task's checkbox marker after verifying the expected entry text and file contents.
- Markdown writes complete before SideNote reports success.
- The app never silently redirects a failed write to an internal long-term store.
- External or malformed content remains untouched and visible as raw file content; SideNote edits only entries it recognizes confidently.

Because Markdown is authoritative, uninstalling SideNote leaves all notes, project tags, timestamps, and processed states intact.

## Project Tags

Project association is explicit and deterministic. SideNote does not infer context.

- A project token uses `@ProjectName`.
- Names contain Unicode letters or digits and may use hyphens or underscores instead of spaces.
- Matching is Unicode-aware and case-insensitive; the first observed spelling is retained for display during that scan.
- A note may contain multiple project tokens.
- Typing `@` opens suggestions derived from project tokens already present in the selected folder. A new valid token creates a project implicitly.
- Spoken tagging requires an explicit command: **tag project ProjectName** in English or **תייג פרויקט ProjectName** in Hebrew.
- Recognized tag commands are removed from the prose and inserted as visible `@ProjectName` tokens.
- Repeating the command may attach multiple projects.
- The ordinary word “at” is never interpreted as a tag command.
- A small monochrome chip confirms recognized project membership without pausing capture.

Project rename and merge operations are excluded from the MVP because they would require coordinated rewrites across source files. Users may edit Markdown directly when necessary.

## Review Experience

Review requires device unlock and uses the same black, white, off-white, and gray system as Capture.

The top-level Review navigation has two tabs:

- **Dates:** daily notes in chronological/source order.
- **Projects:** project tokens discovered by scanning the daily Markdown files.

### Dates

The screen shows a large display date, a history/date-browser control, Settings, and explicit previous/next-day controls. Horizontal swipe also changes days but is never the only navigation method.

Recognized entries stay in file order. A row contains:

- A semantic 24 dp checkbox with at least a 48 dp touch target.
- An isolated timestamp.
- A one-line content preview.
- An expand/collapse chevron.

Expanded content appears inline. Checked entries remain in their original position, become visually subdued, and may use strikethrough. SideNote does not reorder unchecked entries above checked entries because file order is the daily-log mental model. Expanded/collapsed UI state is not persisted.

### Projects

The Projects tab scans all accessible `YYYY-MM-DD.md` files and lists discovered project names with matching-entry counts. Opening a project shows every matching entry across dates, newest first. Each result displays its source date and can navigate back to that day.

Checking an entry from a project view updates the checkbox in its original daily file. Project views contain no copied note content and no independent project state.

### Settings

Settings provides:

- Notes-folder selection/change.
- English/Hebrew recognition language.
- Allow online voice recognition.
- Pixel Quick Tap setup instructions.
- Permission status and recovery actions.
- A concise explanation that Markdown files are the source of truth.

Changing the folder requires an unlocked device. The app does not migrate files automatically in the MVP.

## Persistent Unprocessed Notification

After each successful append or checkbox change, and on app/boot recovery when folder permission is available, SideNote scans recognized Markdown tasks and counts unchecked entries.

- If the count is greater than zero, it posts an ongoing notification: **N unprocessed notes**.
- The notification has a **Review** action.
- Review opens only after unlock and selects the most recent day containing an unchecked entry.
- When the unchecked count reaches zero, the notification is removed.
- If notification permission is denied, note capture continues normally and Settings explains that the reminder is unavailable.

The count is derived from files, not a status index. The expected folder contains small daily text files, making reparsing preferable to cache invalidation and dual truth.

## Lock-Screen and Privacy Boundary

Capture may set Android's show-when-locked behavior where permitted. It exposes only the active blank/recovery capture surface. Review, previous days, projects, settings, folder selection, and existing note content require keyguard dismissal.

SideNote stores locally:

- The user-selected folder permission.
- Configuration and onboarding flags.
- At most one temporary unsaved recovery draft.

It does not store or transmit analytics, advertising identifiers, note databases, project indexes, or audio recordings. Online speech fallback sends audio to the device's configured recognition provider for processing and is not described as end-to-end private. SideNote itself has no backend.

## Error Handling and Recovery

- **Folder permission lost:** preserve the temporary draft, do not claim success, and request folder recovery after unlock.
- **File write failure:** keep the draft and allow retry; never clear it until a confirmed write.
- **Process death:** restore the private recovery draft on the next Capture launch.
- **Speech unavailable:** keep typed capture fully functional and retain usable transcript text.
- **Malformed Markdown:** render unrecognized material read-only and avoid broad rewrites.
- **Simultaneous save signals:** serialize through the idempotent save gate.
- **Project command ambiguity:** leave text unchanged unless it matches the explicit bilingual command grammar.
- **Notification unavailable:** preserve capture and review behavior; surface the limitation only in setup/settings.

## Accessibility

- Maintain at least WCAG 4.5:1 contrast for normal text.
- Support system font scaling without truncating note bodies or dates.
- Give checkboxes checked semantics and expandable rows expanded/collapsed semantics.
- Give every icon an accessible label and at least a 48 dp touch target.
- Announce recording, recoverable error, saved, and discarded states without repeatedly interrupting dictation.
- Respect reduced-motion and system haptic preferences.
- Provide non-gesture previous/next-day controls.
- Keep dates/timestamps directionally isolated and test mixed English/Hebrew accessibility output.

## Verification Strategy

Automated unit and instrumentation tests cover:

- Daily filename and header creation, append ordering, multiline formatting, and midnight rollover.
- Checkbox parsing and targeted `[ ]`/`[x]` rewrites without unrelated-file damage.
- English, Hebrew, mixed bidirectional content, Unicode project names, timestamps, and punctuation.
- Typed plus partial/final speech merging without loss of manual edits.
- On-device-first recognition selection and single online-fallback attempt.
- Explicit English/Hebrew voice-tag commands, multiple tags, and non-command speech.
- Project discovery and cross-day aggregation derived only from Markdown.
- Idempotency under concurrent completion signals.
- Temporary draft recovery and deletion after save/discard.
- Folder permission loss and failed-write recovery.
- Notification count, removal at zero, and Review action routing.
- Lock-state routing, semantic labels, reduced motion, and large font layouts.

The build must pass unit tests, Android lint, and a debug assemble task with available local tooling.

A physical Pixel 8 acceptance pass must verify:

1. Quick Tap launches Capture from normal use and, where Pixel/Android permits, from the lock screen.
2. Listening begins immediately after prior setup.
3. Screen-off/power-button behavior saves once.
4. Stable face-down behavior saves once without false positives during ordinary holding/typing.
5. A second Quick Tap delivers a repeated launch and saves; if the Pixel launcher does not deliver it, documentation records this platform limitation without adding accessibility-service privileges.
6. Review/history/projects remain protected behind unlock.
7. English and Hebrew on-device recognition and permitted online fallback behave on the configured device.

## MVP Acceptance Criteria

The MVP is complete when a configured Pixel 8 user can Quick Tap, immediately speak or type in English or Hebrew, explicitly add project tags by typing or voice, finish using the approved physical interactions, and find exactly one timestamped unchecked entry in the correct daily Markdown file. Review must change that file's checkbox directly, browse prior days, aggregate `@ProjectName` entries across days, and maintain the unprocessed notification without any note database, retained audio, account, analytics, or AI categorization.
