# Offline Plex Music

A native Kotlin/Compose music player for a Plex music library and a folder of local audio. The Android app is named **Pocket Music**. Android 8.0 or newer. Version 0.2.0 is a sideloadable development build.

[Download the latest APK](https://github.com/matrixxx1/Offline-Plex-Music/releases/latest) · [Changelog](CHANGELOG.md)

## Features

- Original-quality Plex streaming and bulk downloads, with background playback, notification/headset controls, next, previous, seeking, and a playback queue.
- Direct download/remove buttons on each track and each artist, album, or genre. Downloads → On device supports filtering and reviewed removal of one track, selected tracks, a whole artist/album/genre, everything below a chosen rating, or all downloaded files.
- Offline-only mode: plays downloaded and manually added files. Choose a dedicated folder using Android's folder picker. Copy your own files into that folder or subfolders and scan it.
- Track, artist, album, genre, and playlist browsing; search and select-all apply to the current view. Artist/album/genre checkboxes select the entire group for bulk operations.
- One-to-five-star ratings. Plex changes are stored durably on the device, coalesced per track, and **only sent when you explicitly choose Sync ratings**. Failed changes stay queued. A new rating made during a sync stays queued too. Local-only music has local ratings.
- **Clean 1★** reviews exactly one-star tracks in the current view, including queued ratings. Device and Plex deletion are independently selectable. Plex deletion requires typing `DELETE` and server permission. Rating or syncing never deletes music. Existing Plex half-star ratings are displayed and excluded from one-star cleanup unless exactly one star.
- Local playlists: create, add selected tracks, remove tracks, reorder tracks, delete a playlist without deleting its music. Existing Plex audio playlists are imported by Refresh Plex and can be used as a playback, radio, rating, and download scope.
- Download jobs persist across restarts; pause, resume/retry, and cancel queue. Completed downloads remain after canceling. Interrupted files restart from the beginning; byte count is checked before they become available offline.

## Random modes

| Mode | One task |
|---|---|
| Random Track | One fresh random track |
| Random Album | A random album, in disc/track order |
| Random Artist | Every track by one random artist, grouped by album and disc/track order |
| Random Genre | Every track in one random genre, grouped by album and disc/track order |

After a task finishes, another group is selected randomly. The immediately previous track/group is avoided when alternatives exist. With **2 Track limit**, album/artist/genre tasks stop after their first two tracks (or fewer if only one is available). Skipping forward consumes that queue position. Random Track always changes after one track. Radio uses the current filtered view or playlist. Play a row to play that visible list sequentially. Mode changes take effect after the currently playing track.

## First setup

1. Install `Offline-Plex-Music-0.2.0.apk` from the GitHub release (or `artifacts` after building locally) on Android.
2. In **Settings**, enter your Plex server URL and `X-Plex-Token`, then **Save & test**. Use the account whose ratings you want to update. HTTPS is preferred; HTTP is enabled for trusted local Plex networks. Tokens are encrypted using Android Keystore and app-data backup/transfer is excluded.
3. Tap **Refresh Plex** to import accessible music libraries and audio playlists. This is read-only; it never sends queued ratings.
4. Choose a folder such as `Music/PocketMusic`. Android may prohibit selecting the root of Downloads; choose a dedicated subfolder under Music instead.
5. Select tracks or whole artists/albums/genres, then **Download**. Permit notifications to see background download status. Copy your own music into the chosen folder and tap **Scan folder** when downloads are finished or canceled.
6. Tap **Online** at the top to switch to **Offline**. Downloads pause and playback uses only local files. To resume downloads, switch online and use **Downloads → Transfers → Resume / retry**.

### Manage downloaded music

- Tap the download icon on a track or group to save it; tap its trash icon to review removing its local files.
- Open **Downloads → On device**, then choose **All downloads**, **Artist**, **Album**, **Genre**, or **Rating below**. Choose the artist/album/genre from the dropdown or adjust the rating slider. Search further narrows the list.
- Rating cleanup is strictly below the selected threshold: below 3★ includes 1★, 2★, and 2.5★, but not 3★. It uses unsynced ratings too. Unrated music is excluded unless **Include unrated tracks** is checked.
- Uncheck **Include music I added myself** to limit cleanup to Plex downloads. When your own files are included, the confirmation explicitly says they will be deleted from your chosen music folder.
- Use **Remove matching downloads**, **Remove selected**, or **Remove all downloads** and review the exact list before confirming. These actions remove local files only; they keep Plex copies and queued ratings. Plex tracks remain streamable and can be downloaded again.
- For permanent Plex-server deletion, use the existing Library selection → **Delete** action and its separate server confirmation.

Plex token help: [Finding an authentication token](https://support.plex.tv/articles/204059436-finding-an-authentication-token-x-plex-token/).

For the example “rate an artist one star and remove them”: open Artists, select that artist's checkbox, choose Rate → 1★, optionally choose Sync ratings and review it, then choose Delete and review device/Plex targets. Alternatively, open that artist and use Clean 1★. A deletion does not automatically sync ratings first.

## Build and verification

The Windows workspace is inside OneDrive. `build.ps1` keeps generated Android outputs in `%LOCALAPPDATA%\PocketMusic-build` to avoid sync locks and copies the resulting APK back into `artifacts`.

```powershell
.\build.ps1
# With an emulator connected:
.\build.ps1 -Tasks connectedDebugAndroidTest
```

Elsewhere, use JDK 17+ (tested here with JDK 23), Android SDK platform 37, and `./gradlew testDebugUnitTest lintDebug assembleDebug`. Configure the SDK through `local.properties` or `ANDROID_HOME`. The wrapper uses Gradle 9.5.0.

Tests cover group selection/order/two-track behavior, exact-star cleanup, rating queue persistence and concurrent acknowledgments, paginated Plex music import, inherited genres, rating request/readback, and refusing non-track or mismatched-server deletion. Emulator tests use synthetic silent WAV files and a local HTTP fixture; test fixtures and the test document provider are only in the androidTest APK.

## Current limits

- A real Plex server/account has not been configured or tested in this workspace. Live authentication, library-specific metadata, permissions, streaming formats, and server deletion still need checking against your server. No real Plex media has been changed or deleted.
- Connection is manual URL/token; Plex account login, automatic discovery, Plex Home switching, and multiple servers are not included. A library is bound to one server identity to avoid sending queued changes to another server.
- Streams and downloads use the original audio file. There is no transcoding or Plex Relay negotiation. Codec support depends on Android/Media3. WMA and other unsupported codecs may be indexed but fail playback with an error.
- Local playlists do not sync edits back to Plex. Refresh imports Plex playlists; local copies and order edits remain on this device. Ratings are not written into audio-file tags.
- Android may pause long downloads under battery or background-job limits. Use Resume/retry to continue. Partial files restart rather than using HTTP range resume.
- The library is an atomic JSON snapshot and is loaded in memory. Very large music collections have not been performance-tested. Refresh/scans are foreground app operations; finished downloads, playlists, settings, and ratings persist, but the active playback queue is not restored after process death.
- This is a debug-signed build for testing, not a Play Store release. Back up music before deliberately using permanent server deletion.

API references: [Plex Media Server API](https://developer.plex.tv/pms/) and [Android Media3 background playback](https://developer.android.com/media/media3/session/background-playback).
