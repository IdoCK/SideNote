# SideNote Android MVP Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the approved native Android MVP in which a Pixel 8 user can Quick Tap, immediately speak or type in English or Hebrew, save through physical completion signals, and review timestamped Markdown tasks by date or project.

**Architecture:** Use one Android application with separate `CaptureActivity` and unlock-protected `MainActivity`. Keep Markdown behind a `DocumentRepository`, capture behavior behind a pure `CaptureCoordinator`, Android speech and sensors behind small adapters, and Compose screens driven by immutable view state. Use manual dependency injection through `AppContainer`; do not add Room, Hilt, a backend, or an app-owned note index.

**Tech Stack:** Kotlin 2.3.21, Android Gradle Plugin 9.2.0, Gradle 9.4.1, JDK 17, compile/target SDK 37, min SDK 34, Jetpack Compose BOM 2026.08.00, Material 3, AndroidX DataStore, DocumentFile/Storage Access Framework, WorkManager, Kotlin coroutines, JUnit 4, Truth, Compose UI tests.

**Spec:** `docs/superpowers/specs/2026-08-27-sidenote-android-mvp-design.md`

## Global Constraints

- Native Kotlin and Jetpack Compose; optimize for Pixel 8 and require API 34 or newer.
- `YYYY-MM-DD.md` files in the user-selected SAF folder are the only durable note, project, and processed-state source of truth.
- No Room database, project/status index, account, analytics, AI categorization, raw-audio retention, or accessibility service.
- Capture may show over the lock screen; Review, Projects, Settings, folder selection, and existing note content require unlock.
- Support English (`en-US`), Hebrew (`he-IL`), mixed bidirectional text, and automatic language switching without a language selector.
- Voice is on at launch by default; typing turns it off; the blob toggles it; speech amplitude alone deforms the resting circle.
- Capture has no Save or Done button. Only non-empty drafts append, through one idempotent completion gate.
- Use stable dependencies only. The pinned toolchain follows the official AGP/Gradle matrix and Compose BOM current on 2026-08-27: [AGP versions](https://developer.android.com/build/releases/about-agp), [Compose BOM](https://developer.android.com/develop/ui/compose/bom).
- Every behavior task follows RED → GREEN → regression → commit. Never clear a draft or report success before the Markdown write completes.

## File Structure

```text
app/src/main/java/com/sidenote/app/
  SideNoteApplication.kt             # process-wide AppContainer and app coroutine scope
  AppContainer.kt                    # dependency graph interfaces and production wiring
  MainActivity.kt                    # unlock-protected Review host
  capture/CaptureActivity.kt         # lock-screen-safe capture host and repeated-intent entry
  capture/CaptureCoordinator.kt      # draft ownership, voice state, and idempotent save gate
  capture/CaptureViewModel.kt        # lifecycle adapter for coordinator and Android sources
  capture/SpeechEngine.kt            # platform-neutral speech contract
  capture/AndroidSpeechEngine.kt     # on-device-first bilingual recognizer and fallback
  capture/CompletionSignalSource.kt  # screen-off, face-down, background signal adapters
  capture/ui/CaptureScreen.kt        # black capture surface and 4 dp writing card
  capture/ui/VoiceBlob.kt            # flat circle and RMS-driven Canvas deformation
  data/markdown/MarkdownModels.kt     # recognized entries and stable source references
  data/markdown/MarkdownCodec.kt      # parse, append-format, targeted checkbox rewrite
  data/markdown/ProjectSyntax.kt      # Unicode @tokens and bilingual voice commands
  data/documents/TextDocumentStore.kt # provider-neutral daily-file primitives
  data/documents/SafTextDocumentStore.kt
  data/documents/DocumentRepository.kt
  data/documents/MarkdownDocumentRepository.kt
  data/settings/AppSettings.kt
  data/settings/SettingsRepository.kt
  data/settings/DataStoreSettingsRepository.kt
  data/recovery/RecoveryDraftStore.kt
  data/recovery/AtomicFileRecoveryDraftStore.kt
  review/ReviewViewModel.kt
  review/ui/ReviewScreen.kt
  review/ui/SettingsScreen.kt
  onboarding/OnboardingScreen.kt
  notification/UnprocessedNotificationCoordinator.kt
  notification/NotificationRefreshWorker.kt
  notification/BootReceiver.kt
  navigation/SideNoteNavHost.kt
  ui/theme/Color.kt, Theme.kt, Type.kt
app/src/main/res/
  values/strings.xml, themes.xml
  xml/backup_rules.xml, data_extraction_rules.xml
app/src/test/java/com/sidenote/app/...      # pure domain/coordinator tests
app/src/androidTest/java/com/sidenote/app/... # SAF, Activity, Compose, lock-routing tests
```

---

### Task 1: Reproducible Android Foundation

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `gradle.properties`
- Create: `gradle/libs.versions.toml`
- Create: `gradle/wrapper/gradle-wrapper.properties`
- Create: `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`
- Create: `app/build.gradle.kts`
- Create: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/java/com/sidenote/app/SideNoteApplication.kt`
- Create: `app/src/main/java/com/sidenote/app/AppContainer.kt`
- Create: `app/src/main/java/com/sidenote/app/MainActivity.kt`
- Create: `app/src/main/java/com/sidenote/app/ui/theme/Color.kt`
- Create: `app/src/main/java/com/sidenote/app/ui/theme/Theme.kt`
- Create: `app/src/main/java/com/sidenote/app/ui/theme/Type.kt`
- Create: `app/src/androidTest/java/com/sidenote/app/AppLaunchTest.kt`

**Interfaces:**
- Produces: `interface AppContainer` and `SideNoteApplication.container: AppContainer` for all later tasks.
- Produces: a debug APK whose first visible semantic heading is `Capture`.

- [ ] **Step 1: Install and record the Android toolchain**

Install Android Studio Quail 2 (`2026.1.2`) with its embedded JDK 17, Android SDK Platform 37, build-tools 37, platform-tools, and one API 37 Pixel 8 emulator. Add a short `Toolchain` section to `README.md` with the installed Android Studio, SDK, and JDK paths. This workstation currently has no `java`, `gradle`, `ANDROID_HOME`, or `ANDROID_SDK_ROOT` on its command path, so use Android Studio's embedded JDK and the Gradle wrapper rather than a global Gradle install.

- [ ] **Step 2: Create pinned Gradle configuration**

Use these exact version catalog entries:

```toml
[versions]
agp = "9.2.0"
kotlin = "2.3.21"
composeBom = "2026.08.00"
activityCompose = "1.13.0"
lifecycle = "2.10.0"
navigation = "2.9.6"
coreKtx = "1.17.0"
datastore = "1.1.7"
documentfile = "1.1.0"
work = "2.11.0"
coroutines = "1.10.2"
junit = "4.13.2"
truth = "1.4.5"
androidxJunit = "1.3.0"
espresso = "3.7.0"

[plugins]
androidApplication = { id = "com.android.application", version.ref = "agp" }
kotlinAndroid = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlinCompose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
```

Configure `namespace = "com.sidenote.app"`, `applicationId = "com.sidenote.app"`, `compileSdk = 37`, `minSdk = 34`, `targetSdk = 37`, Java/Kotlin target 17, Compose enabled, and instrumentation runner `androidx.test.runner.AndroidJUnitRunner`. Disable cloud backup because the selected SAF notes are external source files and the private recovery draft must not be backed up.

- [ ] **Step 3: Write the failing launch test**

```kotlin
@RunWith(AndroidJUnit4::class)
class AppLaunchTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun launchesIntoCapturePlaceholder() {
        rule.onNodeWithText("Capture").assertIsDisplayed()
    }
}
```

- [ ] **Step 4: Run the test and verify RED**

Run: `./gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.sidenote.app.AppLaunchTest`

Expected: FAIL because `MainActivity` does not yet expose a `Capture` node.

- [ ] **Step 5: Add the minimal application/container and Compose host**

```kotlin
interface AppContainer

