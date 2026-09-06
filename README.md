# Offline Plex Music

A native Kotlin/Compose music player for a Plex music library and a folder of local audio. The Android app is named **Offline Plex music**. Android 8.0 or newer. Version 0.6.0 is a sideloadable development build.

[Download the latest APK](https://github.com/matrixxx1/Offline-Plex-Music/releases/latest) · [Changelog](CHANGELOG.md)

## Features

- Visible Plex tab with browser sign-in, server discovery, and one-step connection/import. Manual URL/token setup is available under Advanced connection.
- Android Auto media browsing, playback, voice search, four radio modes, and queued rating controls. See setup below for sideloaded APKs.
- Original-quality Plex streaming and bulk downloads, with background playback, notification/headset controls, next, previous, seeking, and a playback queue.
- Smart downloads by genre, artist, album, or existing Plex mood/style tags: whole groups, a random total, or X new songs per group, with an editable preview and size estimate.
- Direct download/remove buttons on each track and each artist, album, or genre. Downloads → On device supports filtering and reviewed removal of one track, selected tracks, a whole artist/album/genre, everything below a chosen rating, or all downloaded files.
- Offline-only mode: plays downloaded and manually added files. Choose a dedicated folder using Android's folder picker. Copy your own files into that folder or subfolders and scan it.
- Track, artist, album, genre, and playlist browsing; search and select-all apply to the current view. Artist/album/genre checkboxes select the entire group for bulk operations.
- One-to-five-star ratings. Plex changes are stored durably on the device, coalesced per track, and **only sent when you explicitly choose Sync ratings**. Failed changes stay queued. A new rating made during a sync stays queued too. Local-only music has local ratings.
- **Clean 1★** reviews exactly one-star tracks in the current view, including queued ratings. Device and Plex deletion are independently selectable. Plex deletion requires typing `DELETE` and server permission. Rating or syncing never deletes music. Existing Plex half-star ratings are displayed and excluded from one-star cleanup unless exactly one star.
- Local playlists: create, add selected tracks, remove tracks, reorder tracks, delete a playlist without deleting its music. Existing Plex audio playlists are imported with your library and can be used as a playback, radio, rating, and download scope.
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

1. Install `Offline-Plex-Music-0.6.0.apk` from the GitHub release (or `artifacts` after building locally) on Android.
2. Tap **Connect Plex** in Library, or open the **Plex** tab. Choose **Sign in with Plex**, authorize in your browser, and return to the app. Select your server and tap **Connect & import music**. Alternatively, expand **Advanced connection** and enter a server URL and `X-Plex-Token`. Server tokens are encrypted using Android Keystore and app-data backup/transfer is excluded.
3. Open **Library** and tap a track to stream immediately. **Plex → Import / refresh music** or **Library → Import music** updates accessible music libraries and audio playlists. Import never sends queued ratings or deletes media.
4. For offline listening, use **Plex → Choose download folder** or **Settings → Choose folder**, such as `Music/OfflinePlexMusic`. Android may prohibit selecting the root of Downloads; choose a dedicated subfolder under Music instead.
5. Select tracks or whole artists/albums/genres, then **Download**. Permit notifications to see background download status. Copy your own music into the chosen folder and tap **Scan folder** when downloads are finished or canceled.
6. Tap **Online** at the top to switch to **Offline**. Downloads pause and playback uses only local files. To resume downloads, switch online and use **Downloads → Transfers → Resume / retry**.

### Recover a Plex login after a network error

If the browser says you successfully signed in but the app cannot resolve or reach Plex, return to the app and tap **Retry connection**. The pending PIN is saved before opening the browser. Once Plex returns an account token, it is saved before server discovery. Both are encrypted with Android Keystore and survive app restarts. Retrying continues that login without reopening the browser; **Reopen Plex sign-in** reuses the same pending PIN if authorization is unfinished.

The app retries temporary network/server errors and can use Plex's alternate HTTPS API host when the primary host fails DNS resolution or connection. It never disables certificate verification or changes phone network settings. If access remains blocked, try Wi-Fi or check mobile-data/VPN access for this app, then retry. An expired PIN or revoked account token requires a new sign-in; ordinary connection failures do not. **Start a new sign-in / change account** clears saved sign-in recovery state while preserving your server configuration, library, downloads, and queued ratings.

Version 0.4.0 and earlier did not save unfinished logins, so an already-lost login cannot be recovered after upgrading. Begin one new sign-in on 0.5.0 or newer; subsequent retries preserve it.

### Smart downloads

Open **Downloads → Smart download from Plex**, or use the same button on your saved Plex connection.

1. Choose **All music**, **Genres**, **Artists**, **Albums**, **Moods**, or **Styles**. For groups, use the chooser to search and select one, several, or all groups. Choices come from Plex songs still available to download; blank and duplicate tags are omitted.
2. Choose **All matching songs**, **Random songs, total**, or **Random songs per genre/artist/album/mood/style**, then enter X. For example: Genres → all genres → Random songs per genre → 10. Artists → selected artists → Random songs per artist → 5. Albums → selected albums → All matching songs downloads the remaining songs from those albums.
3. Tap **Preview downloads** to see the actual songs and estimated original-file size. Unknown sizes are labeled. Uncheck individual songs to leave them out or use **Reshuffle preview** for another random sample.
4. Choose a download folder if needed, then tap **Download N songs**. Transfers use the existing durable queue, progress notification, and pause/retry controls.

Counts mean **new downloads**, not a target total on the device. Existing downloads and queued jobs (including failed jobs awaiting retry) are skipped. Per-group sampling selects up to X songs independently from each selected group; smaller groups contribute what they have. A track shared by several genres/tags downloads once, so the total can be less than X times the number of groups. Overlapping tags may make a group appear on more than X of the final songs. The preview stays fixed until you edit or reshuffle it; confirming never silently substitutes different songs.

After upgrading, tap **Refresh Plex music & tags** once to import mood/style metadata. The app uses existing tags Plex returns for tracks, albums, and artists; album/artist tags include their tracks. Happy, angry, and sad appear only if those tags exist in the returned library metadata. The app does not infer moods, create tags, or write them back to Plex. Availability and completeness depend on your Plex library and server metadata. See the [Plex API response customization documentation](https://developer.plex.tv/pms/) and the [PlexAPI audio metadata implementation](https://github.com/pushingkarmaorg/python-plexapi/blob/master/plexapi/audio.py).

Smart download selection runs locally on imported metadata and can be previewed offline. Starting transfers requires online mode and a saved Plex connection. It does not sync ratings, delete files, or change your radio playback mode.

### Manage downloaded music

- Tap the download icon on a track or group to save it; tap its trash icon to review removing its local files.
- Open **Downloads → On device**, then choose **All downloads**, **Artist**, **Album**, **Genre**, or **Rating below**. Choose the artist/album/genre from the dropdown or adjust the rating slider. Search further narrows the list.
- Rating cleanup is strictly below the selected threshold: below 3★ includes 1★, 2★, and 2.5★, but not 3★. It uses unsynced ratings too. Unrated music is excluded unless **Include unrated tracks** is checked.
- Uncheck **Include music I added myself** to limit cleanup to Plex downloads. When your own files are included, the confirmation explicitly says they will be deleted from your chosen music folder.
- Use **Remove matching downloads**, **Remove selected**, or **Remove all downloads** and review the exact list before confirming. These actions remove local files only; they keep Plex copies and queued ratings. Plex tracks remain streamable and can be downloaded again.
- For permanent Plex-server deletion, use the existing Library selection → **Delete** action and its separate server confirmation.

Plex token help: [Finding an authentication token](https://support.plex.tv/articles/204059436-finding-an-authentication-token-x-plex-token/).

For the example “rate an artist one star and remove them”: open Artists, select that artist's checkbox, choose Rate → 1★, optionally choose Sync ratings and review it, then choose Delete and review device/Plex targets. Alternatively, open that artist and use Clean 1★. A deletion does not automatically sync ratings first.

## Android Auto

1. Install the APK on your phone and finish Plex connection/import or scan local music before driving.
2. Because this APK comes from GitHub, enable **Unknown sources** in Android Auto's developer settings if it is missing from the car launcher. Open Android Auto settings, expand the version information, tap it ten times, accept developer mode, then open the overflow menu → Developer settings → Unknown sources. Reconnect Android Auto and check Customize launcher. See [Google's sideload testing instructions](https://developer.android.com/training/cars/testing#unknown-sources).
3. Open **Offline Plex music** on the car display. The four sections are **Library**, **Downloads**, **Playlists**, and **Radio**. Library contains artists, albums, genres, and tracks. Downloads includes your own scanned files.
4. Use the car's play/pause, next/previous, seek, and voice-search controls. Previous follows normal Android media behavior: after a few seconds it restarts the current song; press again to go to the previous song. Phone controls retain their direct previous-track behavior.
5. Radio uses the phone's **2 Track limit** setting. The custom **Rate 1 star** and **Rate 5 stars** actions, plus star ratings from hosts that expose them, save on the phone. **Sync ratings** remains manual on the phone. Available custom-action placement depends on your car host.

Offline mode applies to car browsing, search, and radio. The car interface uses your cached library and can start before the phone UI opens. Set up Plex, manage downloads, sync ratings, and review deletions on the phone. This is Android Auto projection from your phone, not a separate app installed into Android Automotive OS.

Large browse lists use range folders of at most 100 items. Car search returns up to 100 matches; narrow the query for more specific results. A sequential car queue contains up to 500 tracks around the selected song; radio continues selecting new groups. A physical Android Auto head unit / Desktop Head Unit session has not been available for visual verification. Automated tests exercise both the Android platform browser/transport bridge used by car hosts and the Media3 browser.

## Reuse downloads from other apps

Select an accessible folder with **Settings → Choose folder**, then **Scan folder**. The app reads audio files in place and includes subfolders; it does not copy them. This also sets the destination for future downloads.

Plezy supports a custom shared download folder; music in such a folder can be scanned. Plezy's default storage is app-private, and Plex's private offline cache is generally unavailable to another app. Android 11+ prevents the system folder picker from granting access to other apps' `Android/data` folders. See [Plezy's storage implementation](https://github.com/edde746/plezy/blob/main/lib/services/download_storage_service.dart) and [Android's folder restrictions](https://developer.android.com/training/data-storage/shared/documents-files#document-tree-access-restrictions).

Scanning imports audio files as local tracks; it does not import Plezy/Plex download databases, playlists, or Plex track identities. Ratings on these scanned tracks stay local. Deleting a scanned file removes the original file from that shared folder, so the other app will lose that copy too. No files on the user's phone were inspected during development.

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
- Plex browser sign-in and server discovery are supported. Plex Home profile switching and combining multiple servers are not included. A library is bound to one server identity to avoid sending queued changes to another server. Pending sign-in details and the account token are encrypted on the device so discovery can resume after network failure or process death; the selected server token is saved separately.
- Streams and downloads use the original audio file. There is no transcoding; connection URLs, including available relay URLs, come from Plex discovery. Codec support depends on Android/Media3. WMA and other unsupported codecs may be indexed but fail playback with an error.
- Local playlists do not sync edits back to Plex. Refresh imports Plex playlists; local copies and order edits remain on this device. Ratings are not written into audio-file tags.
- Android may pause long downloads under battery or background-job limits. Use Resume/retry to continue. Partial files restart rather than using HTTP range resume.
- The library is an atomic JSON snapshot and is loaded in memory. Very large music collections have not been performance-tested. Refresh/scans are foreground app operations; finished downloads, playlists, settings, and ratings persist, but the active playback queue is not restored after process death.
- This is a debug-signed build for testing, not a Play Store release. Back up music before deliberately using permanent server deletion.

API references: [Plex browser PIN sign-in](https://forums.plex.tv/t/authenticating-with-plex/609370), [Plex Media Server API](https://developer.plex.tv/pms/) and [Android Media3 background playback](https://developer.android.com/media/media3/session/background-playback).
