# Offline Plex Music 0.6.0 validation

Verified locally on 2026-09-06 with `build.ps1 -Tasks testDebugUnitTest,lintDebug,assembleDebug,connectedDebugAndroidTest`.

- APK: `Offline-Plex-Music-0.6.0.apk`, debug-signed, Android 8.0+.
- SHA-256: `675C1465FFBA1FF1F7EEC2D7AEF59800F01D55BCD1D627447CC9F70920D2DEAC`.
- APK v2 signature verified with the same certificate as prior releases.
- 61 JVM tests passed: 9 smart-download, 8 car catalog, 8 download-filter, 8 radio, 9 rating/persistence, 6 Plex server API, 5 Plex account API, and 8 login-recovery tests.
- 16 Android 14 emulator integration tests passed on the dedicated `PocketMusic_Test` AVD.
- Android lint: zero errors, 15 warnings (existing API/dependency, backup, Kotlin convenience, and media-search service items).
- Smart-download tests cover independent per-artist/per-genre sampling, short groups, overlapping genres with deduplication, total limits, exact group matches, separate albums with identical titles, and excluding local/downloaded/queued/unavailable files. Fixed random seeds verify reproducibility and reshuffling.
- Tag tests cover track/album/artist inheritance, normalization and blank removal, missing metadata, JSON persistence, and reading older libraries without losing ratings or local file links. Fixture requests retain Media/Part data and only use GET.
- Android tests select per-genre counts, reject invalid counts and empty selections, search for an existing Happy mood, review the exact matching songs, and exclude individual songs without changing library ratings or queuing downloads.
- A complete per-artist download runs through the new UI against a local HTTP audio fixture. It skips the already-downloaded song and verifies the downloaded bytes. Existing scan/local deletion checks verify queued ratings and remote media remain intact.
- Reviewed current emulator screenshots: `screenshots/smart-download-setup.png` and `screenshots/smart-download-moods.png`. All songs, metadata and media in tests are synthetic. The count field dismisses its keyboard with Done and the picker accommodates keyboard insets.
- Existing login-recovery tests still cover encrypted PIN/token persistence, DNS/HTTP retries, expiry, invalid tokens, cancellation, and TLS errors. Existing streaming, radio in background, playlists, manual ratings, and download cleanup tests passed.
- Existing Android Auto tests cover platform and Media3 browsing/search/playback/ratings without opening a phone activity. No physical car head unit or Desktop Head Unit projection was available; host-specific UI remains unverified.

No user Plex server or phone files were accessed during this change. Mood/style availability depends on metadata returned by the user's Plex server; no moods were inferred or written back. Refresh music after upgrading to populate the new tag fields. Smart counts mean new downloads per selected group, not a target total already on the device. A song may match several groups but is queued only once.

Reports are under `%LOCALAPPDATA%\PocketMusic-build\app\reports`. GitHub branch and tag CI and the released APK download/checksum are verified separately before publishing.
