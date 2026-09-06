# Offline Plex Music 0.3.0 validation

Verified locally on 2026-09-06.

- APK: `Offline-Plex-Music-0.3.0.apk`, debug-signed, Android 8.0+.
- SHA-256: `4B234CAD8B2032EF161A7AFC414A811396C9547B2018C5645664CDB7588D66DC`.
- Android APK signature verification passed (v2 signature).
- 34 JVM tests passed: 8 download-filter/threshold tests, 8 radio-planning, 9 rating/persistence/cleanup, 5 Plex server API and 4 Plex account API contract tests.
- 10 Android 14 emulator integration tests passed on the dedicated `PocketMusic_Test` AVD.
- New emulator tests verify the launcher name and visible first-run connection controls, and enter a server URL/token through the Plex screen, import music, and stream the imported track. The fixture verifies that import preserves a queued rating and makes only GET requests.
- Account contract tests verify strong PIN creation, consistent client identification, browser URL encoding, pending/claimed PIN responses, header-only account tokens, server-specific tokens, HTTPS preference, rejected malformed addresses, and actionable HTTP errors.
- Live unauthenticated Plex PIN creation and polling succeeded. The test PIN remained unclaimed; no user account was accessed.
- Emulator coverage includes HTTP audio streaming, bulk downloads with byte-for-byte verification, Android document-folder access, scanning manually added audio, selective local deletion, manual rating queue persistence, playlist creation, offline filtering/playback, and playback advancing while the activity is in the background.
- New coverage verifies direct download and removal controls, artist/group cleanup reviews, strict rating thresholds with optional unrated/own-file inclusion, removal of all local music, and preservation of Plex metadata and queued ratings after local removal.
- Android lint: 0 errors, 12 warnings. Warnings concern the target API/dependency versions, optional Kotlin conveniences, and deprecated test API usage.
- Screenshots in `screenshots/` are from the emulator using clearly synthetic song metadata and generated silent WAV files. They are UI examples, not a user's Plex library.

Reproduce with `build.ps1`; run `build.ps1 -Tasks connectedDebugAndroidTest` with an emulator available for the device tests. Reports are under `%LOCALAPPDATA%\PocketMusic-build\app\reports`.

No user Plex server was accessed. Real-server authentication, actual music formats, account/server permissions, and permanent Plex deletion remain unverified. The API deletion tests use a local fixture and verify that only reviewed music-track endpoints can receive DELETE requests. No real music was deleted.
