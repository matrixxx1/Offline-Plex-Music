# Offline Plex Music 0.8.0 validation

Verified locally on 2026-09-06 with `build.ps1 -Tasks testDebugUnitTest,lintDebug,assembleDebug,connectedDebugAndroidTest`.

- APK: `Offline-Plex-Music-0.8.0.apk`, Android 8.0+, versionCode 8.
- SHA-256: `5431E538CC0D42671B1E149627A1E93E69811F4D0A1205576E30A9E55BDAA996`.
- APK v2 signature verified; certificate SHA-256 `a10f7fb8796db24804477c00230ed03b92a46ef0e1e64f1b3000c4cddfa56b13`, matching prior releases. Application ID remains `com.m3.pocketmusic`.
- 60 JVM tests passed. New cases cover deduplicated playlist downloads, skipping existing/queued/unavailable/local-only files, playlist metadata refresh without a full scan or writes, and preserving large radio groups across bounded chunks, reset, and removal.
- 20 Android 14 emulator integration tests passed on the dedicated `PocketMusic_Test` AVD.
- Android lint: zero errors, 15 existing warnings.
- Car-controller tests cover browse/search/playlist playback, ratings, Stop clearing the radio queue, restarting afterward, empty radio, missing-file playback errors, and permanent audio-focus handoff to another media focus owner without automatic resumption.
- A car startup regression test exposed selection of an unplayable streaming-only group without Plex configured; radio now excludes those tracks before choosing groups.
- Radio sends at most 100 new tracks per chunk to the player while retaining the remainder of the selected group. The queue mutation guard prevents recursive appends during player callbacks. Download-status-only updates no longer rebuild the car catalog.
- Playlist UI tests select overlapping Plex playlists, skip existing and queued songs, reject empty selection, and preserve pending ratings. A local HTTP fixture verifies playlist downloads through the UI, Wi-Fi-only queueing without HTTP on cellular, automatic start on Wi-Fi, interrupted transfer recovery, byte-exact downloaded audio, and local cleanup without server deletion or losing queued ratings.
- The HTTP interruption fixture now arms its stalled second download only after streaming stops; streaming preloading previously consumed the latch and caused a false failure.
- Existing login persistence/recovery, background radio, local playlists, reviewed deletion, bulk ratings, scanning, Wi-Fi preference persistence, and paused-queue tests passed.
- Reviewed synthetic screenshots: `screenshots/playlist-downloads.png` and `screenshots/download-cleanup.png`.

No user Plex server or phone files were accessed. No physical phone was attached, so the original crash stack could not be captured. Tests use Android platform and Media3 media controllers, not a physical Android Auto head unit or projected Desktop Head Unit. The audio-focus test verifies the handoff behavior; it does not prove the cause of the user's original crash.

Reports are under `%LOCALAPPDATA%\PocketMusic-build\app\reports`. Branch/tag CI and the downloadable GitHub APK checksum are checked before the draft release is published.