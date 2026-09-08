# Offline Plex Music 0.11.0

- Fix offline downloads incorrectly failing because the completed file differs from Plex's cached size metadata. After installing, open **Downloads → Transfers → Resume / retry** for the earlier failed jobs. Truncated transfers are still rejected; completed songs are kept.
- New **Now playing** mobile screen: artwork, artist/album/title, seeking, previous/play/pause/next, shuffle, next artist, stop, ratings, local deletion, Plex deletion flags, sync review, and playback queue. Tap the compact player's song information to open it.
- New **Selected playlist** editor: open **Playlists → Edit** to rename, add songs, move entries to any position, remove entries, shuffle the order, or save a local copy.
- Plex playlist changes are saved locally until **Sync playlist** is reviewed and confirmed. Sync preserves duplicates, verifies the result, and retains drafts on failure or conflict. **Reload from Plex** discards local edits; save a local copy first if desired. Smart playlists are read-only with local copying available.

Install the APK over the existing app. The package and development signing certificate are unchanged. Android 8.0+ is required.

Tests use synthetic media and local HTTP fixtures; real Plex server permissions and physical car display behavior are not verified. Playlist synchronization changes membership/name only, not song files or ratings.
