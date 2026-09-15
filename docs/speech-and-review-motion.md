# SideNote 1.0.8

September 10, 2026 · version code 9

## Speech feedback

The enabled blob is static during silence. Speech onset enables feedback; 16 kHz mono PCM is analyzed in 64 ms windows. Loudness controls deformation strength, normalized fundamental pitch controls movement speed and lobe placement, and spectral brightness controls lobe reach. “Tone” means acoustic brightness, not emotion or sentiment. The contour settles over 140 ms after input becomes silent. The tap target stays fixed and disabled system animations are respected.

One AudioRecord stream feeds both an in-memory pipe to Android SpeechRecognizer and the acoustic analyzer. No audio files are created. The analyzer removes DC, rejects a quiet floor, estimates voiced pitch between 80 and 500 Hz through normalized autocorrelation, and estimates brightness from first-difference energy. Speech detection additionally gates motion to avoid animating ordinary background noise before speech begins.

If the provider rejects shared audio, silences this recorder, or stops consuming the pipe, the session closes and the existing retry mechanism falls back to the recognizer-owned microphone. That fallback provides loudness feedback only. Pitch/brightness reset across sessions, so old measurements cannot masquerade as current input. Existing local/online consent and stop/save rules are retained.

## Review

Notes expand and collapse from their top edge. Days slide in the navigation direction. Dates/Projects and project details use a small horizontal slide and fade. Transitions run for 220 ms at normal Android animation speed. Outgoing pages retain their content until the exit finishes, while their controls and accessibility semantics are disabled. Native Compose animations honor Android's animation duration setting.

## Verification

213 JVM tests pass. 10 Review UI tests, 14 capture UI tests and 17 capture lifecycle tests pass on the emulator (41 total). The keyboard-focus case initially failed because Pixel Launcher/System UI ANR dialogs held focus; it passed after those emulator dialogs were dismissed. Release build and lint pass: 0 errors, 17 warnings, 1 hint. Two rendered speech-blob frames were inspected. Tests cover static silence, pitch/loudness/brightness measurements, stale session callbacks, fixed blob bounds, intermediate note expansion, directional day changes, and outgoing tab/project content.

Android's [audio-source API](https://developer.android.com/reference/android/speech/RecognizerIntent#EXTRA_AUDIO_SOURCE) is provider-dependent; physical device validation is necessary in addition to the synthetic-signal tests.

Installed in place on the connected Pixel 8, preserving app data. Verified version 1.0.8/code9, release signature/alignment, active unsilenced 16 kHz mono microphone stream, and repeated speech-detection callbacks with no microphone fallback in the observed session. User feedback on perceived motion and physical stop gestures remains pending.

APK SHA-256: 426475856993676421F6841CDF464E8722A1E94E23B92E3C70F1E55F410AEBC2.
