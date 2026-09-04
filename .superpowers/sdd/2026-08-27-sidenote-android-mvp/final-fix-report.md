# Final cohesive fix wave report

Date: 2026-09-04
Reviewed base: `4f5b405`
Scope: all 13 findings and the three required minor improvements in `final-fix-brief.md`
Status: implemented and automated gates complete; physical Pixel acceptance remains pending

## Evidence provenance

This session recovered an interrupted, uncommitted fix wave containing production changes and regression tests but no report. The entire inherited diff was inspected before completion. For inherited work, the original RED console output was no longer available; the RED statements below are therefore explicit reconstructions from the reviewed base behavior, the authoritative finding, and the regression that now covers it. They are not represented as newly witnessed failures.

The recoverable task record did retain several later, directly observed REDs while hardening the wave:

- a new-target SAF rename that mutated and then threw was incorrectly classified as a pre-write failure until `renameFailureAfterCreatingANewTargetIsUncertainRatherThanAFalseRejection` was added;
- immediate completion could miss a temporary-mirror failure, and a throwing recovery clear could incorrectly end as `Discarded`;
- a pending debounced recovery write could resurrect a draft after confirmed completion until `cancelPending` was introduced;
- the first clean connected pass exposed Activity event duplication and a transient provider lookup between sibling renames; focused fixes removed the duplicate mirror flush and made the independent fixture tolerate only that transient missing-target interval;
- the final clean attempt exposed two synchronization failures: stale text selection during IME injection and an off-main fake keyguard callback. The exact two cases passed 2/2, the two complete classes passed 19/19, and the subsequent full device run passed 81/81.

All production behavior is still asserted by exact outcomes; the synchronization changes do not loosen literal text, exactly-once repository scans, pre-unlock zero-read assertions, or activity privacy assertions.

## Finding resolutions

### 1. Graceful speech finalization

`SpeechControl.finish` now differs from cancellation. Completion stops acquisition, places the coordinator in `Finalizing`, accepts the current recognizer's final/error callback for a bounded 1,500 ms window, merges the terminal owned span, and only then freezes and persists. Timeout cancels and destroys the recognizer, and stale callbacks remain generation-gated. Typing, voice-off, discard, and terminal capture still reject late speech.

Reconstructed RED: base completion invalidated the listener and destroyed recognition immediately, so completion before a first partial could save blank text and completion after a partial could miss a corrected final.
GREEN: `completionBeforeFirstPartialKeepsTerminalSpeech`, `completionUsesCorrectedFinalInsteadOfFrozenPartial`, `finishPortSeesFinalizingStateBeforeReentrantCallback`, `gracefulFinishWaitsForTerminalResultAndDoesNotDestroyEarly`, and `gracefulFinishTimesOutAndRejectsLateFinalWithoutOnlineRestart`.

### 2. Unreadable recovery is not empty

Recovery read failure now maps to `RecoveryUnreadable`, a distinct non-empty/error workflow. Typing, debounce/flush, and blank completion cannot replace or clear unknown recovery bytes. The user must either retry a successful read or deliberately discard; a failed discard clear remains retryable and does not claim success.

Reconstructed RED: base mapped `ReadFailure` to no draft, allowing an empty completion or new typing to clear/overwrite the only recovery artifact.
GREEN: `unreadableRecoveryIsNeverClearedByBlankCompletionOrOverwrittenByTyping`, `failedRecoveryClearDoesNotClaimDiscardAndCanBeRetried`, and `discardCanRetryAfterRecoveryClearFails`.

### 3. Notification-independent Review

The manifest keeps Capture as the sole exported launcher Activity and declares a static `Review SideNote` long-press shortcut in `res/xml/shortcuts.xml`. Its explicit REVIEW action enters through Capture, performs the same unlock gate before protected dependencies or data are read, routes to non-exported Main, and does not initialize capture/voice. Normal launch and Quick Tap still open Capture, with no second launcher icon or capture chrome.

Reconstructed RED: no exported route could open Review when a notification was absent or permission was denied.
GREEN: `staticReviewShortcutIsAvailableWithoutNotificationsOrAnyNotes`, `lockedReviewShortcutRecreationAndRepeatedIntentsLoadNothingUntilUnlock`, and `lockedNotificationIntentLoadsNothingThenRoutesOnceAfterUnlockAcrossRecreationAndRepeats`. The routing class also closes indirectly launched Activities so repeated tests cannot inherit another test's Main instance.

