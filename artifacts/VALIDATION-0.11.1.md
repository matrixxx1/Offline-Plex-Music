# Offline Plex Music 0.11.1 validation

Verified on 2026-09-07 with `build.ps1 -Tasks testDebugUnitTest,lintDebug,assembleDebug,connectedDebugAndroidTest`.

- 83 JVM tests passed; all 30 Android emulator tests passed on PocketMusic_Test (Android 14).
- Lint: 0 errors, 16 warnings.
- The new responsiveness test seeds 31,382 tracks and download jobs, holds the library writer lock, invokes pause/rating/settings on Main, and requires a later main-thread message to execute within two seconds while that lock remains held. After release it verifies pause, rating, both settings, and queue persistence through a fresh store instance.
- Existing HTTP/document-provider download tests pass, including Wi-Fi waiting, interrupted download/resume, byte-exact completion despite stale Plex metadata, and local cleanup.
- Rating/playlist tests wait for completed durable saves. Integration coverage verifies that starting sync immediately after rating and going online includes those pending changes.
- Phone writes run on IO in tap order. Sync waits for earlier edits. Download document rename occurs outside the store lock, and local URI/actual size/queue removal are committed together.
- APK: `com.m3.pocketmusic`, versionCode 12, versionName 0.11.1, minimum API 26, target API 36.
- Signature verified; unchanged development certificate SHA-256: `a10f7fb8796db24804477c00230ed03b92a46ef0e1e64f1b3000c4cddfa56b13`.
- APK SHA-256: `A7269418FA0C3988F00CC8220C285E4209E960E01CB48EC9A20A27BB6E6FD624`.

The test demonstrates removal of an identified UI-blocking path; it does not reproduce the reported phone's exact ANR. No physical phone or head unit was connected. No real Plex media was modified. Existing download retry and resume controls remain available after updating.
