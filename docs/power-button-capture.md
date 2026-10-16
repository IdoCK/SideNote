# Power-button capture

Choose SideNote as the default digital assistant, then configure the device's long Power-button press for Digital assistant. This replaces the current assistant for system assistant invocations. The ordinary app icon opens Review. To revert, select your preferred assistant under Android Settings → Apps → Default apps → Digital assistant app. Availability and settings wording depend on the device.

The system-bound assistant services forward invocations to Capture. Both require Android's BIND_VOICE_INTERACTION permission. Assist-data and screenshot context are disabled. The integration has no hotword detector, background microphone capture, gesture polling or wake lock. Android can keep the selected assistant service bound; this is not zero process residency. Capture's voice-on-at-launch preference controls recording after launch.

**The active or restored recovery draft can be shown while locked.** Review/history/Settings require unlock. Repeated assistant invocations retain the active capture; they do not intentionally save it merely by reopening. Screen-off Quick Tap is not provided by this integration.

After configuring the role, test physical long-press entry while unlocked and locked, microphone preference, repeated invocations, and unlock-protected Review on your own device with fictional notes. No historical device serial, diagnostic log or physical acceptance result is published in this snapshot.

References: [Android assistant API](https://developer.android.com/reference/android/service/voice/VoiceInteractionService), [assistant activity launch](https://developer.android.com/reference/android/service/voice/VoiceInteractionSession#startAssistantActivity(android.content.Intent)).
