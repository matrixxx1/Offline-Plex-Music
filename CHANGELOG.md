# Changelog

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
