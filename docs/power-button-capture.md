# Power-button Capture — 1.0.10

Choose SideNote as the default digital assistant, then configure Pixel's long power-button press for Digital assistant. This replaces Gemini for system assistant invocations. The ordinary app icon still opens Review. To revert, select Gemini/Google under Android Settings → Apps → Default apps → Digital assistant app.

The system-bound CaptureAssistantService handles locked invocations; CaptureAssistantSessionService forwards ordinary assistant invocations through startAssistantActivity. Both target the existing single-task CaptureActivity, retaining the current draft and its lock-screen privacy protections. Both services require BIND_VOICE_INTERACTION. The assistant disables assist-data and screenshot context. It does not implement hotword detection, microphone capture, motion monitoring, polling or a wake lock. Android keeps the selected assistant service bound, so this is an idle bound service rather than zero background process residency. Capture's existing voice-on-at-launch setting controls recording.

The metadata intentionally declares an empty recognitionService (supported on our Android 14+ minimum) rather than supplying a speech provider. The phone's existing GoogleTTSRecognitionService remained selected after the role change.

Validation on 2026-09-10:

- 226 unit tests passed, including keyguard invocation targeting Capture without review extras.
- 14 emulator instrumented checks passed: packaged assistant metadata and permissions, speech intent behavior, and Review/Settings navigation and lock protections.
- Release build and lint passed: 0 errors, 17 warnings, 1 hint. APK signature and alignment verified against the existing release certificate.
- Installed release 1.0.10 (code 11) in place on Pixel 8, serial 38060DLJH0012F. Selected assistant via the standard role manager; existing power-button setting was already Assistant with a 500 ms hold.
- Phone initially Dozing; injected long power key opened Capture, power state became Awake, keyguard remained showing.
- User independently confirmed a physical long Power press from screen off opens Capture correctly.
- Emulator unlocked Assistant key opened Capture as an assistant task. Direct `cmd voiceinteraction show` was denied its system permission; the normal input-key route succeeded without granting permissions or bypassing role qualification.

APK: releases/SideNote-1.0.10.apk

SHA256: C96CE195A4418F4FDB0E7D018094F80C552DD0EA04FB6A84ABB2808DBCD6C838

References: [Android assistant API](https://developer.android.com/reference/android/service/voice/VoiceInteractionService), [assistant activity launch](https://developer.android.com/reference/android/service/voice/VoiceInteractionSession#startAssistantActivity(android.content.Intent)), [AOSP empty recognition-service example](https://android.googlesource.com/platform/development/+/refs/heads/main/samples/VoiceInteractionService/res/xml/voice_interaction.xml).