class ProductionAppContainer(private val context: Context) : AppContainer

class SideNoteApplication : Application() {
    lateinit var container: AppContainer
        private set
    override fun onCreate() {
        super.onCreate()
        container = ProductionAppContainer(this)
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SideNoteTheme { Text("Capture") } }
    }
}
```

- [ ] **Step 6: Verify the foundation**

Run: `./gradlew.bat testDebugUnitTest lintDebug assembleDebug connectedDebugAndroidTest`

Expected: all tasks PASS; `app/build/outputs/apk/debug/app-debug.apk` exists.

- [ ] **Step 7: Commit**

```bash
git add README.md settings.gradle.kts build.gradle.kts gradle.properties gradle gradlew gradlew.bat app
git commit -m "build: scaffold native Android app"
```

### Task 2: Markdown Domain and Project Syntax

**Files:**
- Create: `app/src/main/java/com/sidenote/app/data/markdown/MarkdownModels.kt`
- Create: `app/src/main/java/com/sidenote/app/data/markdown/MarkdownCodec.kt`
- Create: `app/src/main/java/com/sidenote/app/data/markdown/ProjectSyntax.kt`
- Create: `app/src/test/java/com/sidenote/app/data/markdown/MarkdownCodecTest.kt`
- Create: `app/src/test/java/com/sidenote/app/data/markdown/ProjectSyntaxTest.kt`

**Interfaces:**
- Produces: `MarkdownCodec.createDailyFile(LocalDate): String`, `appendEntry(existing, capture): String`, `parse(date, text): ParsedDailyFile`, and `rewriteProcessed(text, source, processed): RewriteResult`.
- Produces: `ProjectSyntax.tokens(text): List<ProjectToken>` and `extractVoiceCommand(text, locale): VoiceCommandResult`.

- [ ] **Step 1: Write failing Markdown tests**

```kotlin
class MarkdownCodecTest {
    private val codec = MarkdownCodec()

    @Test fun createsHeaderAndFormatsMultilineCapture() {
        val result = codec.appendEntry(
            codec.createDailyFile(LocalDate.parse("2026-08-27")),
            NewCapture(LocalTime.of(14, 30), "@Home-Renovation First line\nSecond line")
        )
        assertThat(result).isEqualTo(
            "# 2026-08-27\n\n- [ ] **14:30** @Home-Renovation First line\n  Second line\n"
        )
    }

    @Test fun rewritesOnlyVerifiedCheckbox() {
        val source = "# 2026-08-27\n\n- [ ] **09:00** One\n\nUnrelated [ ] text\n"
        val parsed = codec.parse(LocalDate.parse("2026-08-27"), source)
        val result = codec.rewriteProcessed(source, parsed.entries.single().source, true)
        assertThat(result).isEqualTo(RewriteResult.Updated(
            "# 2026-08-27\n\n- [x] **09:00** One\n\nUnrelated [ ] text\n"
        ))
    }
}
```

Add literal cases for Hebrew, mixed bidi content, CRLF input, malformed tasks, duplicate timestamps, midnight date ownership, and an expected-source mismatch that must return `RewriteResult.Conflict` without changing text.

- [ ] **Step 2: Write failing project-syntax tests**

```kotlin
@Test fun extractsUnicodeTagsAndExplicitHebrewCommand() {
    assertThat(ProjectSyntax.tokens("@SideNote וגם @שיפוץ-הבית").map { it.display })
        .containsExactly("SideNote", "שיפוץ-הבית")
    assertThat(ProjectSyntax.extractVoiceCommand("תייג פרויקט שיפוץ-הבית לקנות צבע", Locale("he")))
        .isEqualTo(VoiceCommandResult("לקנות צבע", listOf("שיפוץ-הבית")))
}

@Test fun ordinaryAtWordIsNotACommand() {
    assertThat(ProjectSyntax.extractVoiceCommand("meet at noon", Locale.ENGLISH).projects).isEmpty()
}
```

- [ ] **Step 3: Run tests and verify RED**

Run: `./gradlew.bat testDebugUnitTest --tests "com.sidenote.app.data.markdown.*"`

Expected: compilation FAIL because the domain types do not exist.

- [ ] **Step 4: Implement immutable models and codec**

```kotlin
data class NewCapture(val time: LocalTime, val text: String)
data class EntrySource(val lineStart: Int, val rawTask: String, val ordinal: Int)
data class MarkdownEntry(
    val date: LocalDate,
    val time: LocalTime,
    val text: String,
    val processed: Boolean,
    val projects: List<ProjectToken>,
    val source: EntrySource,
)
data class ParsedDailyFile(val date: LocalDate, val entries: List<MarkdownEntry>, val raw: String)
sealed interface RewriteResult { data class Updated(val text: String) : RewriteResult; data object Conflict : RewriteResult }
```

Use the recognized-task regex `^- \[([ xX])] \*\*(\d{2}:\d{2})\*\*(?: (.*))?$`. Treat only indented continuation lines as part of the entry, preserve every unrelated byte, normalize only newly appended content to `\n`, and case-fold project keys with `lowercase(Locale.ROOT)` while retaining first spelling for display.

- [ ] **Step 5: Run domain tests**

Run: `./gradlew.bat testDebugUnitTest --tests "com.sidenote.app.data.markdown.*"`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/sidenote/app/data/markdown app/src/test/java/com/sidenote/app/data/markdown
git commit -m "feat: add Markdown and project domain"
```

