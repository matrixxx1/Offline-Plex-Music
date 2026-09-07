# Offline Plex Music 0.9.0 validation

Verified locally on 2026-09-06 with `build.ps1 -Tasks testDebugUnitTest,lintDebug,assembleDebug,connectedDebugAndroidTest`.

- APK: `Offline-Plex-Music-0.9.0.apk`, Android 8.0+, versionCode 9.
- SHA-256: `6828FA5EDA29AFC2CEF549A437A0A75DB83CC8AC820FED0B10AF9DDC3B78BB34`.
- APK v2 signature verified. Certificate SHA-256 remains `a10f7fb8796db24804477c00230ed03b92a46ef0e1e64f1b3000c4cddfa56b13`; application ID remains `com.m3.pocketmusic`.
- 66 JVM tests passed; Android lint has zero errors and 15 existing warnings.
- Full Android 14 emulator suite: 23 tests passed on the dedicated PocketMusic_Test AVD. A targeted rerun of the large-playlist test also passed after adding a wait for keyboard dismissal before the screenshot; the APK hash was unchanged.
- The reported playlist-opening path previously performed one full-library search for each playlist song on the UI thread. LibraryIndexTest counts exactly 31,382 source reads to build the index and no additional source reads to resolve a reversed 31,382-entry playlist. Missing entries and repeated playlist entries preserve ordering and metadata.
- Phone library filtering/grouping and download candidate/planning work run on a background dispatcher. Cache refresh is tested with an equivalent track snapshot so it does not leave the loading state stuck. Android Auto playlist resolution also uses indexed lookup.
- Emulator test opens a synthetic 31,382-song playlist within a five-second bound, sees all tracks, opens its download options, and verifies 20 MB selects five 4 MB songs; per-artist limits of one and two yield 25 and 50 songs under a larger cap. Invalid zero MB disables queuing. Pending ratings are preserved.
- Planner tests verify exact byte caps, per-artist caps, overlap deduplication, deterministic random selection and reshuffling, exclusion of unknown/oversized files under a cap, all-songs behavior, and invalid/overflowing numeric limits. Size caps apply to new files, not existing device contents or retry traffic.
- End-to-end download test uses All songs for the first playlist, then a 1 MB random cap for an overlapping playlist's remaining song. It checks Wi-Fi-only queuing without audio HTTP, automatic start when Wi-Fi returns, interrupted-file recovery, byte-exact downloaded files, folder scans, and local removal without server deletion or dropping ratings.
- Large phone playback uses 100-song chunks. A media-controller test verifies bounded queue growth, continuation in order through 400 songs, and reaching the last song. Existing car browse/search/rating, Stop/restart, audio-focus handoff, empty-radio, missing-file, background radio, and local playlist tests pass.
- Reviewed synthetic screenshots: screenshots/playlist-size-options.png and screenshots/playlist-downloads.png. Keyboard Done dismisses the numeric keyboard. Controls and preview scroll while the queue button remains outside the scrolling list.

No physical phone or car head unit was connected. The user's original ANR trace was not captured, so the quadratic lookup is an identified code defect and likely explanation, not a confirmed device stack diagnosis. No user Plex media or phone files were accessed or deleted.

The app still keeps a library snapshot in memory and reads it on startup; memory use grows with collection size. This change addresses playlist resolution, selection, and long playback queue construction, not a complete storage-engine replacement.

Reports are under `%LOCALAPPDATA%\PocketMusic-build\app\reports`. Branch/tag CI and the downloadable GitHub APK checksum are verified before publishing the draft release.