### 4. Utterance continuation

The Android speech engine now starts subsequent utterances while capture remains active and voice-enabled, inserts a separator where required, treats ordinary silence/no-match as a restartable lifecycle event, and uses session generations to reject callbacks from replaced recognizers. Pause, typing, voice-off, discard, completion, and terminal state cancel pending restarts.

Reconstructed RED: the first final destroyed the recognizer while the UI remained voice-on, leaving later speech unobserved.
GREEN: `twoUtterancesContinueWithASeparatorAndStaleCallbacksAreIgnored`, `normalSilenceRestartsWithoutPermanentUnavailableState`, `pendingUtteranceRestartCannotSurviveTypingOffDiscardCompletionOrPause`, and the existing literal voice/typing end-to-end capture.

### 5. Recognition package visibility

`AndroidManifest.xml` now declares a `<queries>` intent for `android.speech.RecognitionService`. Discovery remains safe when no provider is installed and makes no emulator-specific provider assumption.

Reconstructed RED: the base manifest had no visibility declaration, so Android package filtering could hide recognition services from the support path.
GREEN: `recognitionServiceDiscoveryIsSafeWhenAProviderIsPresentOrAbsent`, plus merged-manifest compilation in the clean build.

### 6. Bilingual readiness

Speech availability now distinguishes languages already installed for on-device use from languages that are merely downloadable. Capture starts locally only when the required bilingual contract is actually ready. A locally unsupported language can use one default-recognizer attempt only when the existing online-fallback consent allows it; otherwise the session remains typed-only. `TypedOnly` never starts recognition and typing remains usable.

Reconstructed RED: downloadable languages were treated as installed, `TypedOnly` was ignored by capture startup, and `LanguageNotSupported` did not reach the consented fallback path.
GREEN: `installedOnlyLanguagesAreReadyButDownloadableOnlyAreNot`, `localUnsupportedLanguageCanUseTheConsentedOnlinePath`, `typedOnlySupportNeverStartsCaptureRecognitionAndTypingRemainsAvailable`, and the speech-intent contract tests.

### 7. Cancellation at the I/O return boundary

Capture recovery reconciliation now belongs to a process-scoped `CaptureRecoveryHandoff`. It mirrors the exact draft, appends under the shared completion transaction, and clears only after confirmed success even if the host ViewModel is cleared after the underlying write and before the dispatcher returns. New capture sessions queue behind the same handoff. UI work remains cancellable; there is no unbounded `NonCancellable` block.

Reconstructed RED: cancellation of the ViewModel-owned `withContext(IO)` return could discard a committed result and leave recovery available for a duplicate retry.
GREEN: `clearingHostAfterWriteBeforeIoReturnStillReconcilesRecovery` and `terminalSpeechDuringCompletionReachesCoordinatorWithoutEventMutexDeadlock`.

### 8. Recovery persistence failures

`RecoveryDraftWriter` contains debounce and flush exceptions, reports mirror health to the in-memory capture state, and can cancel a pending debounce before clear. Explicit Markdown completion is still allowed when the temporary mirror fails; confirmed Markdown success clears recovery, while Markdown failure/uncertainty leaves the in-memory draft visible with combined accessible guidance. A finishing host transfers flush ownership to the process scope before awaiting UI feedback.

Reconstructed RED: debounce/flush exceptions escaped the writer and could prevent a writable SAF completion; a delayed write could also recreate recovery after clear. Directly observed REDs are listed under evidence provenance.
GREEN: `throwingDebounceAndFlushAreRecoverableRatherThanUnhandled`, `cancelPendingDropsTheDebouncedWriteWithoutPersistingIt`, `failedTemporaryMirrorDoesNotPreventExplicitMarkdownCompletion`, `immediateCompletionSurfacesMirrorFailureWhenMarkdownAlsoCannotBeSaved`, `finishingStopFlushSurvivesViewModelClearing`, and the combined polite-error assertions in `AccessibilityAndBidiTest`.

### 9. External Markdown

The codec excludes checkbox-looking text inside backtick fences, tilde fences, unclosed fences, and HTML comments from editable task recognition. Review retains each daily file's complete original source and shows it read-only after unlock, including prose-only or malformed files. Checkbox updates still operate on the recognized source range and preserve all other bytes.