### Task 3: SAF Document Repository

**Files:**
- Create: `app/src/main/java/com/sidenote/app/data/documents/TextDocumentStore.kt`
- Create: `app/src/main/java/com/sidenote/app/data/documents/SafTextDocumentStore.kt`
- Create: `app/src/main/java/com/sidenote/app/data/documents/DocumentRepository.kt`
- Create: `app/src/main/java/com/sidenote/app/data/documents/MarkdownDocumentRepository.kt`
- Create: `app/src/test/java/com/sidenote/app/data/documents/MarkdownDocumentRepositoryTest.kt`
- Create: `app/src/androidTest/java/com/sidenote/app/data/documents/SafTextDocumentStoreTest.kt`

**Interfaces:**
- Produces: `DocumentRepository.append(text, committedAt): AppendResult`, `days(): List<ParsedDailyFile>`, `setProcessed(source, expectedRaw, processed): UpdateResult`, and `uncheckedCount(): Int`.
- Consumes: `MarkdownCodec` from Task 2 and an exact persisted SAF tree `Uri`.

- [ ] **Step 1: Write a failing repository test with a real in-memory store**

```kotlin
class MarkdownDocumentRepositoryTest {
    private val store = InMemoryTextDocumentStore()
    private val repo = MarkdownDocumentRepository(store, MarkdownCodec(), Mutex())

    @Test fun concurrentAppendsCreateOneDailyFileAndPreserveOrder() = runTest {
        coroutineScope {
            launch { repo.append("first", Instant.parse("2026-08-27T13:00:00Z"), zone("America/New_York")) }
            launch { repo.append("second", Instant.parse("2026-08-27T13:01:00Z"), zone("America/New_York")) }
        }
        assertThat(store.names()).containsExactly("2026-08-27.md")
        assertThat(repo.days().single().entries.map { it.text }).containsExactly("first", "second").inOrder()
    }
}
```

Also test write failure leaves prior text untouched, permission loss returns `RepositoryError.PermissionLost`, malformed files stay readable, and targeted checkbox conflict performs no write.

- [ ] **Step 2: Run and verify RED**

Run: `./gradlew.bat testDebugUnitTest --tests "com.sidenote.app.data.documents.MarkdownDocumentRepositoryTest"`

Expected: compilation FAIL because repository contracts are absent.

- [ ] **Step 3: Implement provider-neutral repository**

```kotlin
interface TextDocumentStore {
    suspend fun listNames(): List<String>
    suspend fun read(name: String): String?
    suspend fun writeAtomically(name: String, expected: String?, replacement: String): WriteOutcome
}

interface DocumentRepository {
    suspend fun append(text: String, committedAt: Instant, zone: ZoneId): AppendResult
    suspend fun days(): List<ParsedDailyFile>
    suspend fun setProcessed(source: EntrySource, fileName: String, expectedRaw: String, processed: Boolean): UpdateResult
    suspend fun uncheckedCount(): Int
}
```

Guard every read-modify-write sequence with one repository `Mutex`. Compute filename/time inside the locked successful-save attempt. `writeAtomically` must verify current content equals `expected`; never overwrite an externally changed file.

- [ ] **Step 4: Implement SAF adapter and persisted permission helper**

Use `DocumentFile.fromTreeUri`, exact display-name lookup, `ContentResolver.openInputStream`, and `openOutputStream(uri, "wt")`. Persist `FLAG_GRANT_READ_URI_PERMISSION | FLAG_GRANT_WRITE_URI_PERMISSION` from `ACTION_OPEN_DOCUMENT_TREE`. Before truncation, re-read and compare to the expected text; if the provider cannot offer atomic replacement, return a conflict/failure and retain the draft rather than claiming success.

- [ ] **Step 5: Add the instrumentation contract test**

The instrumentation test must exercise the adapter against a test-owned document tree/provider fixture and assert exact UTF-8 round-trip, exact `.md` filename, and permission-denied mapping. Do not assert `DocumentFile` internals.

- [ ] **Step 6: Verify**

Run: `./gradlew.bat testDebugUnitTest connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.package=com.sidenote.app.data.documents`

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/sidenote/app/data/documents app/src/test/java/com/sidenote/app/data/documents app/src/androidTest/java/com/sidenote/app/data/documents
git commit -m "feat: add SAF Markdown repository"
```

### Task 4: Settings and Recovery Draft Persistence

**Files:**
- Create: `app/src/main/java/com/sidenote/app/data/settings/AppSettings.kt`
- Create: `app/src/main/java/com/sidenote/app/data/settings/SettingsRepository.kt`
- Create: `app/src/main/java/com/sidenote/app/data/settings/DataStoreSettingsRepository.kt`
- Create: `app/src/main/java/com/sidenote/app/data/recovery/RecoveryDraftStore.kt`
- Create: `app/src/main/java/com/sidenote/app/data/recovery/AtomicFileRecoveryDraftStore.kt`
- Create: `app/src/test/java/com/sidenote/app/data/settings/SettingsRepositoryTest.kt`
- Create: `app/src/test/java/com/sidenote/app/data/recovery/RecoveryDraftStoreTest.kt`

**Interfaces:**
- Produces: `SettingsRepository.settings: Flow<AppSettings>`, setters for tree URI, voice default, fallback consent, and onboarding.
- Produces: `RecoveryDraftStore.load/save/clear`; it holds at most one unsaved draft and is excluded from backup.

- [ ] **Step 1: Write failing default/settings tests**

```kotlin
@Test fun defaultsArePrivacySafeAndVoiceOn() = runTest {
    assertThat(repo.settings.first()).isEqualTo(
        AppSettings(treeUri = null, voiceOnAtLaunch = true, onlineFallbackAllowed = false, onboardingComplete = false)
    )
}

