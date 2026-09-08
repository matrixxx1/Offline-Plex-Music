# Offline Plex Music 0.10.0

Playlists is now the second tab on the phone and Android Auto. The phone Library adds Play, Shuffle, and Jump to artist while retaining individual song selection and next/previous controls.

Android Auto's full playback screen exposes Shuffle playlist, Next artist, and Rating in the action overflow. Rating cycles through 0–5 stars. More song actions opens Delete downloaded copy, Flag/Unflag Plex deletion, and Stop. Back returns to the playback actions. Car hosts control the exact placement.

Plex deletion is now a saved, reversible flag. On the phone, Sync reviews ratings and deletion flags. Server deletion requires explicit confirmation; rating-only sync leaves flagged files alone. Failed changes stay queued, and downloaded copies are retained when deleting from Plex.

Album art is retrieved while browsing or playing and cached for offline reuse, including Android Auto now-playing art. The cache is limited to 32 MiB and can be evicted by Android under storage pressure.

Install `Offline-Plex-Music-0.10.0.apk` over the existing app. This is the same debug-signed development package (`com.m3.pocketmusic`), Android 8.0 or newer. Reconnect Android Auto after updating to refresh its media controls.

Tests use synthetic media and local HTTP fixtures. Physical car display placement and a real Plex server are not verified by those tests.
