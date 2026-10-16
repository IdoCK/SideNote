package com.sidenote.app.capture

import android.content.Intent
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.os.Looper
import android.os.Bundle
import android.speech.SpeechRecognizer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SpeechIntentTest {
    @Test
    fun recognitionServiceDiscoveryIsSafeWhenAProviderIsPresentOrAbsent() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val services = context.packageManager.queryIntentServices(
            Intent(RecognitionService.SERVICE_INTERFACE),
            0,
        )

        assertThat(services).doesNotContain(null)
        services.forEach { result ->
            assertThat(result.serviceInfo?.packageName).isNotEmpty()
            assertThat(result.serviceInfo?.name).isNotEmpty()
        }
    }

    @Test
    fun nullEmptyAndBlankRecognitionBundlesStillProduceTerminalSignals() {
        val empty = Bundle()
        val blank = Bundle().apply {
            putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("   "))
        }

        assertThat(terminalRecognitionText(null)).isEmpty()
        assertThat(terminalRecognitionText(empty)).isEmpty()
        assertThat(terminalRecognitionText(blank)).isEqualTo("   ")
    }

    @Test
    fun androidMainThreadBoundaryMarshalsBackgroundSyncAndSuspendWork() = runBlocking {
        val mainThread = AndroidSpeechMainThread()
        val background = Executors.newSingleThreadExecutor()

        try {
            val synchronousWasMain = background.submit<Boolean> {
                mainThread.run { Looper.myLooper() == Looper.getMainLooper() }
            }.get()
            val suspendingWasMain = withContext(Dispatchers.Default) {
                mainThread.runSuspending { Looper.myLooper() == Looper.getMainLooper() }
            }

            assertThat(synchronousWasMain).isTrue()
            assertThat(suspendingWasMain).isTrue()
        } finally {
            background.shutdownNow()
        }
    }

    @Test
    fun localIntentCarriesTheExactAutomaticBilingualRecognitionContract() {
        val intent = SpeechIntentFactory.create(SpeechRequest(preferOffline = true))

        assertThat(intent.action).isEqualTo(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        assertThat(intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL))
            .isEqualTo(RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        assertThat(intent.getBooleanExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)).isTrue()
        assertThat(intent.getStringExtra(RecognizerIntent.EXTRA_ENABLE_FORMATTING))
            .isEqualTo(RecognizerIntent.FORMATTING_OPTIMIZE_QUALITY)
        assertThat(intent.getBooleanExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)).isTrue()
        assertThat(intent.getBooleanExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, false)).isTrue()
        assertThat(intent.getStringExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH))
            .isEqualTo(RecognizerIntent.LANGUAGE_SWITCH_BALANCED)
        assertThat(intent.getStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES))
            .containsExactly("en-US", "he-IL")
            .inOrder()
        assertThat(intent.hasExtra(RecognizerIntent.EXTRA_LANGUAGE)).isFalse()
    }

    @Test
    fun onlineFallbackIntentKeepsTheBilingualContractAndOnlyDisablesOfflinePreference() {
        val intent = SpeechIntentFactory.create(SpeechRequest(preferOffline = false))

        assertThat(intent.getBooleanExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)).isFalse()
        assertThat(intent.getStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES))
            .containsExactly("en-US", "he-IL")
            .inOrder()
        assertThat(intent.getStringExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH))
            .isEqualTo(RecognizerIntent.LANGUAGE_SWITCH_BALANCED)
        assertThat(intent.hasExtra(RecognizerIntent.EXTRA_LANGUAGE)).isFalse()
    }

    @Test
    fun supportAndDownloadProbeAddsOnlyTheRequestedLanguageToTheSameBilingualIntent() {
        val intent = SpeechIntentFactory.create(
            SpeechRequest(preferOffline = true, languageTag = "he-IL"),
        )

        assertThat(intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE)).isEqualTo("he-IL")
        assertThat(intent.getStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES))
            .containsExactly("en-US", "he-IL")
            .inOrder()
    }
}