@Test fun fallbackTurnsOnOnlyAfterDisclosureAcceptance() = runTest {
    repo.acceptVoiceDisclosureAndSetFallback(true)
    assertThat(repo.settings.first().onlineFallbackAllowed).isTrue()
}
```

- [ ] **Step 2: Write failing recovery tests**

```kotlin
@Test fun recoveryKeepsOneDraftAndClearsOnlyExplicitly() = runTest {
    store.save(RecoveryDraft("typed טקסט", TextRange(9), voiceEnabled = false))
    assertThat(store.load()?.text).isEqualTo("typed טקסט")
    store.clear()
    assertThat(store.load()).isNull()
}
```

Also corrupt the recovery file and assert `load()` returns a typed `CorruptDraft` result, quarantines the bad file, and never crashes Capture.

- [ ] **Step 3: Run and verify RED**

Run: `./gradlew.bat testDebugUnitTest --tests "com.sidenote.app.data.settings.*" --tests "com.sidenote.app.data.recovery.*"`

Expected: compilation FAIL.

- [ ] **Step 4: Implement stores**

```kotlin
data class AppSettings(
    val treeUri: Uri?,
    val voiceOnAtLaunch: Boolean = true,
    val onlineFallbackAllowed: Boolean = false,
    val onboardingComplete: Boolean = false,
)

interface RecoveryDraftStore {
    suspend fun load(): RecoveryLoadResult
    suspend fun save(draft: RecoveryDraft)
    suspend fun clear()
}
```

Use Preferences DataStore only for configuration. Use `AtomicFile` plus UTF-8 JSON for the temporary recovery draft; write on debounced draft changes and force the final draft write on `onStop`. Add `recovery-draft.json` to backup/data-extraction exclusions.

- [ ] **Step 5: Verify and commit**

Run: `./gradlew.bat testDebugUnitTest --tests "com.sidenote.app.data.settings.*" --tests "com.sidenote.app.data.recovery.*"`

```bash
git add app/src/main/java/com/sidenote/app/data/settings app/src/main/java/com/sidenote/app/data/recovery app/src/test/java/com/sidenote/app/data/settings app/src/test/java/com/sidenote/app/data/recovery app/src/main/res/xml
git commit -m "feat: persist settings and recovery draft"
```

### Task 5: Capture Coordinator and Idempotent Save Gate

**Files:**
- Create: `app/src/main/java/com/sidenote/app/capture/CaptureCoordinator.kt`
- Create: `app/src/main/java/com/sidenote/app/capture/CaptureModels.kt`
- Create: `app/src/test/java/com/sidenote/app/capture/CaptureCoordinatorTest.kt`

**Interfaces:**
- Produces: `CaptureCoordinator.state: StateFlow<CaptureState>` and event methods `onUserEdit`, `onVoiceToggle`, `onSpeechPartial`, `onSpeechFinal`, `complete`, and `discard`.
- Consumes: `DocumentRepository`, `RecoveryDraftStore`, `ProjectSyntax`, `Clock`, `ZoneId`, and callback interfaces for speech stop, haptic confirmation, and closing Capture.

- [ ] **Step 1: Write failing state-transition tests**

```kotlin
@Test fun typingStopsVoiceAndPreservesSpeechText() = runTest {
    coordinator.start(voiceDefaultOn = true, recovered = null)
    coordinator.onSpeechPartial("hello")
    coordinator.onUserEdit(TextFieldValue("hello שלום", TextRange(10)))
    assertThat(coordinator.state.value.voiceEnabled).isFalse()
    assertThat(coordinator.state.value.draft.text).isEqualTo("hello שלום")
    assertThat(fakeSpeech.stopCalls).isEqualTo(1)
}

@Test fun partialSpeechReplacesOnlyOwnedSpan() = runTest {
    coordinator.start(true, RecoveryDraft("prefix ", TextRange(7), true))
    coordinator.onSpeechPartial("one")
    coordinator.onSpeechPartial("one two")
    assertThat(coordinator.state.value.draft.text).isEqualTo("prefix one two")
}
```

Add tests for default-off launch, blob off/on toggle, final speech clearing the owned range, English and Hebrew voice-tag commands removed from prose, and failed speech leaving usable partial text.

- [ ] **Step 2: Write the concurrent completion test**

```kotlin
@Test fun simultaneousSignalsAppendExactlyOnce() = runTest {
    coordinator.start(true, RecoveryDraft("one thought", TextRange(11), true))
    coroutineScope {
        listOf(ScreenOff, FaceDown, RepeatedLaunch, Backgrounded).forEach { signal ->
            launch { coordinator.complete(signal) }
        }
    }
    assertThat(fakeRepository.appends).containsExactly("one thought")
    assertThat(fakeRecovery.clearCalls).isEqualTo(1)
    assertThat(fakeCloser.closeCalls).isEqualTo(1)
}
```

Also assert blank completion closes without append, append failure preserves recovery and screen state, and discard never appends.

- [ ] **Step 3: Run and verify RED**

Run: `./gradlew.bat testDebugUnitTest --tests "com.sidenote.app.capture.CaptureCoordinatorTest"`

Expected: compilation FAIL.

- [ ] **Step 4: Implement state and ownership algorithm**

```kotlin
data class CaptureState(
    val draft: TextFieldValue = TextFieldValue(),
    val voiceEnabled: Boolean = true,
    val speechOwnedRange: TextRange? = null,
    val rms: Float = 0f,
    val status: CaptureStatus = CaptureStatus.Ready,
)

enum class CompletionSignal { ScreenOff, FaceDown, RepeatedLaunch, Backgrounded }

