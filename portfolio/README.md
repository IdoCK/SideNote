# Personal site release bundle

This directory owns the SideNote page content and image provenance. The interactive demo lives in `demos/sidenote-demo.html`. PersonalSite imports the files declared in `manifest.json`; edit the originals here.

Use `COPY.md` for the approved positioning and tone. `manifest.json`'s plain-text `tagline` also updates the SideNote project card and its accessible label on PersonalSite; keep it aligned with the case-page headline.

At the start of each app change or SDK/APK build, run `node scripts/portfolio-plan.mjs`. Keep the plan short: impact, descriptions, demo, screenshots, and evidence. Revisit existing plans for same-version changes. A screenshot review is mandatory even when no image needs replacing. Capture current native screens when the UI changed; otherwise record why each reused image remains accurate. Never include private notes. Browser/staged images must be identified as such to visitors.

The manifest records the Gradle version/code, review date, completed plan, native source fingerprint, and file mappings. Every file has `source`, `target`, and `sha256`. Every image additionally has `kind` (`native`, `browser`, `illustration`, or `staged`), `appVersion`, `reviewedOn`, and meaningful `provenance`. `appVersion` is the version for which the image was reviewed; provenance must disclose an older capture or synthetic origin. Source paths are relative to this repository; targets are relative to PersonalSite.

After reviewing content and completing the plan, run `node scripts/portfolio-check.mjs --seal`, then `node scripts/portfolio-check.mjs`. Sealing refreshes file hashes and the source fingerprint; it does not update versions, provenance, or approve content. The fingerprint covers sorted paths and bytes under `app/src/main` plus `app/build.gradle.kts` (CRLF is normalized to LF for Kotlin, XML, Java, JSON, properties, text, Markdown, and ProGuard files; binary files remain unchanged), so same-version app edits invalidate the bundle. CI runs the checker and its regression tests; it does not build Android or certify visual accuracy.

Sync the verified bundle with PersonalSite's importer, then check the page at desktop and phone sizes and complete the capture demo journey. Record limitations honestly before release. Any automated synchronization must use a reviewed source revision and retain provenance.

Node 22 or newer is required for these checks. `scripts/sign-release.ps1` runs the portfolio gate, rebuilds the release and verifies the embedded APK version/code before signing; an old APK cannot silently receive the new version's filename. Build/test first to obtain screenshots, complete the plan, then sign. The source fingerprint covers production source sets and build/dependency configuration as well as the main UI.

After changes reach SideNote main, PersonalSite's **Sync SideNote portfolio** GitHub workflow imports the ready bundle every six hours, or immediately when manually dispatched. Its own GitHub token can read this public repository; no cross-repository write token is required. It writes only the generated site files to PersonalSite main for the existing Cloudflare deployment. Check the site lock file and Cloudflare result before saying the public page is updated. A failed freshness gate leaves the current site intact.