Reconstructed RED: fenced examples were editable while unrecognized prose disappeared from Review.
GREEN: `fencedAndHtmlExampleTasksAreNotEditableAndValidCheckboxPreservesTheirBytes`, `unclosedFenceCannotExposeTasksAndForgedFenceSourceCannotBeRewritten`, `proseOnlyAndMalformedDailyFilesRetainTheirOriginalReadOnlySource`, and `proseOnlyMarkdownIsVisibleAsReadOnlySourceContent`.

### 10. Review re-entry freshness

Main refreshes Review after protected re-entry, resume, and reused unlock without duplicating the initial scan. `ReviewViewModel` serializes repository access, coalesces overlapping refresh requests, and waits for a checkbox mutation before loading its result.

Reconstructed RED: returning to an existing Main refreshed permission state only, leaving externally added, edited, or removed Markdown stale.
GREEN: `oneCoalescedReentryRefreshLoadsExternalAddEditAndDelete`, `reentryRefreshWaitsForAnOverlappingCheckboxWriteAndLoadsItsResultOnceSafe`, and `resumedExistingReviewRefreshesExternalAddEditAndDeleteWithReusedUnlock`.

### 11. Approved discovery affordances

After explicit unlock, Capture derives project suggestions from current Markdown and replaces only the active `@prefix`, retaining unrelated text and valid selection/composition state. Locking clears suggestions and prevents the history scan. Review now includes a scrollable 120-day date browser and horizontal day swipe while retaining accessible Previous/Next buttons and source-day navigation.

Reconstructed RED: Capture had no project suggestions and Review exposed only single-step buttons rather than the approved browser/swipe affordances.
GREEN: `projectSuggestionsNeverReadHistoryUntilUnlockedAndRefreshFromMarkdownSource`, `markdownDerivedSuggestionReplacesTheActivePrefixWithoutLosingTextFieldState`, `longDateBrowserAndHorizontalSwipeWorkAlongsideExplicitDayButtons`, and `fontScaleTwoWrapsAllChipsAndPreservesEditingSelectionAndComposition`. The last test waits for the requested selection to reach `TextFieldValue` before injecting the literal suffix, preventing IME commands from using the intentionally retained earlier selection.

### 12. Settings errors are visible

Main's persistence message now reaches Settings. The screen displays monochrome `LiveRegionMode.Polite` feedback and successful retry clears the prior failure instead of leaving a stale warning.

Reconstructed RED: the navigation host passed `mainState.message` only to Onboarding.
GREEN: `failedFolderRepairAndConsentWritesStayVisibleUntilASuccessfulRetry` and the Settings screen accessibility assertions.

### 13. SAF uncertain writes and false claims

The store no longer opens the authoritative target with truncating `wt`. It requires provider create+rename capabilities, creates a unique app-owned sibling stage, writes and verifies replacement bytes there, revalidates the target identity and expected content, renames an existing target to an app-owned backup, installs the stage, and verifies the installed bytes before cleanup. App artifacts encode a private owner token, transaction id, target, expected hash, and replacement hash. Recovery considers only verified owned artifacts, restores an owned backup only when the target is absent, never overwrites a conflicting target, and filters artifacts from note/project/status scans.

Outcomes now distinguish confirmed success, pre-mutation failure/conflict, and post-mutation `Uncertain`. Capture and Review retain recovery/context and avoid the false promise that the file was unchanged when a provider may have mutated it.

Reconstructed RED: base `wt` could truncate before throwing while UI and README claimed unchanged/concurrency-safe behavior. Directly observed rename-after-mutation RED is listed under evidence provenance.
GREEN: `stageWriteFailureLeavesExistingTargetUntouched`, `missingRenameCapabilityFailsClosedBeforeChangingOriginal`, `renameFailureAfterOriginalMutationIsUncertainAndNextReadRestoresOriginal`, `renameFailureAfterCreatingANewTargetIsUncertainRatherThanAFalseRejection`, `providerThrowAfterReplacementRenameRemainsUncertainButRecoveryKeepsReplacement`, `uncertainPostWriteOutcomeIsPreservedForCaptureAndCheckboxCallers`, and `uncertainCheckboxWriteDoesNotPromiseThatMarkdownWasUnchanged`.