class CaptureCoordinator(
    private val repository: DocumentRepository,
    private val recovery: RecoveryDraftStore,
    private val saveMutex: Mutex,
    private val clock: Clock,
    private val zone: ZoneId,
    /* narrow output ports */
)
```

On partial speech, replace only `speechOwnedRange` and update that range. On user edit, accept the full `TextFieldValue`, clear the owned range, stop recognition, and set voice off. In `complete`, enter `saveMutex.withLock`; return immediately if a save already succeeded or is in flight. Clear recovery and close only after `AppendResult.Success`.

- [ ] **Step 5: Verify and commit**

Run: `./gradlew.bat testDebugUnitTest --tests "com.sidenote.app.capture.CaptureCoordinatorTest"`

```bash
git add app/src/main/java/com/sidenote/app/capture/CaptureCoordinator.kt app/src/main/java/com/sidenote/app/capture/CaptureModels.kt app/src/test/java/com/sidenote/app/capture/CaptureCoordinatorTest.kt
git commit -m "feat: coordinate capture and deduplicate saves"
```

### Task 6: Bilingual On-Device-First Speech Engine

**Files:**
- Create: `app/src/main/java/com/sidenote/app/capture/SpeechEngine.kt`
- Create: `app/src/main/java/com/sidenote/app/capture/AndroidSpeechEngine.kt`
- Create: `app/src/test/java/com/sidenote/app/capture/AndroidSpeechEnginePolicyTest.kt`
- Create: `app/src/androidTest/java/com/sidenote/app/capture/SpeechIntentTest.kt`

**Interfaces:**
- Produces: `SpeechEngine.support()`, `requestModelDownloads()`, `start(listener)`, `stop()`, and `destroy()`.
- Emits: partial/final text, normalized RMS `0f..1f`, detected language, unavailable/error state.

- [ ] **Step 1: Write failing retry-policy tests**

```kotlin
@Test fun localRecoverableFailureRetriesOnlineOnceWhenAllowed() = runTest {
    val policy = SpeechAttemptPolicy(onlineFallbackAllowed = true)
    assertThat(policy.nextAfter(Attempt.Local, SpeechFailure.Network)).isEqualTo(Attempt.Online)
    assertThat(policy.nextAfter(Attempt.Online, SpeechFailure.Network)).isNull()
}

@Test fun unsupportedBilingualRecognitionLeavesVoiceUnavailable() {
    assertThat(SpeechSupport.evaluate(local = setOf("en-US"), online = emptySet(), fallbackAllowed = false))
        .isEqualTo(SpeechAvailability.TypedOnly)
}
```

Cover network timeout/server/language-unavailable as recoverable, permission/client errors as non-retryable, fallback disabled, and exactly one retry.

- [ ] **Step 2: Run and verify RED**

Run: `./gradlew.bat testDebugUnitTest --tests "com.sidenote.app.capture.AndroidSpeechEnginePolicyTest"`

Expected: compilation FAIL.

- [ ] **Step 3: Implement Android recognition request**

```kotlin
private fun recognitionIntent(preferOffline: Boolean) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline)
    putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true)
    putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH, RecognizerIntent.LANGUAGE_SWITCH_BALANCED)
    putStringArrayListExtra(
        RecognizerIntent.EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES,
        arrayListOf("en-US", "he-IL"),
    )
}
```

Create the local attempt with `SpeechRecognizer.createOnDeviceSpeechRecognizer(context)` and the permitted fallback with `SpeechRecognizer.createSpeechRecognizer(context)`. Map `onRmsChanged` through an exponential smoother and clamp to `0f..1f`. Forward `onLanguageDetection` as diagnostic state only; never ask the user to choose.

- [ ] **Step 4: Implement support/model preparation**

Call `checkRecognitionSupport` for both constrained languages. When supported but not installed, call `triggerModelDownload` once per language from unlocked onboarding. If neither local nor permitted online recognition supports both, return `TypedOnly`; do not create a language picker.

- [ ] **Step 5: Add intent-level instrumentation assertions**

Extract an internal `SpeechIntentFactory` and assert every literal extra above, including allowed-language order and balanced sensitivity. The production change that must fail this test is removing or widening the bilingual constraint.

- [ ] **Step 6: Verify and commit**

Run: `./gradlew.bat testDebugUnitTest --tests "com.sidenote.app.capture.AndroidSpeechEnginePolicyTest" connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.sidenote.app.capture.SpeechIntentTest`

```bash
git add app/src/main/java/com/sidenote/app/capture/SpeechEngine.kt app/src/main/java/com/sidenote/app/capture/AndroidSpeechEngine.kt app/src/test/java/com/sidenote/app/capture/AndroidSpeechEnginePolicyTest.kt app/src/androidTest/java/com/sidenote/app/capture/SpeechIntentTest.kt
git commit -m "feat: add bilingual speech recognition"
```

### Task 7: Physical Completion Signals and Capture Activity

**Files:**
- Create: `app/src/main/java/com/sidenote/app/capture/CompletionSignalSource.kt`
- Create: `app/src/main/java/com/sidenote/app/capture/CaptureActivity.kt`
- Create: `app/src/main/java/com/sidenote/app/capture/CaptureViewModel.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Create: `app/src/test/java/com/sidenote/app/capture/FaceDownDetectorTest.kt`
- Create: `app/src/androidTest/java/com/sidenote/app/capture/CaptureActivityTest.kt`

**Interfaces:**
- Produces: `Flow<CompletionSignal>` from screen-off, debounced face-down, safe backgrounding, and `onNewIntent`.
- Consumes: Task 5 coordinator; `CaptureActivity` is `singleTop`, show-when-locked, and contains no Review path.

- [ ] **Step 1: Write failing face-down detector tests**

```kotlin
@Test fun emitsOnceAfterStableFaceDownWindow() = runTest {
    val detector = FaceDownDetector(threshold = -8.5f, releaseThreshold = -7.0f, debounce = 750.milliseconds)
    detector.onZ(-9.2f, 0.milliseconds)
    detector.onZ(-9.0f, 749.milliseconds)
    assertThat(detector.events).isEmpty()
    detector.onZ(-9.1f, 750.milliseconds)
    assertThat(detector.events).containsExactly(CompletionSignal.FaceDown)
}
```

Test cancellation when the device moves, hysteresis, no duplicate until release, and ordinary portrait/flat-face-up readings.

- [ ] **Step 2: Run and verify RED**

Run: `./gradlew.bat testDebugUnitTest --tests "com.sidenote.app.capture.FaceDownDetectorTest"`

Expected: compilation FAIL.

- [ ] **Step 3: Implement sources and activity contract**

Register `ACTION_SCREEN_OFF` dynamically while Capture is active. Sample `Sensor.TYPE_ACCELEROMETER` only during Capture. Arm background completion only after onboarding/permission activities return, so setup navigation cannot save. In `CaptureActivity`:

```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setShowWhenLocked(true)
    setTurnScreenOn(true)
    viewModel.start(intent)
}

override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    viewModel.complete(CompletionSignal.RepeatedLaunch)
}
```

