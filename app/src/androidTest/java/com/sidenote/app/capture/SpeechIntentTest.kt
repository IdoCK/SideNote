package com.sidenote.app.capture

import android.speech.RecognizerIntent
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SpeechIntentTest {
    @Test
    fun localIntentCarriesTheExactAutomaticBilingualRecognitionContract() {
        val intent = SpeechIntentFactory.create(SpeechRequest(preferOffline = true))

        assertThat(intent.action).isEqualTo(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        assertThat(intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL))
            .isEqualTo(RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        assertThat(intent.getBooleanExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)).isTrue()
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
