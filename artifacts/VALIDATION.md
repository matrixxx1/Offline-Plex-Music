# Offline Plex Music 0.2.0 validation

Verified locally on 2026-09-06.

- APK: `Offline-Plex-Music-0.2.0.apk`, debug-signed, Android 8.0+.
- SHA-256: `561D801A941FFD8B9C1B4A74E3C66A5F37F448947A7C78D263664B3FABD12014`.
- Android APK signature verification passed (v2 signature).
- 30 JVM tests passed: 8 download-filter/threshold tests, 8 radio-planning, 9 rating/persistence/cleanup, 5 Plex API contract tests.
- 8 Android 14 emulator integration tests passed on the dedicated `PocketMusic_Test` AVD.
- Emulator coverage includes HTTP audio streaming, bulk downloads with byte-for-byte verification, Android document-folder access, scanning manually added audio, selective local deletion, manual rating queue persistence, playlist creation, offline filtering/playback, and playback advancing while the activity is in the background.
- New coverage verifies direct download and removal controls, artist/group cleanup reviews, strict rating thresholds with optional unrated/own-file inclusion, removal of all local music, and preservation of Plex metadata and queued ratings after local removal.
- Android lint: 0 errors, 10 warnings. Warnings concern the target API/dependency versions, optional Kotlin conveniences, and deprecated test API usage.
- Screenshots in `screenshots/` are from the emulator using clearly synthetic song metadata and generated silent WAV files. They are UI examples, not a user's Plex library.

Reproduce with `build.ps1`; run `build.ps1 -Tasks connectedDebugAndroidTest` with an emulator available for the device tests. Reports are under `%LOCALAPPDATA%\PocketMusic-build\app\reports`.

No user Plex server was accessed. Real-server authentication, actual music formats, account/server permissions, and permanent Plex deletion remain unverified. The API deletion tests use a local fixture and verify that only reviewed music-track endpoints can receive DELETE requests. No real music was deleted.