Declare `android:launchMode="singleTop"`, `android:excludeFromRecents="true"`, and an exported launcher entry for `CaptureActivity`. Keep `MainActivity` non-exported except for explicit notification intents.

- [ ] **Step 4: Add Activity tests**

Launch Capture with a fake container; deliver a second intent and assert one coordinator completion. Simulate `ACTION_SCREEN_OFF` and assert the same. Assert no Review, Settings, date, or project semantics exist in Capture when keyguard state is locked.

- [ ] **Step 5: Verify and commit**

Run: `./gradlew.bat testDebugUnitTest --tests "com.sidenote.app.capture.FaceDownDetectorTest" connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.sidenote.app.capture.CaptureActivityTest`

```bash
git add app/src/main/AndroidManifest.xml app/src/main/java/com/sidenote/app/capture app/src/test/java/com/sidenote/app/capture/FaceDownDetectorTest.kt app/src/androidTest/java/com/sidenote/app/capture/CaptureActivityTest.kt
git commit -m "feat: add physical capture completion"
```

### Task 8: Capture Compose UI and Speech-Reactive Blob

**Files:**
- Create: `app/src/main/java/com/sidenote/app/capture/ui/CaptureScreen.kt`
- Create: `app/src/main/java/com/sidenote/app/capture/ui/VoiceBlob.kt`
- Create: `app/src/androidTest/java/com/sidenote/app/capture/ui/CaptureScreenTest.kt`
- Modify: `app/src/main/java/com/sidenote/app/capture/CaptureActivity.kt`
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:**
- Produces: `CaptureScreen(state, onTextChanged, onVoiceToggle, onDiscard)` and `VoiceBlob(enabled, rms, reducedMotion, onToggle)`.
- Consumes: immutable `CaptureState`; no Android speech or repository calls from composables.

- [ ] **Step 1: Write failing Compose behavior tests**

```kotlin
@Test fun captureHasNoSaveDoneOrListeningLabel() {
    compose.setContent { CaptureScreen(CaptureState(), {}, {}, {}) }
    compose.onNodeWithText("Save").assertDoesNotExist()
    compose.onNodeWithText("Done").assertDoesNotExist()
    compose.onNodeWithText("Listening", substring = true).assertDoesNotExist()
    compose.onNodeWithContentDescription("Voice input on").assertIsDisplayed().assertIsOn()
}

@Test fun typingDispatchesTextAndBlobToggleIsAccessible() {
    compose.onNodeWithText("Enter thought here…").performTextInput("שלום")
    assertThat(events.single()).isEqualTo(CaptureEvent.UserEdit("שלום"))
    compose.onNodeWithContentDescription("Voice input off").performClick()
    assertThat(events.last()).isEqualTo(CaptureEvent.ToggleVoice)
}
```

Add assertions for 4 dp card shape, logical start alignment under Hebrew and English, Discard visible only for non-empty text, 48 dp blob touch target, font scale 2.0, and no clipped content.

- [ ] **Step 2: Write failing blob geometry tests**

Expose a pure `BlobGeometry.from(rms, phase, enabled, reducedMotion)` function. Assert `rms == 0` produces a circle, disabled produces a circle with hollow paint, positive RMS changes control points within 0.96–1.08 scale, and reduced motion never changes geometry.

- [ ] **Step 3: Run and verify RED**

Run: `./gradlew.bat testDebugUnitTest --tests "*BlobGeometryTest" connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.sidenote.app.capture.ui.CaptureScreenTest`

Expected: FAIL because UI and geometry are absent.

- [ ] **Step 4: Implement the approved surface**

Use edge-to-edge `#050505`, a centered 112–144 dp Canvas, and an off-white text card at 80–88% width with exact `RoundedCornerShape(4.dp)`. Draw one filled circle when enabled/idle, one dim stroked circle when disabled, and a four-lobed cubic path only when smoothed RMS exceeds the speech threshold. Remove all interior bars, visible listening copy, Save, Done, history, and settings controls.

Use `BasicTextField` or an undecorated Material field with `TextDirection.Content`, `TextAlign.Start`, Noto/system fonts, and `@Project` chips above the card. Apply `Role.Switch`, `toggleableState`, content descriptions `Voice input on/off`, and a 48 dp minimum touch target to the blob.

- [ ] **Step 5: Verify UI and commit**

Run: `./gradlew.bat testDebugUnitTest connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.package=com.sidenote.app.capture.ui lintDebug`

```bash
git add app/src/main/java/com/sidenote/app/capture/ui app/src/main/java/com/sidenote/app/capture/CaptureActivity.kt app/src/androidTest/java/com/sidenote/app/capture/ui app/src/main/res/values/strings.xml
git commit -m "feat: build zero-thought capture UI"
```

### Task 9: Review, Projects, Settings, and Onboarding

**Files:**
- Create: `app/src/main/java/com/sidenote/app/review/ReviewViewModel.kt`
- Create: `app/src/main/java/com/sidenote/app/review/ReviewModels.kt`
- Create: `app/src/main/java/com/sidenote/app/review/ui/ReviewScreen.kt`
- Create: `app/src/main/java/com/sidenote/app/review/ui/SettingsScreen.kt`
- Create: `app/src/main/java/com/sidenote/app/onboarding/OnboardingScreen.kt`
- Create: `app/src/main/java/com/sidenote/app/navigation/SideNoteNavHost.kt`
- Modify: `app/src/main/java/com/sidenote/app/MainActivity.kt`
- Create: `app/src/test/java/com/sidenote/app/review/ReviewViewModelTest.kt`
- Create: `app/src/androidTest/java/com/sidenote/app/review/ui/ReviewScreenTest.kt`
- Create: `app/src/androidTest/java/com/sidenote/app/onboarding/OnboardingScreenTest.kt`

**Interfaces:**
- Produces: date/project projections derived from `DocumentRepository.days()` on every refresh.
- Produces: checkbox updates that call `setProcessed` with exact source identity and refresh after success/conflict.
- Consumes: Settings and speech-support preparation from Tasks 4 and 6.

- [ ] **Step 1: Write failing review-projection tests**