## Required minor improvements

- `actualReviewAtFontScaleTwoHasIsolatedTimeExpandableBodyAndButtonDayNavigation` now asserts that the recreated Activity's effective `resources.configuration.fontScale` is exactly `2f`; focused device result: 1/1 passed in 25s.
- `delayedAppendCrossingMinuteBoundaryReportsTheCommittedAttemptTime` advances the clock across a minute while I/O is delayed and verifies the saved announcement uses the captured commit-attempt time.
- README's literal multi-capture example now has the required blank separator between task blocks.

## Verification

Environment:

- JDK 17: `C:\Users\Ido\scoop\apps\temurin17-jdk\current`
- Android SDK: `C:\Users\Ido\Android\Sdk`
- AVD: `Pixel_8_API_37`, Android 17 / API 37
- emulator fingerprint: `google/sdk_gphone64_x86_64/emu64xa:17/CE2A.260420.019/15611780:userdebug/dev-keys`

Commands and results:

1. `gradlew.bat clean testDebugUnitTest lintDebug assembleDebug connectedDebugAndroidTest`
   - all 81 tasks executed from clean output;
   - JVM suite, lint analysis, and APK assembly passed;
   - 186/186 JVM tests passed;
   - device phase found the two synchronization REDs described above, so the combined invocation exited 1 after 8m 39s at 79/81.
2. Exact two corrected device tests: 2/2 passed, `BUILD SUCCESSFUL in 31s`.
3. Complete affected classes (`AccessibilityAndBidiTest`, `ReviewUnlockRoutingTest`): 19/19 passed, `BUILD SUCCESSFUL in 2m 51s`.
4. `gradlew.bat :app:connectedDebugAndroidTest`: 81/81 passed, 0 failed, 0 errors, 0 skipped, `BUILD SUCCESSFUL in 9m 13s`.
5. `gradlew.bat :app:lintDebug`: passed in 14s; XML contains 0 errors and 16 warnings. No baseline or suppression was added.
6. The final added effective-font-scale assertion passed focused 1/1 in 25s. This focused run replaced the ignored connected-test XML after the full-run counts had been recorded; it did not change production or the APK.

Final automated counts: **186 JVM + 81 full device**, all passing in their final applicable runs. The current debug APK is `app/build/outputs/apk/debug/app-debug.apk`, 33,711,761 bytes, SHA-256:

```text
1EE0297A074DB779A0F4BBB41C41022FC05FB697F63D2F11679A757135BFBDD2
```

Official primary references used to check platform boundaries:

- Android static shortcuts: <https://developer.android.com/develop/ui/views/launch/shortcuts/creating-shortcuts>
- Android package visibility declarations: <https://developer.android.com/training/package-visibility/declaring>
- `SpeechRecognizer` support/download/finalization APIs: <https://developer.android.com/reference/android/speech/SpeechRecognizer>
- Storage Access Framework document-provider contract: <https://developer.android.com/guide/topics/providers/document-provider>
- `DocumentsContract.Document` capability flags: <https://developer.android.com/reference/android/provider/DocumentsContract.Document>
- Kotlin coroutine cancellation: <https://kotlinlang.org/docs/cancellation-and-timeouts.html>

## Residual limitations and acceptance boundary

- Generic SAF still has no cross-provider compare-and-swap or universal atomic-replace guarantee. An external writer can race after SideNote's last validation, and a provider can misreport or violate create/rename behavior. SideNote therefore fails closed when required capabilities are absent, reports ambiguous post-mutation outcomes as uncertain, and preserves verified owned stage/backup plus app-private recovery material for retry/inspection. README states this limit explicitly.
- Providers lacking the required sibling create/rename behavior are intentionally unsupported for writes rather than being silently downgraded to truncation.
- The APK is a debug build, not a signed production release.
- Only the AVD was available. Physical Pixel acceptance is **0/7 executed, 7 NOT RUN**. Quick Tap delivery, real keyguard frames, OEM speech behavior/accuracy, sensor false positives, TalkBack speech, and haptic perceptibility remain physical checks.
- Lint has 16 non-blocking warnings (dependency/tool updates, missing app icon, version-catalog suggestions, modifier ordering, and a SharedPreferences KTX suggestion); there are no lint errors.
