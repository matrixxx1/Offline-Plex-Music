# Offline Plex Music 0.5.0 validation

Verified locally on 2026-09-06.

- APK: `Offline-Plex-Music-0.5.0.apk`, debug-signed, Android 8.0+.
- SHA-256: `5BAE1FCFA328B93D4D7785ED8073351E26D33FC48362E41C862D4E41538C679E`.
- Android APK signature verification passed (v2 signature).
- 51 JVM tests passed: 8 car catalog tests, 8 download-filter/threshold tests, 8 radio-planning, 9 rating/persistence/cleanup, 5 Plex server API, 5 Plex account API contract tests, and 8 login-recovery tests.
- 14 Android 14 emulator integration tests passed on the dedicated `PocketMusic_Test` AVD.
- Car catalog coverage includes four root sections, all radio modes, offline and downloaded-file filtering, playlist order and selection, Unicode IDs, voice query matching, malformed-ID rejection, and complete range-folder coverage of 10,005 tracks.
- Platform MediaBrowser/MediaController tests start the service without opening a phone activity; browse playlists; play, pause, seek, skip, and stop; queue and persist ratings without syncing; play from voice search; and start offline album radio with the two-track setting retained.
- A Media3 browser test searches, resolves the selected result into playable audio, and verifies an arbitrary external URI is rejected. Android Auto discovery metadata and the exported browser service are asserted as well.
- No physical Android Auto head unit or Desktop Head Unit projection was available. Host-specific UI, voice recognition, and custom-action placement remain to be checked in a vehicle. The tests exercise the media-browser/transport protocol, not a simulated car screenshot.
- New emulator tests verify the launcher name and visible first-run connection controls, and enter a server URL/token through the Plex screen, import music, and stream the imported track. The fixture verifies that import preserves a queued rating and makes only GET requests.
- Account contract tests verify strong PIN creation, consistent client identification, browser URL encoding, pending/claimed PIN responses, header-only account tokens, server-specific tokens, HTTPS preference, rejected malformed addresses, and actionable HTTP errors.
- Live unauthenticated PIN creation on plex.tv and lookup of the same PIN on clients.plex.tv succeeded. The test PIN remained unclaimed; no user account was accessed. Alternate-host fallback retains HTTPS certificate verification and is limited to DNS/connection failures.
- Login recovery tests cover retrying the same PIN, retaining authorization before server discovery, transient DNS/HTTP retries, expiration, invalid credentials, cancellation, and TLS failures. Android tests verify encrypted PIN/token persistence across activity/ViewModel recreation, recovery controls, and resetting sign-in without erasing the saved server or queued ratings.
- The reported phone DNS failure was not reproduced on that physical phone. The update preserves login progress through network failures; it cannot guarantee resolution of device, VPN, or carrier DNS problems.
- Emulator coverage includes HTTP audio streaming, bulk downloads with byte-for-byte verification, Android document-folder access, scanning manually added audio, selective local deletion, manual rating queue persistence, playlist creation, offline filtering/playback, and playback advancing while the activity is in the background.
- New coverage verifies direct download and removal controls, artist/group cleanup reviews, strict rating thresholds with optional unrated/own-file inclusion, removal of all local music, and preservation of Plex metadata and queued ratings after local removal.
- Android lint: 0 errors, 15 warnings. Warnings concern the target API/dependency versions, backup configuration, optional Kotlin conveniences, and exposed media-search service permissions.
- Screenshots in `screenshots/` are from the emulator using clearly synthetic song metadata and generated silent WAV files. They are UI examples, not a user's Plex library.

Reproduce with `build.ps1`; run `build.ps1 -Tasks connectedDebugAndroidTest` with an emulator available for the device tests. Reports are under `%LOCALAPPDATA%\PocketMusic-build\app\reports`.

No user Plex server was accessed. Real-server authentication, actual music formats, account/server permissions, and permanent Plex deletion remain unverified. The API deletion tests use a local fixture and verify that only reviewed music-track endpoints can receive DELETE requests. No real music was deleted.