```kotlin
@Test fun projectsAggregateAcrossDaysNewestFirstWithoutCopies() = runTest {
    repository.days = listOf(day("2026-08-26", "@SideNote old"), day("2026-08-27", "@sidenote new"))
    viewModel.refresh()
    val project = viewModel.state.value.projects.single()
    assertThat(project.displayName).isEqualTo("SideNote")
    assertThat(project.entries.map { it.entry.text }).containsExactly("@sidenote new", "@SideNote old").inOrder()
    assertThat(project.entries.all { it.entry.source.rawTask.isNotBlank() }).isTrue()
}

@Test fun checkboxConflictReloadsInsteadOfOverwriting() = runTest {
    repository.nextUpdate = UpdateResult.Conflict
    viewModel.setProcessed(sourceEntry, true)
    assertThat(repository.daysCalls).isEqualTo(2)
    assertThat(viewModel.state.value.message).isEqualTo(ReviewMessage.FileChanged)
}
```

Test source-order dates, previous/next bounds, most-recent-unprocessed routing, processed rows remaining in place, and first-observed project spelling.

- [ ] **Step 2: Run and verify RED**

Run: `./gradlew.bat testDebugUnitTest --tests "com.sidenote.app.review.ReviewViewModelTest"`

Expected: compilation FAIL.

- [ ] **Step 3: Implement review state and navigation**

```kotlin
data class ProjectGroup(val key: String, val displayName: String, val entries: List<ProjectEntry>)
data class ReviewState(
    val days: List<ParsedDailyFile> = emptyList(),
    val selectedDate: LocalDate? = null,
    val projects: List<ProjectGroup> = emptyList(),
    val expanded: Set<EntrySource> = emptySet(),
    val message: ReviewMessage? = null,
)
```

Keep expansion state in memory only. Derive every project list from current parsed files; never persist it. Project checkbox actions carry the original filename/source reference back to `DocumentRepository`.

- [ ] **Step 4: Write failing Compose navigation/semantics tests**

Assert Dates/Projects tabs, explicit previous/next controls, 48 dp semantic checkboxes, expand/collapse state descriptions, isolated timestamps, checked strikethrough/subdued styling without reorder, project counts, and navigation from project result to its source day. Run the same fixture with Hebrew and font scale 2.0.

- [ ] **Step 5: Implement Review and Settings screens**

Use the approved monochrome visual system. Settings must expose exact controls for notes folder, `Voice on at launch`, online fallback, microphone/notification permission state, Quick Tap guide, and source-of-truth explanation. Folder change launches `ACTION_OPEN_DOCUMENT_TREE` only after unlock and never migrates old files.

- [ ] **Step 6: Implement three-step onboarding**

Step 1 persists the tree permission. Step 2 requests `RECORD_AUDIO` and `POST_NOTIFICATIONS`, displays the one-time privacy disclosure, records fallback consent, checks bilingual support, and requests supported model downloads. Step 3 shows the exact Pixel path: `Settings → System → Gestures → Quick Tap → Open app → SideNote`.

- [ ] **Step 7: Verify and commit**

Run these as separate commands so one instrumentation package filter cannot overwrite the other:

```powershell
./gradlew.bat testDebugUnitTest --tests "com.sidenote.app.review.*" lintDebug
./gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.package=com.sidenote.app.review
./gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.package=com.sidenote.app.onboarding
```

```bash
git add app/src/main/java/com/sidenote/app/review app/src/main/java/com/sidenote/app/onboarding app/src/main/java/com/sidenote/app/navigation app/src/main/java/com/sidenote/app/MainActivity.kt app/src/test/java/com/sidenote/app/review app/src/androidTest/java/com/sidenote/app/review app/src/androidTest/java/com/sidenote/app/onboarding
git commit -m "feat: add review projects settings and setup"
```

### Task 10: Persistent Unprocessed Notification and Unlock Routing

**Files:**
- Create: `app/src/main/java/com/sidenote/app/notification/UnprocessedNotificationCoordinator.kt`
- Create: `app/src/main/java/com/sidenote/app/notification/NotificationRefreshWorker.kt`
- Create: `app/src/main/java/com/sidenote/app/notification/BootReceiver.kt`
- Modify: `app/src/main/java/com/sidenote/app/AppContainer.kt`
- Modify: `app/src/main/java/com/sidenote/app/MainActivity.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Create: `app/src/test/java/com/sidenote/app/notification/UnprocessedNotificationCoordinatorTest.kt`
- Create: `app/src/androidTest/java/com/sidenote/app/notification/ReviewUnlockRoutingTest.kt`

**Interfaces:**
- Produces: `refresh()` that scans Markdown and posts/removes notification ID `1001`.
- Produces: unique WorkManager job `refresh-unprocessed-notes` for boot/recovery.
- Consumes: `DocumentRepository.uncheckedCount()`; never consumes a cached count.

- [ ] **Step 1: Write failing notification-policy tests**

```kotlin
@Test fun positiveCountPostsOngoingReviewNotification() = runTest {
    repository.count = 3
    coordinator.refresh()
    assertThat(notifier.last.title).isEqualTo("3 unprocessed notes")
    assertThat(notifier.last.ongoing).isTrue()
    assertThat(notifier.last.actions.single().title).isEqualTo("Review")
}

