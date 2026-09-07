# Changelog

## 0.8.0

- Replace the smart download picker and track/group download buttons with Plex playlist selection. Refresh playlist membership and new tracks without a full library scan; queue missing songs once, retaining Wi-Fi-only by default.
- Keep track/artist/album/genre/rating/all local cleanup. Add filtered/selected rating, manual rating sync, and separately confirmed phone/Plex deletion directly in Downloads.
- Add Stop playback on the phone and car. Platform Stop, permanent audio-focus loss, and playback errors clear radio/queue and release foreground playback. Empty radio selections no longer prepare an empty player.
- Feed large radio groups into the media player in bounded chunks, guard reentrant queue changes, and avoid rebuilding car catalogs for download-status-only updates.
- Preserve app identity, existing download records, encrypted Plex connection, and queued ratings when installing over the prior APK.


## 0.7.0

- Add Only download on Wi-Fi, enabled by default for new installs and upgrades, in the smart-download preview, Transfers, and Settings.
- Persist download selections in the queue until an allowed network is available; automatically start waiting downloads when Wi-Fi returns.
- Apply preference changes to pending work and interrupt transfers that lose their allowed connection. Unfinished files remain queued; completed files are kept.
- Persist an explicit pause so changing the Wi-Fi setting does not resume a paused queue.
- Leave streaming, ratings, and library refresh behavior unchanged.

## 0.6.0

- Add Smart download from the Downloads and Plex screens, with genre, artist, album, mood, and style selection.
- Download whole groups, a random total, or X new songs independently per selected group.
- Add searchable group selection, a stable song preview, individual exclusions, reshuffling, and an original-file size estimate.
- Skip downloaded/queued files and deduplicate songs shared across groups.
- Import and persist existing Plex track/album/artist mood and style tags; refresh music once after upgrading to populate these choices.
- Preserve existing library data, queued ratings, login recovery, and Android Auto support.

## 0.5.0

- Fix the repeated login flow after temporary DNS/network failures during Plex PIN polling or server discovery.
- Encrypt and persist pending PINs before opening the browser and account tokens before discovering servers.
- Add Retry connection, Reopen Plex sign-in, and Pause connection; retries continue the same login after app restarts.
- Retry transient failures and fall back to Plex's alternate HTTPS API hostname for DNS/connection errors, retaining normal TLS verification.
- Distinguish expired/revoked authorization from temporary connectivity problems and honor the PIN's full advertised lifetime.
- Allow starting a new sign-in without clearing the saved server, music, downloads, or queued ratings.

## 0.4.0

- Add Android Auto discovery with an exported MediaLibraryService and the platform media-browser bridge.
- Browse Library, Downloads, Playlists, and Radio from the car, including artists, albums, genres, own files, and all four random modes.
- Add voice search, selected-playlist queues, playback resumption, and standard car transport controls.
- Expose queued one-star/five-star actions and star-rating requests. Car ratings remain local until manual sync on the phone.
- Apply offline filtering and the existing two-track setting in the car. Refresh subscribed car folders when the library changes.
- Use range folders for large catalogs and resolve playback only from known library IDs.
- Document Android Auto setup for GitHub APKs and limits on reusing Plezy/Plex downloads.

## 0.3.0

- Rename the launcher, app header, notifications, and Plex product name to Offline Plex music. Keep the same application ID and upgrade path.
- Add a visible Plex tab and direct Library connection/import controls.
- Sign in through Plex in your browser, discover accessible servers, and select a server to connect and import music in one step.
- Keep manual URL/token setup under Advanced connection. Saved connections can import or refresh directly.
- Explain streaming and offline downloads and provide a download-folder picker in the Plex screen.
- Preserve queued ratings and downloaded files during import. Connecting and importing never sync ratings or delete music.

## 0.2.0

- Download or remove one track directly from its library row.
- Download or remove an entire artist, album, or genre directly from its group row.
- Browse downloaded files under Downloads → On device; transfer progress is under Transfers.
- Remove downloads by artist, album, genre, exact rating threshold, selection, or all local music.
- Rating thresholds include queued changes, preserve half-star precision, and exclude unrated tracks unless explicitly included.
- Optionally exclude manually added music from cleanup.
- Review the exact file list before removing local downloads. Plex copies and queued ratings are preserved. Existing Plex deletion remains a separate action.
- Prevent the radio queue from reintroducing files while a removal is in progress.

## 0.1.0

- Plex original-file streaming and background downloads.
- Offline files, four random modes, two-track limit, playlists, queued ratings, and reviewed bulk deletion.
