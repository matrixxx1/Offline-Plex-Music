# Offline Plex Music 0.11.0 validation

Verified on 2026-09-07 using `build.ps1 -Tasks testDebugUnitTest,lintDebug,assembleDebug,connectedDebugAndroidTest`.

- 83 JVM tests passed and all 29 Android emulator tests passed on PocketMusic_Test (Android 14).
- Android lint: 0 errors, 16 warnings.
- APK package `com.m3.pocketmusic`, versionCode 11, versionName 0.11.0, minimum API 26.
- APK signature verified. Development certificate SHA-256 remains `a10f7fb8796db24804477c00230ed03b92a46ef0e1e64f1b3000c4cddfa56b13`.
- APK SHA-256: `4509AE5F3A0E7EE9C073B463CA5F35937A091E9885F914AA01DAA9FAE708CD8B`.
- Now playing UI test verifies metadata, shared car actions, rating changes, reviewed local deletion, queue, and Stop. Selected playlist tests verify rename, position-based moves, removing one duplicate occurrence, adding a song, restored selection/draft, offline pending changes, and separate sync review.
- Playlist API fixture tests verify add/remove/reorder/rename, numeric entry IDs, duplicate preservation, no media deletion, conflict refusal, empty playlists, smart/local-track rejection, partial-failure checkpoints, and retry after an ambiguous applied request without duplicate additions.
- Durable playlist rules cover migration of older snapshots, baseline preservation, refresh/deleted-server draft preservation, reverting changes, and acknowledgement with concurrent local edits.
- Download regression uses deliberately incorrect Plex metadata sizes both larger and smaller than the actual file. Both complete files are byte-exact, finalized, and store actual sizes. Existing Wi-Fi queuing, interruption/resume, scan, and local cleanup checks pass. Transfer unit tests still reject truncated/empty responses and network failure.
- Reviewed screenshots: `screenshots/now-playing-0.11.0.png` and `screenshots/playlist-editor-0.11.0.png`. Selected navigation tabs scroll into view automatically.

Playlist endpoint behavior was checked against the [Python-PlexAPI playlist implementation](https://raw.githubusercontent.com/pkkid/python-plexapi/master/plexapi/playlist.py) and [field-edit implementation](https://python-plexapi.readthedocs.io/en/latest/_modules/plexapi/mixins/edit.html). Tests use local HTTP fixtures rather than a real Plex account. Server permissions and concurrent remote edits during a multi-step sync are not atomic guarantees; final readback must match before edits are marked synced.

No physical phone or car head unit was connected. No user Plex media was changed or deleted. Earlier failed download jobs can be retried from Downloads > Transfers > Resume / retry after updating.