@Test fun zeroCountRemovesNotification() = runTest {
    repository.count = 0
    coordinator.refresh()
    assertThat(notifier.cancelledIds).containsExactly(1001)
}
```

Test denied notification permission as a non-fatal result and folder permission loss as a recoverable Settings state.

- [ ] **Step 2: Run and verify RED**

Run: `./gradlew.bat testDebugUnitTest --tests "com.sidenote.app.notification.*"`

Expected: compilation FAIL.

- [ ] **Step 3: Implement coordinator, worker, and boot receiver**

Create one low-importance channel `unprocessed_notes`. Use `setOngoing(true)`, `setOnlyAlertOnce(true)`, `setContentTitle("$count unprocessed notes")`, and an immutable/update-current `PendingIntent` targeting `MainActivity` with `EXTRA_OPEN_MOST_RECENT_UNPROCESSED = true`. Enqueue a unique one-time worker from `BOOT_COMPLETED`; refresh directly after every successful append or checkbox update.

- [ ] **Step 4: Implement unlock boundary**

Before rendering Review or honoring the notification destination, call `KeyguardManager.requestDismissKeyguard`. Until dismissal succeeds, render no note content. If Capture starts while onboarding is incomplete, require dismissal and route to onboarding; do not expose the selected folder or recovery draft on the lock-screen prompt.

- [ ] **Step 5: Add routing instrumentation tests**

Use an injectable `LockState` interface in `AppContainer`. With `locked = true`, assert the Review intent shows only an unlock gate and no note text. After emitting unlocked, assert the most recent day with an unchecked entry is selected. Assert Capture still exposes only its blank/recovery surface.

- [ ] **Step 6: Verify and commit**

Run: `./gradlew.bat testDebugUnitTest --tests "com.sidenote.app.notification.*" connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.sidenote.app.notification.ReviewUnlockRoutingTest`

```bash
git add app/src/main/java/com/sidenote/app/notification app/src/main/java/com/sidenote/app/AppContainer.kt app/src/main/java/com/sidenote/app/MainActivity.kt app/src/main/AndroidManifest.xml app/src/test/java/com/sidenote/app/notification app/src/androidTest/java/com/sidenote/app/notification
git commit -m "feat: add unprocessed notification and unlock gate"
```

### Task 11: End-to-End Hardening, README, and Pixel 8 Acceptance

**Files:**
- Create: `app/src/androidTest/java/com/sidenote/app/e2e/CaptureToReviewTest.kt`
- Create: `app/src/androidTest/java/com/sidenote/app/e2e/AccessibilityAndBidiTest.kt`
- Create: `app/src/test/java/com/sidenote/app/ArchitectureBoundaryTest.kt`
- Modify: `README.md`
- Create: `docs/pixel-8-acceptance.md`
- Modify: `.gitignore`

**Interfaces:**
- Verifies the assembled application as one system; introduces no new production subsystem.

- [ ] **Step 1: Write the failing end-to-end capture test**

```kotlin
@Test fun quickCaptureProducesOneUncheckedMarkdownEntryAndReviewProcessesIt() = runTest {
    launchCapture(fakeTree, now = "2026-08-27T18:26:00-04:00")
    enterTypedText("רעיון חדש @SideNote")
    emitCompletion(ScreenOff)
    assertTreeFile("2026-08-27.md").isEqualTo(
        "# 2026-08-27\n\n- [ ] **18:26** רעיון חדש @SideNote\n"
    )
    openReviewUnlocked()
    checkEntry("18:26")
    assertTreeFile("2026-08-27.md").contains("- [x] **18:26**")
}
```

Add one voice/typed merge case, one simultaneous completion case, one failed-write recovery case, and one cross-day project view case. The fixtures must inspect actual text documents rather than repository mocks.

- [ ] **Step 2: Add accessibility, bidi, and architecture boundary tests**

Test content descriptions and state semantics, TalkBack-friendly timestamp isolation, Hebrew/English/mixed content, font scale 2.0, reduced motion, and non-gesture day navigation. Add an architecture test that fails if production source references `androidx.room`, an HTTP client, analytics SDKs, or app-owned note/project/status database classes.

- [ ] **Step 3: Run focused tests and resolve only observed failures**

Run: `./gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.package=com.sidenote.app.e2e testDebugUnitTest --tests "com.sidenote.app.ArchitectureBoundaryTest"`

Expected before hardening: at least one fixture or integration assertion fails. Fix each observed production defect with its own RED/GREEN cycle; do not weaken literal Markdown expectations.

- [ ] **Step 4: Write user/developer README**

Document:

1. Android Studio Quail 2 / JDK 17 / SDK 37 setup and `./gradlew.bat` commands.
2. First-run folder choice and why Markdown remains after uninstall.
3. Pixel 8 Quick Tap: `Settings → System → Gestures → Quick Tap → Open app → SideNote`.
4. Lock-screen limitations, screen-off behavior, face-down debounce, and the need to verify repeated launch on the physical Pixel.
5. English/Hebrew automatic recognition, local-first behavior, transparent Android online fallback, and the fact that SideNote retains no audio.
6. Exact daily Markdown/task format and `@ProjectName` grammar.
7. Recovery behavior for permission loss and failed writes.

- [ ] **Step 5: Run the complete automated release gate**

Run:

```powershell
./gradlew.bat --stop
./gradlew.bat clean testDebugUnitTest lintDebug assembleDebug connectedDebugAndroidTest
```

Expected: exit 0; zero failing tests; lint has no errors; `app-debug.apk` exists. Record task counts and the APK SHA-256 in `docs/pixel-8-acceptance.md`.

- [ ] **Step 6: Execute the physical Pixel 8 acceptance checklist**

On a Pixel 8, record Android build number and verify all seven spec cases: normal/locked Quick Tap launch, default-on immediate listening, screen-off exactly-once save, stable face-down exactly-once save without ordinary-motion false positives, second Quick Tap repeated intent or documented launcher limitation, unlock-protected Review/Projects/Settings, and English/Hebrew/mixed utterances with permitted fallback. For each case record PASS/FAIL, reproduction steps, and resulting Markdown excerpt. Do not add accessibility-service privileges if repeated launch is unsupported.

- [ ] **Step 7: Final verification and commit**

Run: `git diff --check` and repeat `./gradlew.bat testDebugUnitTest lintDebug assembleDebug connectedDebugAndroidTest` after any acceptance fixes.

```bash
git add README.md .gitignore docs/pixel-8-acceptance.md app/src/test app/src/androidTest
git commit -m "test: complete SideNote MVP acceptance gate"
```

## Plan Self-Review

- **Spec coverage:** Tasks 2–3 cover Markdown authority and projects; Tasks 4–8 cover recovery, speech, physical completion, and Capture; Task 9 covers onboarding/review/settings; Task 10 covers notification and lock boundary; Task 11 covers accessibility, failure recovery, build, and physical Pixel acceptance.
- **Source-of-truth check:** No task introduces Room or durable copied note/project/status state. Project groups and notification counts always derive from parsed Markdown.
- **Type consistency:** `EntrySource`, `ParsedDailyFile`, `DocumentRepository`, `CaptureState`, `CompletionSignal`, `AppSettings`, and `AppContainer` are defined before their consumers and retain the same names/signatures across tasks.
- **Placeholder scan:** The plan contains no unresolved markers, deferred implementation, dynamic dependency, or generic “add tests/error handling” step. Every error path named by the spec has a concrete task and assertion.
- **Tooling reality:** Task 1 explicitly installs the missing local Android toolchain before any build claim. Task 11 requires both emulator automation and a separately recorded Pixel 8 pass.
