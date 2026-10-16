package com.sidenote.app.review.ui

import android.net.Uri
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import android.graphics.Color
import android.view.WindowInsets
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.sidenote.app.AppContainer
import com.sidenote.app.MainActivity
import com.sidenote.app.MainDependencies
import com.sidenote.app.SideNoteApplication
import com.sidenote.app.capture.CaptureDependencies
import com.sidenote.app.capture.SpeechAvailability
import com.sidenote.app.capture.SpeechEngine
import com.sidenote.app.data.documents.AppendResult
import com.sidenote.app.data.documents.DocumentRepository
import com.sidenote.app.data.documents.UpdateResult
import com.sidenote.app.data.markdown.EntrySource
import com.sidenote.app.data.markdown.MarkdownCodec
import com.sidenote.app.data.markdown.ParsedDailyFile
import com.sidenote.app.data.settings.AppSettings
import com.sidenote.app.data.settings.SettingsRepository
import com.sidenote.app.privacy.LockState
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReviewNavigationTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private lateinit var application: SideNoteApplication
    private lateinit var originalContainer: AppContainer
    private lateinit var container: NavigationContainer
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun installContainer() {
        application = ApplicationProvider.getApplicationContext()
        originalContainer = application.container
        container = NavigationContainer()
        application.installContainerForTesting(container)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitForIdle()
    }

    @After
    fun restoreContainer() {
        scenario?.close()
        application.installContainerForTesting(originalContainer)
    }

    @Test
    fun appLauncherOpensReviewAndCaptureHasItsOwnShortcut() {
        val launcher = application.packageManager.getLaunchIntentForPackage(application.packageName)
        assertThat(launcher?.component?.className).isEqualTo(MainActivity::class.java.name)
        val shortcuts = application.getSystemService(android.content.pm.ShortcutManager::class.java)
            .manifestShortcuts
        assertThat(shortcuts.single { it.id == "capture" }.intent?.component?.className)
            .isEqualTo("com.sidenote.app.capture.CaptureActivity")
    }

    @Test
    fun launchingMainAgainFromSettingsReturnsToReview() {
        compose.onNodeWithContentDescription("Settings").performClick()
        scenario!!.onActivity { activity ->
            activity.startActivity(android.content.Intent(activity.intent)
                .putExtra("review_relaunch", true)
                .setFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                    android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
        compose.waitUntil(timeoutMillis = 5_000) {
            var delivered = false
            scenario!!.onActivity { delivered = it.intent.getBooleanExtra("review_relaunch", false) }
            delivered
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Dates").assertIsDisplayed()
    }

    @Test
    fun configuredMainActivityRoutesFromReviewToSettingsAndPersistsVoiceToggle() {
        compose.onNodeWithContentDescription("Dates").assertIsDisplayed()
        compose.onNodeWithText("Production wiring").assertIsDisplayed()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithContentDescription("Settings").assertIsDisplayed().assertIsSelected()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "review-settings-selected.png")
            .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        compose.onNodeWithText("Voice on at launch").assertIsOn().performClick()

        compose.waitUntil(timeoutMillis = 5_000) {
            container.settings.value.voiceOnAtLaunch.not()
        }
        assertThat(container.settings.value.voiceOnAtLaunch).isFalse()
    }

    @Test
    fun settingsSlidesInAndSystemBackSlidesToTheSelectedDay() {
        compose.onNodeWithContentDescription("Open date 2026-08-26").performClick()
        compose.onNodeWithText("Earlier note @Home").assertIsDisplayed()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.mainClock.advanceTimeBy(80)
        compose.onNodeWithText("Earlier note @Home").assertDoesNotExist()
        compose.onNodeWithText("Earlier note @Home", useUnmergedTree = true).assertExists()
        val moving = compose.onNodeWithText("Voice on at launch").getUnclippedBoundsInRoot().left.value
        compose.mainClock.advanceTimeBy(300)
        val settled = compose.onNodeWithText("Voice on at launch").getUnclippedBoundsInRoot().left.value
        assertThat(moving).isGreaterThan(settled)
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.mainClock.advanceTimeBy(80)
        compose.onNodeWithText("Voice on at launch").assertDoesNotExist()
        compose.onNode(
            hasClickAction() and hasAnyDescendant(hasText("Voice on at launch")),
            useUnmergedTree = true,
        ).performClick()
        compose.runOnIdle { assertThat(container.settings.value.voiceOnAtLaunch).isTrue() }
        compose.onNodeWithText("Earlier note @Home").assertExists()
        val returning = compose.onNodeWithText("Earlier note @Home").getUnclippedBoundsInRoot().left.value
        compose.mainClock.advanceTimeBy(300)
        val returned = compose.onNodeWithText("Earlier note @Home").getUnclippedBoundsInRoot().left.value
        assertThat(returning).isLessThan(returned)
        compose.onNodeWithText("Earlier note @Home").assertIsDisplayed()
        compose.onNodeWithContentDescription("Expand note 1").assertDoesNotExist()
        compose.mainClock.autoAdvance = true
    }

    @Test
    fun tabsExposeTheActiveDestinationAndNavigateDirectlyFromSettings() {
        compose.onNodeWithContentDescription("Dates").assertIsSelected()
        compose.onNodeWithContentDescription("Projects").performClick().assertIsSelected()
        compose.onNodeWithContentDescription("Settings").performClick().assertIsSelected()
        compose.onNodeWithContentDescription("Projects").performClick().assertIsSelected()
        compose.onNodeWithText("Home").assertIsDisplayed()
        compose.onNodeWithContentDescription("Settings").performClick().assertIsSelected()
        compose.onNodeWithContentDescription("Dates").performClick().assertIsSelected()
        compose.onNodeWithText("Production wiring").assertIsDisplayed()
    }

    @Test
    fun settingsBackPreservesSelectedProject() {
        compose.onNodeWithContentDescription("Projects").performClick()
        compose.onNodeWithText("Home").performClick()
        compose.onNodeWithText("All projects").assertIsDisplayed()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithContentDescription("Settings").assertIsSelected()
        compose.waitForIdle()
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("All projects").assertIsDisplayed()
        compose.onNodeWithText("Earlier note @Home").assertIsDisplayed()
    }

    @Test
    fun lockingDuringSettingsTransitionImmediatelyRemovesBothProtectedPages() {
        compose.onNodeWithText("Production wiring").assertIsDisplayed()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.mainClock.advanceTimeBy(80)
        compose.onNodeWithText("Production wiring", useUnmergedTree = true).assertExists()
        compose.runOnIdle { container.lock.locked.value = true }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("Unlock SideNote").assertIsDisplayed()
        compose.onNodeWithText("Production wiring", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("Voice on at launch", useUnmergedTree = true).assertDoesNotExist()
        compose.mainClock.autoAdvance = true
    }

    @Test
    fun settingsPaintsBehindSystemBars() {
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithContentDescription("Settings").assertIsDisplayed().assertIsSelected()
        compose.waitForIdle()
        var top = 0
        var bottom = 0
        scenario!!.onActivity { activity ->
            val insets = activity.window.decorView.rootWindowInsets
                .getInsets(WindowInsets.Type.systemBars())
            top = insets.top
            bottom = insets.bottom
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.waitForIdle(300, 3_000)
        val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(instrumentation.targetContext.getExternalFilesDir(null), "settings-first-use.png")
                .outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
            assertThat(top).isGreaterThan(0)
            assertThat(bottom).isGreaterThan(0)
            for (y in listOf(top - 2, screenshot.height - bottom / 2)) {
                val pixel = screenshot.getPixel(screenshot.width / 4, y)
                assertThat(Color.red(pixel)).isLessThan(40)
                assertThat(Color.green(pixel)).isLessThan(40)
                assertThat(Color.blue(pixel)).isLessThan(40)
            }
        } finally {
            screenshot.recycle()
        }
    }
}

private class NavigationContainer : AppContainer {
    val lock = NavigationLockState()
    val settings = NavigationSettingsRepository()
    private val repository = NavigationRepository()

    override fun captureDependencies(): CaptureDependencies = error("Capture is outside this test")
    override fun lockState(): LockState = lock

    override fun mainDependencies(): MainDependencies = MainDependencies(
        settings = settings,
        repository = repository,
        speechFactory = { NavigationSpeechEngine },
        ioDispatcher = Dispatchers.IO,
    )
}

private class NavigationSettingsRepository : SettingsRepository {
    private val mutableSettings = MutableStateFlow(
        AppSettings(
            treeUri = Uri.parse("content://notes/tree/SideNote"),
            voiceOnAtLaunch = true,
            voiceDisclosureAccepted = true,
            onboardingComplete = true,
        ),
    )
    val value: AppSettings get() = mutableSettings.value
    override val settings: Flow<AppSettings> = mutableSettings

    override suspend fun setTreeUri(treeUri: Uri?) {
        mutableSettings.value = value.copy(treeUri = treeUri)
    }

    override suspend fun setVoiceOnAtLaunch(enabled: Boolean) {
        mutableSettings.value = value.copy(voiceOnAtLaunch = enabled)
    }

    override suspend fun acceptVoiceDisclosureAndSetFallback(allowed: Boolean) {
        mutableSettings.value = value.copy(
            voiceDisclosureAccepted = true,
            onlineFallbackAllowed = allowed,
        )
    }

    override suspend fun setOnboardingComplete(complete: Boolean) {
        mutableSettings.value = value.copy(onboardingComplete = complete)
    }
}

private class NavigationRepository : DocumentRepository {
    private val olderDay = MarkdownCodec().parse(
        LocalDate.parse("2026-08-26"),
        "# 2026-08-26\n\n- [ ] **10:15** Earlier note @Home\n",
    )
    private val day = MarkdownCodec().parse(
        LocalDate.parse("2026-08-27"),
        "# 2026-08-27\n\n- [ ] **14:26** Production wiring\n",
    )

    override suspend fun append(text: String, committedAt: Instant, zone: ZoneId): AppendResult =
        error("Review navigation must not append")

    override suspend fun days(): List<ParsedDailyFile> = listOf(olderDay, day)

    override suspend fun setProcessed(
        source: EntrySource,
        fileName: String,
        expectedRaw: String,
        processed: Boolean,
    ): UpdateResult = UpdateResult.Success

    override suspend fun uncheckedCount(): Int = 1
}

private object NavigationSpeechEngine : SpeechEngine {
    override suspend fun support(): SpeechAvailability = SpeechAvailability.Available
    override suspend fun requestModelDownloads() = Unit
    override fun start(listener: SpeechEngine.Listener) = Unit
    override fun stop() = Unit
    override fun destroy() = Unit
}

private class NavigationLockState : LockState {
    override val locked = MutableStateFlow(false)
    override fun refresh() = Unit
    override fun requestDismissKeyguard(activity: android.app.Activity) = Unit
}
