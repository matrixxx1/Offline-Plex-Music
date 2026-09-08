# Offline Plex Music 0.10.0 validation

Verified on 2026-09-07 using `build.ps1 -Tasks testDebugUnitTest,lintDebug,assembleDebug,connectedDebugAndroidTest`.

- 68 JVM tests passed; all 26 Android emulator tests passed on PocketMusic_Test (Android 14).
- Android lint: 0 errors, 16 warnings.
- APK package `com.m3.pocketmusic`, versionCode 10, versionName 0.10.0, minimum API 26.
- APK signature verified; certificate matches the local 0.9.0 APK: SHA-256 `a10f7fb8796db24804477c00230ed03b92a46ef0e1e64f1b3000c4cddfa56b13`.
- APK SHA-256: `158D76742E54FCD83FA49D236949DC48853F14E6DB1BA4C26FF613FED403E30D`.
- Legacy Android Auto bridge test verifies exposed custom actions have nonzero icons, both action menus appear, artist jumping and playlist shuffle work, ratings persist, deletion flags persist and can be removed without network writes.
- HTTP fixture verifies offline deletion flags, rating-only sync without DELETE, failed server deletion retention, and explicitly confirmed deletion retaining downloaded copies and ratings.
- Artwork test verifies a successful image fetch followed by shared-album cache reuse in offline mode without a second request.
- Existing playlist, large-library, playback, download, and local-removal regression tests pass. Screenshot helper now generates unique filenames across repeated emulator runs.
- Reviewed `screenshots/offline-player-0.10.0.png` for phone tab order, playback controls, artist jump, shuffle, and local deletion layout.

No physical phone or car head unit was connected. Physical car placement and real Plex server operations remain unverified. Tests used synthetic media and local HTTP fixtures; no user music was deleted.
