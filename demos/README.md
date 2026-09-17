# SideNote canonical web demo

`sidenote-demo.html` is the authoritative browser simulation for SideNote 1.0.12. PersonalSite imports this file; make demo changes here, not in the generated website copy. Review native Kotlin UI and behavior when updating it.

Open the HTML in a modern browser, or serve this directory with a static server. The optional local `archivo-1.woff2` and `archivo-3.woff2` files style the surrounding presentation; the demo falls back to system fonts without them. Keep those assets alongside the HTML when publishing. No JavaScript build step, CDN, credentials, or API is required.

The guided journey opens Capture, adds a fictional speech sample, simulates face-down saving, and returns to the saved thought. Additional controls expose screen-off, backgrounding, Power capture, Quick Tap, wake, and reset. Dates, Projects, Settings, note expansion, processing, typing, and project suggestions remain interactive. The layout adapts to small screens and honors reduced motion.

This is a simulation, not a compiled Android SDK or embedded native app. Microphone input, Android permissions, folder access, notifications, gestures, and device sensors are simulated. Notes are fictional, stored in memory only, and reset on refresh. Use screenshots from a verified native release for product evidence; label any browser-demo captures as simulated.

For every release or SDK/APK build, follow the repository's personal-site update workflow: make a minimal plan covering demo behavior, descriptions, screenshots, and version provenance; update these together before publishing. The HTML's `sidenote-version` metadata records the native version it represents.

## Verification checklist

- Complete the guided capture/sample/face-down/save journey and reset during pending speech or saving.
- Check Power/Quick Tap repeat handling and screen-off wake behavior.
- Tap each date; verify the selected rectangular date and fixed navigation.
- Open Projects, a project, a source date, and Settings. Escape from Settings returns to the prior Review tab.
- Expand/collapse a long note; confirm ellipsis, height animation and rotating chevron. Short notes have no chevron. Toggle processing independently.
- Exercise online-voice disclosure with keyboard, Escape, and both choices; no real permissions should be requested.
- Check desktop and phone widths, keyboard focus, reduced motion, and absence of console errors.
