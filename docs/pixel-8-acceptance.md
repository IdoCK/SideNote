# Device acceptance checklist

Use a dedicated test folder and fictional notes. This checklist is a specification, not a record of completed physical tests. Emulator automation cannot establish actual Pixel gesture delivery, sensor reliability, speech accuracy, keyguard frames, TalkBack output or haptic perception.

## Preparation

Build/install the version you intend to test. Record model, Android version, speech provider, downloaded languages, fallback preference, and app version in a private test record. Do not publish device serials, personal folder paths or real notes. Configure a real keyguard and grant only the permissions required for the case.

## Manual checks

1. **Entry points:** open Review from the icon and Capture from its shortcut. On a supported Pixel configure Quick Tap for Capture, and optionally select the digital-assistant Power-button route. Test actual physical gestures; a synthetic activity launch is not equivalent. Stock screen-off Quick Tap is unsupported.
2. **Voice preference:** with Voice on at launch enabled, speak a fictional marker immediately after entry. Repeat disabled, then toggle the blob deliberately. Check English, Hebrew and mixed utterances, local models, permission denial and consented fallback. Record transcript inaccuracies; provider support does not guarantee accuracy or privacy policy.
3. **Exactly-once completion:** type a unique fictional marker and finish by screen-off, timeout and backgrounding. Check one new Markdown task per completion and no duplicate restored draft. Repeated launch keeps the current draft; it does not itself mean save.
4. **Face-down placement:** verify ordinary handling and brief placement below 750 ms do not finish. Stable face-down placement beyond the threshold should save once. Repeat with the intended case/posture and check false positives.
5. **Keyguard privacy:** create fictional previous notes, lock, and try Review, Settings, Projects, notifications and folder selection. Cancel unlock and inspect for private-content flashes. Those history routes should stay gated. Capture deliberately exposes its current/restored draft while locked; it is not a confidential vault.
6. **Recovery and storage:** revoke folder access or use a controlled faulting provider, verify failure is not reported as saved, restore access and inspect Markdown before retrying an uncertain write. Preserve recovery data while testing. Providers without create/rename capability are unsupported for writes.
7. **Accessibility:** exercise TalkBack reading/order, keyboard or alternative date controls, enlarged text, bidirectional text, reduced motion, visible errors and haptic confirmation on physical hardware.

## Known boundaries

SAF has no universal compare-and-swap or atomic-replace guarantee. An external writer can race the final provider mutation. Hidden recovery artifacts can remain when deletion is unavailable. Process death after a confirmed append but before recovery cleanup can restore an already committed mirror because its cleanup marker is process-local. See README privacy and limitation sections before relying on sensitive captures.

Unit, lint, build and connected-test commands are in the README. Run them for your environment and keep actual results distinct from this checklist. No device certification, conformance percentage or fresh production-release approval is implied.
