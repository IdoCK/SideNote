package com.sidenote.app.onboarding

import android.net.Uri
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.sidenote.app.MainActivity
import com.sidenote.app.data.settings.AppSettings
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OnboardingScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private var scenario: ActivityScenario<MainActivity>? = null

    @After
    fun closeActivity() {
        scenario?.close()
    }

    @Test
    fun setupIsExactlyThreeStepsAndFolderMustBeGrantedFirst() {
        var folderCalls = 0
        var continueCalls = 0
        val settings = AppSettings(treeUri = null)

        setContent {
            OnboardingScreen(
                step = OnboardingStep.Folder,
                settings = settings,
                folderLabel = null,
                microphoneGranted = false,
                notificationsGranted = false,
                speechPreparation = SpeechPreparationState.Idle,
                message = null,
                onChooseFolder = { folderCalls += 1 },
                onRequestPermissions = {},
                onAcceptVoiceDisclosure = {},
                onOnlineFallbackChange = {},
                onCheckSpeech = {},
                onDownloadModels = {},
                onContinue = { continueCalls += 1 },
            )
        }

        compose.onNodeWithText("Step 1 of 3").assertIsDisplayed()
        compose.onNodeWithText("Choose notes folder").performClick()
        compose.onNodeWithText("Continue").assertIsNotEnabled()
        compose.onNodeWithText("Language", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Step 4", substring = true).assertDoesNotExist()
        compose.runOnIdle {
            assertThat(folderCalls).isEqualTo(1)
            assertThat(continueCalls).isEqualTo(0)
        }
    }

    @Test
    fun voiceStepRequestsBothPermissionsAndRequiresDisclosureBeforeOnlineFallback() {
        var permissionCalls = 0
        var accepted: Boolean? = null
        var fallback: Boolean? = null
        var checks = 0
        var downloads = 0
        var settings by mutableStateOf(
            AppSettings(
                treeUri = Uri.parse("content://notes/tree/SideNote"),
                voiceDisclosureAccepted = false,
            ),
        )

        setContent {
            OnboardingScreen(
                step = OnboardingStep.Voice,
                settings = settings,
                folderLabel = "SideNote",
                microphoneGranted = false,
                notificationsGranted = false,
                speechPreparation = SpeechPreparationState.TypedOnly,
                message = null,
                onChooseFolder = {},
                onRequestPermissions = { permissionCalls += 1 },
                onAcceptVoiceDisclosure = { allowed ->
                    accepted = allowed
                    settings = settings.copy(
                        voiceDisclosureAccepted = true,
                        onlineFallbackAllowed = allowed,
                    )
                },
                onOnlineFallbackChange = { fallback = it },
                onCheckSpeech = { checks += 1 },
                onDownloadModels = { downloads += 1 },
                onContinue = {},
            )
        }

        compose.onNodeWithText("Step 2 of 3").assertIsDisplayed()
        compose.onNodeWithText("Microphone: Not allowed").assertIsDisplayed()
        compose.onNodeWithText("Notifications: Not allowed").assertIsDisplayed()
        compose.onNodeWithText("Allow microphone & notifications").performClick()
        compose.onNodeWithText(
            "Voice is processed on this phone when available. When necessary, Android's speech service may process it online.",
        ).assertIsDisplayed()
        compose.onNodeWithText("I understand").performClick()
        compose.onNodeWithText("Allow online voice recognition").assertIsOn()
        compose.onNodeWithText("English (US) and Hebrew", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Check English + Hebrew").performClick()
        compose.onNodeWithText("Download speech models").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertThat(permissionCalls).isEqualTo(1)
            assertThat(accepted).isTrue()
            assertThat(fallback).isNull()
            assertThat(checks).isEqualTo(1)
            assertThat(downloads).isEqualTo(1)
        }
    }

    @Test
    fun eitherSpeechPreparationOperationDisablesBothPreparationActions() {
        var preparation by mutableStateOf(SpeechPreparationState.Checking)
        setContent {
            OnboardingScreen(
                step = OnboardingStep.Voice,
                settings = AppSettings(
                    treeUri = Uri.parse("content://notes/tree/SideNote"),
                    voiceDisclosureAccepted = true,
                ),
                folderLabel = "SideNote",
                microphoneGranted = true,
                notificationsGranted = true,
                speechPreparation = preparation,
                message = null,
                onChooseFolder = {},
                onRequestPermissions = {},
                onAcceptVoiceDisclosure = {},
                onOnlineFallbackChange = {},
                onCheckSpeech = {},
                onDownloadModels = {},
                onContinue = {},
            )
        }

        compose.onNodeWithText("Check English + Hebrew").assertIsNotEnabled()
        compose.onNodeWithText("Download speech models").assertIsNotEnabled()

        compose.runOnIdle { preparation = SpeechPreparationState.Downloading }

        compose.onNodeWithText("Check English + Hebrew").assertIsNotEnabled()
        compose.onNodeWithText("Download speech models").assertIsNotEnabled()
    }

    @Test
    fun finalStepShowsTheExactPixelQuickTapPath() {
        setContent {
            OnboardingScreen(
                step = OnboardingStep.QuickTap,
                settings = AppSettings(treeUri = Uri.parse("content://notes/tree/SideNote")),
                folderLabel = "SideNote",
                microphoneGranted = true,
                notificationsGranted = true,
                speechPreparation = SpeechPreparationState.Available,
                message = null,
                onChooseFolder = {},
                onRequestPermissions = {},
                onAcceptVoiceDisclosure = {},
                onOnlineFallbackChange = {},
                onCheckSpeech = {},
                onDownloadModels = {},
                onContinue = {},
            )
        }

        compose.onNodeWithText("Step 3 of 3").assertIsDisplayed()
        compose.onNodeWithText(
            "Settings → System → Gestures → Quick Tap → Open app → SideNote",
        ).assertIsDisplayed()
        compose.onNodeWithText("Finish setup").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun fontScaleTwoKeepsTheCurrentStepScrollableWithoutClippingItsPrimaryAction() {
        setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                OnboardingScreen(
                    step = OnboardingStep.QuickTap,
                    settings = AppSettings(treeUri = Uri.parse("content://notes/tree/SideNote")),
                    folderLabel = "SideNote",
                    microphoneGranted = true,
                    notificationsGranted = true,
                    speechPreparation = SpeechPreparationState.Available,
                    message = null,
                    onChooseFolder = {},
                    onRequestPermissions = {},
                    onAcceptVoiceDisclosure = {},
                    onOnlineFallbackChange = {},
                    onCheckSpeech = {},
                    onDownloadModels = {},
                    onContinue = {},
                )
            }
        }

        val root = compose.onNodeWithTag(ONBOARDING_ROOT_TAG).bounds()
        val step = compose.onNodeWithText("Step 3 of 3").bounds()
        assertThat(step.left).isAtLeast(root.left)
        assertThat(step.top).isAtLeast(root.top)
        assertThat(step.right).isAtMost(root.right)
        assertThat(step.bottom).isAtMost(root.bottom)
        compose.onNodeWithText("Finish setup").assertIsDisplayed()
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.bounds(): Rect =
        fetchSemanticsNode().boundsInRoot

    private fun setContent(content: @androidx.compose.runtime.Composable () -> Unit) {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario?.onActivity { activity -> activity.setContent(content = content) }
        compose.waitForIdle()
    }
}
