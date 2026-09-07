package com.m3.pocketmusic

import android.content.Context
import android.content.ContentValues
import android.provider.MediaStore
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.documentfile.provider.DocumentFile
import android.provider.DocumentsContract
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.net.ServerSocket

class MusicInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val store get() = context.musicStore
    @Before fun seed() {
        store.credentials.saveLogin(PlexLogin())
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("pm grant com.m3.pocketmusic android.permission.POST_NOTIFICATIONS")).use { it.readBytes() }
        }
        val wav = File(context.filesDir, "test-audio.wav")
        // A generated, silent PCM WAV; no copyrighted or user media is involved.
        val samples = 44_100 * 6
        val bytes = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("RIFF".toByteArray()); bytes.putInt(36 + samples * 2); bytes.put("WAVEfmt ".toByteArray())
        bytes.putInt(16); bytes.putShort(1); bytes.putShort(1); bytes.putInt(44_100); bytes.putInt(88_200)
        bytes.putShort(2); bytes.putShort(16); bytes.put("data".toByteArray()); bytes.putInt(samples * 2)
        wav.writeBytes(bytes.array())
        compose.runOnUiThread { PlaybackService.instance?.reloadConnection() }
        store.update { LibraryState(tracks = listOf(
            Track("local:1", "Open Road", "North Coast", "Daylight", number = 1, genres = listOf("Indie"), localUri = wav.toURI().toString(), duration = 6000),
            Track("local:2", "Golden Hour", "North Coast", "Daylight", number = 2, genres = listOf("Indie"), localUri = wav.toURI().toString(), duration = 6000),
            Track("remote:3", "Night Drive", "Afterglow", "City Lights", remoteKey = "3", part = "/test.wav", genres = listOf("Electronic"))
        )) }
        compose.waitUntil(5000) { compose.onAllNodesWithText("Open Road").fetchSemanticsNodes().isNotEmpty() }
    }
    @Test fun equivalentLibrarySnapshotDoesNotLeaveLoadingScreenStuck() {
        store.update { it.copy(tracks = it.tracks.map { t -> t.copy() }, wifiOnlyDownloads = !it.wifiOnlyDownloads) }
        compose.waitUntil(5000) { compose.onAllNodesWithText("Open Road").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Artists", useUnmergedTree = true).performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("North Coast", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("North Coast", useUnmergedTree = true).performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Open Road").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(3, store.state.value.tracks.size)
    }
    @Test fun bulkArtistRatingStaysQueuedAndPersists() {
        compose.onNodeWithText("Artists", useUnmergedTree = true).performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Afterglow", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Afterglow", useUnmergedTree = true).performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Night Drive").fetchSemanticsNodes().isNotEmpty() }
        // Select all in the current artist view.
        compose.onNodeWithTag("select-all").performClick()
        compose.onNodeWithText("Rate", useUnmergedTree = true).performClick()
        compose.onNodeWithText("1★", useUnmergedTree = true).performClick()
        compose.waitUntil { store.state.value.tracks.first { it.id == "remote:3" }.pendingRating == 1 }
        assertEquals(0, store.state.value.tracks.first { it.id == "remote:3" }.serverRating)
        assertEquals(1, MusicStore(context).state.value.tracks.first { it.id == "remote:3" }.pendingRating)
    }
    @Test fun localPlaybackAndOfflineFilteringWork() {
        compose.onNodeWithText("Online", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Night Drive").assertDoesNotExist()
        compose.onNodeWithText("Start radio").performClick()
        compose.waitUntil(15_000) { PlaybackService.status.value.playing }
        assertTrue(PlaybackService.status.value.trackId in setOf("local:1", "local:2"))
        compose.onNodeWithContentDescription("Pause").performClick()
        compose.waitUntil { !PlaybackService.status.value.playing }
        screenshot("offline-player")
    }
    @Test fun localRatingAndPlaylistAreUsableWithoutPlex() {
        compose.onAllNodesWithText("☆", useUnmergedTree = true)[0].performClick()
        compose.onNodeWithText("5★", useUnmergedTree = true).performClick()
        assertEquals(5, store.state.value.tracks.first().localRating)
        assertNull(store.state.value.tracks.first().pendingRating)
        compose.onNodeWithText("Playlists", useUnmergedTree = true).performClick()
        compose.onNodeWithText("New playlist").performClick()
        compose.onNodeWithText("New playlist name").performTextInput("Road trip")
        compose.onNodeWithText("Create", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Road trip").assertExists()
        assertEquals("Road trip", store.state.value.playlists.single().name)
    }
    @Test fun cleanupRequiresReviewAndExplicitServerConfirmation() {
        store.rate(setOf("remote:3"), 1)
        compose.onNodeWithText("Clean 1★").performClick()
        compose.onNodeWithText("Review music deletion").assertExists()
        compose.onNodeWithText("Delete selected files").assertIsNotEnabled()
        compose.onNodeWithText("Cancel", useUnmergedTree = true).performClick()
        assertEquals(3, store.state.value.tracks.size)
        screenshot("library")
    }
    @Test fun downloadedFiltersReviewGroupsThresholdsAndIndividualFiles() {
        store.rate(setOf("local:1"), 1)
        compose.onNodeWithText("Downloads", useUnmergedTree = true).performClick()
        compose.onNodeWithText("2 matching tracks").assertExists()
        compose.onNodeWithText("Artist", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("download-filter-value").performClick()
        compose.onNodeWithText("North Coast", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Remove matching downloads").performClick()
        compose.onNodeWithText("Remove 2 local files?").assertExists()
        compose.onNodeWithText("Plex server", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Cancel", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("remove-download-local:1").performClick()
        compose.onNodeWithText("Remove 1 local files?").assertExists()
        compose.onNodeWithText("Cancel", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Rating below", useUnmergedTree = true).performScrollTo().performClick()
        compose.onNodeWithText("1 matching tracks").assertExists()
        compose.onNodeWithTag("include-unrated").performClick()
        compose.onNodeWithText("2 matching tracks").assertExists()
        compose.onNodeWithTag("include-own-files").performClick()
        compose.onNodeWithText("0 matching tracks").assertExists()
        compose.onNodeWithTag("include-own-files").performClick()
        assertEquals(2, store.state.value.tracks.count { it.downloaded })
        screenshot("download-cleanup")
    }
    @Test fun libraryOffersDirectGroupAndTrackRemovalReview() {
        compose.onNodeWithTag("track-download-local:1").performClick()
        compose.onNodeWithText("Remove 1 local files?").assertExists()
        compose.onNodeWithText("Cancel", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Artists", useUnmergedTree = true).performClick()
        compose.onNodeWithContentDescription("Remove downloads by North Coast").performClick()
        compose.onNodeWithText("Remove 2 local files?").assertExists()
        compose.onNodeWithText("Cancel", useUnmergedTree = true).performClick()
        assertEquals(3, store.state.value.tracks.size)
    }
    @Test fun radioContinuesWithActivityInBackground() {
        compose.onNodeWithText("Online", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Start radio").performClick()
        compose.waitUntil(15_000) { PlaybackService.status.value.playing }
        val first = PlaybackService.status.value.trackId
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        val deadline = System.currentTimeMillis() + 15_000
        while (PlaybackService.status.value.trackId == first && System.currentTimeMillis() < deadline) Thread.sleep(100)
        assertNotEquals(first, PlaybackService.status.value.trackId)
        assertTrue(PlaybackService.status.value.playing)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.onNodeWithContentDescription("Pause").assertExists()
    }
    @Test fun bulkDownloadScansOwnFilesAndDeletesOnlyLocalSelection() {
        val audio = File(context.filesDir, "test-audio.wav").readBytes()
        val socket = ServerSocket(0)
        val requests = java.util.concurrent.CopyOnWriteArrayList<String>()
        val holdSecond = java.util.concurrent.atomic.AtomicBoolean(false)
        val secondStarted = java.util.concurrent.CountDownLatch(1)
        val releaseSecond = java.util.concurrent.CountDownLatch(1)
        val thread = Thread {
            while (!socket.isClosed) runCatching {
                socket.accept().use { client ->
                    val reader = client.getInputStream().bufferedReader()
                    val first = reader.readLine().orEmpty(); requests += first
                    var line = reader.readLine(); while (!line.isNullOrEmpty()) line = reader.readLine()
                    val body = if (first.contains("/audio/")) audio else """{"MediaContainer":{"machineIdentifier":"test-server"}}""".toByteArray()
                    val type = if (first.contains("/audio/")) "audio/wav" else "application/json"
                    client.getOutputStream().use { out ->
                        out.write("HTTP/1.1 200 OK\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        if (first.startsWith("GET /audio/2.wav") && holdSecond.compareAndSet(true, false)) {
                            out.write(body, 0, 512); out.flush(); secondStarted.countDown()
                            releaseSecond.await(20, java.util.concurrent.TimeUnit.SECONDS)
                            out.write(body, 512, body.size - 512)
                        } else out.write(body)
                    }
                }
            }
        }.apply { isDaemon = true; start() }
        try {
            val tree = DocumentsContract.buildTreeDocumentUri("com.m3.pocketmusic.test.documents", "root")
            val folder = DocumentFile.fromTreeUri(context, tree)!!
            context.sendBroadcast(android.content.Intent().setComponent(android.content.ComponentName("com.m3.pocketmusic.test", "com.m3.pocketmusic.TestFolderGrant")))
            compose.waitUntil(10_000) { context.checkCallingOrSelfUriPermission(folder.uri, android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == android.content.pm.PackageManager.PERMISSION_GRANTED }
            assertTrue("Tree ${folder.uri}, type=${folder.type}, write permission=${context.checkCallingOrSelfUriPermission(folder.uri, android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)}", folder.canWrite())
            folder.listFiles().forEach { assertTrue(it.delete()) }
            store.credentials.save(PlexConfig("http://127.0.0.1:${socket.localPort}", "test-token", "test-server"))
            val tracks = (1..2).map { Track("download:$it", "Download $it", remoteKey = it.toString(), part = "/audio/$it.wav", extension = "wav", bytes = audio.size.toLong()) }
            store.update { LibraryState(tracks = tracks, folder = tree.toString(), playlists = listOf(Playlist("plex:first", "First playlist", listOf("download:1"), true), Playlist("plex:both", "Both songs", tracks.map { it.id }, true))) }
            compose.runOnUiThread { PlaybackService.instance?.reloadConnection() }
            compose.runOnUiThread { compose.activity.viewModelStore.clear() }
            compose.activityRule.scenario.recreate()
            compose.onNodeWithText("Download 1", useUnmergedTree = true).performClick()
            compose.waitUntil(15_000) { PlaybackService.status.value.playing }
            assertTrue(requests.any { it.startsWith("GET /audio/1.wav") })
            compose.onNodeWithText("Stop playback").performClick()
            val vm = MusicViewModel(context.applicationContext as MusicApp)
            // Turn Wi-Fi off on this dedicated emulator. The default policy must queue without HTTP.
            val audioRequests = requests.count { it.startsWith("GET /audio/") }
            shell("svc wifi disable")
            compose.waitUntil(15_000) { !onWifi() }
            compose.onNodeWithText("Downloads", useUnmergedTree = true).performClick()
            compose.onNodeWithText("Download Plex playlists").performClick()
            compose.onNodeWithTag("download-playlist-plex:first").performClick()
            compose.onNodeWithTag("playlist-download-options").performClick()
            compose.onNodeWithTag("playlist-download-confirm").performClick()
            compose.waitUntil { store.state.value.downloads.any { it.id == "download:1" } }
            Thread.sleep(1500)
            assertFalse(store.state.value.tracks.first().downloaded)
            assertEquals(audioRequests, requests.count { it.startsWith("GET /audio/") })
            assertEquals(1, MusicStore(context).state.value.downloads.size)
            shell("svc wifi enable")
            compose.waitUntil(20_000) { onWifi() }
            // No Resume action: WorkManager automatically starts the waiting queue.
            compose.waitUntil(45_000) { store.state.value.tracks.first().downloaded || store.state.value.downloads.any { it.state == "Failed" } }
            assertTrue(store.state.value.downloads.toString(), store.state.value.tracks.first().downloaded)
            // Arm interruption only now: streaming preloads must not consume the download latch.
            holdSecond.set(true)
            // The overlapping playlist skips the file already downloaded.
            compose.onNodeWithText("Download Plex playlists").performClick()
            compose.onNodeWithTag("download-playlist-plex:both").performClick()
            compose.onNodeWithTag("playlist-download-options").performClick()
            compose.onNodeWithTag("download-limited").performScrollTo().performClick()
            compose.onNodeWithTag("download-max-mb").performScrollTo().performTextReplacement("1")
            compose.onNodeWithTag("download-max-mb").performImeAction()
            compose.onNodeWithText("Queue 1 songs").assertExists()
            compose.onNodeWithTag("playlist-download-confirm").performClick()
            compose.waitUntil(15_000) { secondStarted.count == 0L }
            shell("svc wifi disable")
            compose.waitUntil(15_000) { !onWifi() }
            releaseSecond.countDown()
            try { compose.waitUntil(15_000) { store.state.value.downloads.any { it.id == "download:2" && it.state == "Queued" } } }
            catch (e: AssertionError) { throw AssertionError("Interrupted queue: ${store.state.value.downloads}; completed=${store.state.value.tracks.filter { it.downloaded }.map { it.id }}", e) }
            assertFalse(store.state.value.tracks.last().downloaded)
            assertTrue(store.state.value.tracks.first().downloaded)
            shell("svc wifi enable")
            compose.waitUntil(20_000) { onWifi() }
            compose.waitUntil(45_000) { store.state.value.tracks.all { it.downloaded } || store.state.value.downloads.any { it.state == "Failed" } }
            assertEquals(store.state.value.downloads.toString(), 2, store.state.value.tracks.count { it.downloaded })
            assertEquals(2, folder.listFiles().size)
            store.state.value.tracks.forEach { t ->
                assertArrayEquals(audio, context.contentResolver.openInputStream(android.net.Uri.parse(t.localUri))!!.use { it.readBytes() })
            }
            val own = folder.createFile("audio/wav", "My own song.wav")!!
            context.contentResolver.openOutputStream(own.uri)!!.use { it.write(audio) }
            runBlocking { LocalMusic.scan(context) { } }
            assertEquals(3, store.state.value.tracks.size)
            assertEquals(1, store.state.value.tracks.count { it.remoteKey.isBlank() })
            store.rate(setOf("download:1"), 1)
            compose.runOnUiThread { vm.removeDownloads(setOf("download:1")) }
            compose.waitUntil(10_000) { !vm.busy.value }
            assertFalse(store.state.value.tracks.first { it.id == "download:1" }.downloaded)
            assertTrue(store.state.value.tracks.first { it.id == "download:2" }.downloaded)
            assertEquals(2, folder.listFiles().size)
            assertTrue(requests.none { it.startsWith("DELETE") })
            assertEquals(1, store.state.value.tracks.first { it.id == "download:1" }.pendingRating)
            compose.runOnUiThread { vm.removeDownloads(store.state.value.tracks.filter { it.downloaded }.map { it.id }.toSet()) }
            compose.waitUntil(10_000) { !vm.busy.value }
            assertTrue(folder.listFiles().isEmpty())
            assertEquals(2, store.state.value.tracks.size)
            assertTrue(store.state.value.tracks.none { it.downloaded })
        } finally { releaseSecond.countDown(); shell("svc wifi enable"); socket.close(); thread.join(1000) }
    }
    @Test fun wifiDownloadPreferencePersistsAndDoesNotResumePausedQueue() {
        compose.onNodeWithText("Downloads", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Transfers (0)").performClick()
        compose.onNodeWithTag("download-wifi-only").assertIsOn().performClick()
        assertFalse(MusicStore(context).state.value.wifiOnlyDownloads)
        store.update { it.copy(downloads = listOf(DownloadJob("remote:3")), downloadsPaused = true) }
        compose.onNodeWithTag("download-wifi-only").performClick()
        assertTrue(store.state.value.downloadsPaused)
        assertEquals(1, store.state.value.downloads.size)
        assertTrue(MusicStore(context).state.value.wifiOnlyDownloads)
        screenshot("wifi-download-queue")
        compose.runOnUiThread { compose.activity.viewModelStore.clear() }
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Downloads", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Transfers (1)").performClick()
        compose.onNodeWithTag("download-wifi-only").assertIsOn()
        compose.onNodeWithText("Queue paused.", substring = true).assertExists()
    }
    private fun shell(command: String) {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)).use { it.readBytes() }
    }
    private fun onWifi(): Boolean {
        val manager = context.getSystemService(android.net.ConnectivityManager::class.java)
        val caps = manager.getNetworkCapabilities(manager.activeNetwork)
        return caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true && !caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)
    }
    @Test fun playlistDownloadsSkipExistingAndQueuedSongsAndPreserveRatings() {
        val tracks = (1..4).map { n -> Track("sample:$n", "Song $n", remoteKey = "$n", part = "/audio/$n", pendingRating = 1,
            localUri = if (n == 1) "file:///existing.wav" else "") }
        store.update { LibraryState(tracks = tracks, downloads = listOf(DownloadJob("sample:2")), playlists = listOf(
            Playlist("plex:a", "Road trip", tracks.take(3).map { it.id }, true),
            Playlist("plex:b", "Favorites", tracks.drop(2).map { it.id }, true),
            Playlist("local", "Local only", listOf("sample:4")))) }
        compose.onNodeWithText("Downloads", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Download Plex playlists").performClick()
        compose.onNodeWithTag("download-playlist-local").assertDoesNotExist()
        compose.onNodeWithTag("download-playlist-plex:a").performClick()
        compose.onNodeWithTag("download-playlist-plex:b").performClick()
        compose.onNodeWithTag("playlist-download-options").performClick()
        compose.onNodeWithText("Queue 2 songs").assertExists()
        compose.onNodeWithTag("playlist-download-confirm").assertIsNotEnabled()
        screenshot("playlist-downloads")
        assertEquals(1, store.state.value.downloads.size)
        assertTrue(store.state.value.tracks.all { it.pendingRating == 1 })
    }
    @Test fun playlistDownloadsEmptySelectionCannotQueue() {
        compose.onNodeWithText("Downloads", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Download Plex playlists").performClick()
        compose.onNodeWithText("No Plex playlists found.", substring = true).assertExists()
        compose.onNodeWithTag("playlist-download-options").assertIsNotEnabled()
        assertTrue(store.state.value.downloads.isEmpty())
    }
    @Test fun largePlaylistOpensAndLoadsNumericDownloadOptionsWithoutFreezing() {
        val tracks = (1..31_382).map { Track("large:$it", "Large song $it", "Artist ${it % 25}", "Album", artistId = "${it % 25}",
            remoteKey = "$it", part = "/audio/$it", bytes = 4_000_000, pendingRating = if (it == 1) 1 else null) }
        store.update { LibraryState(tracks = tracks, playlists = listOf(Playlist("plex:large", "Big playlist", tracks.map { it.id }, true))) }
        compose.onNodeWithText("Playlists", useUnmergedTree = true).performClick()
        val start = android.os.SystemClock.elapsedRealtime()
        compose.onNodeWithTag("open-playlist-plex:large").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Large song 1").fetchSemanticsNodes().isNotEmpty() }
        assertTrue("Large playlist exceeded ANR-sized opening time", android.os.SystemClock.elapsedRealtime() - start < 5000)
        compose.onNodeWithText("31382 tracks").assertExists()
        compose.onNodeWithTag("open-playlist-download").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Queue 31382 songs").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("download-limited").performScrollTo().performClick()
        compose.onNodeWithTag("download-max-mb").performScrollTo().performTextReplacement("20")
        compose.waitUntil(5000) { compose.onAllNodesWithText("Queue 5 songs").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("download-per-artist").performScrollTo().performClick()
        compose.onNodeWithTag("download-artist-count").performScrollTo().performTextReplacement("1")
        compose.onNodeWithTag("download-max-mb").performScrollTo().performTextReplacement("1000")
        compose.waitUntil(5000) { compose.onAllNodesWithText("Queue 25 songs").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("download-artist-count").performScrollTo().performTextReplacement("2")
        compose.waitUntil(5000) { compose.onAllNodesWithText("Queue 50 songs").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("download-max-mb").performScrollTo().performTextReplacement("0")
        compose.onNodeWithTag("playlist-download-confirm").assertIsNotEnabled()
        compose.onNodeWithTag("download-max-mb").performTextReplacement("200")
        compose.onNodeWithTag("download-max-mb").performImeAction()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Queue 50 songs").fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(5000) {
            var keyboardVisible = true
            compose.runOnIdle { keyboardVisible = androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true }
            !keyboardVisible
        }
        // Insets change before the keyboard slide-out animation finishes.
        Thread.sleep(500)
        screenshot("playlist-size-options")
        assertTrue(store.state.value.downloads.isEmpty())
        assertEquals(1, store.state.value.tracks.first().pendingRating)
    }
    @Test fun savedPlexLoginSurvivesRecreationEncryptedAndCanResetIndependently() {
        val savedServer = PlexConfig("https://unit.invalid", "test-existing-server-token", "test-existing-server")
        store.credentials.save(savedServer)
        store.rate(setOf("remote:3"), 1)
        val pinState = PlexLogin(PlexPin(42, "test-pending-pin", 1800), System.currentTimeMillis() + 1_800_000)
        store.credentials.saveLogin(pinState)
        assertEquals(pinState, Credentials(context).readLogin())
        assertFalse(context.getSharedPreferences("connection", Context.MODE_PRIVATE).all.toString().contains("test-pending-pin"))
        compose.runOnUiThread { compose.activity.viewModelStore.clear() }
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Plex", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Retry connection").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Reopen Plex sign-in").assertExists()
        compose.onNodeWithText("Sign in with Plex").assertDoesNotExist()

        val authorized = PlexLogin(token = "test-saved-account-token")
        store.credentials.saveLogin(authorized)
        compose.runOnUiThread { compose.activity.viewModelStore.clear() }
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Plex", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Plex sign-in saved").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Reopen Plex sign-in").assertDoesNotExist()
        assertEquals(authorized, Credentials(context).readLogin())
        assertFalse(context.getSharedPreferences("connection", Context.MODE_PRIVATE).all.toString().contains("test-saved-account-token"))
        screenshot("plex-login-recovery")
        compose.onNodeWithText("Start a new sign-in / change account").performScrollTo().performClick()
        assertFalse(Credentials(context).readLogin().pending)
        assertEquals(savedServer, Credentials(context).read())
        assertEquals(1, store.state.value.tracks.first { it.id == "remote:3" }.pendingRating)
    }
    @Test fun emptyLibraryShowsPlexSetupAndCorrectAppName() {
        store.credentials.save(PlexConfig())
        store.update { LibraryState() }
        compose.runOnUiThread { compose.activity.viewModelStore.clear() }
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Offline Plex music").assertIsDisplayed()
        assertEquals("Offline Plex music", context.applicationInfo.loadLabel(context.packageManager).toString())
        screenshot("empty-library")
        compose.onNodeWithText("Connect Plex").assertIsDisplayed().performClick()
        compose.onNodeWithText("Sign in with Plex").assertIsDisplayed()
        screenshot("plex-connect")
        compose.onNodeWithText("Advanced connection (server URL + token)").performScrollTo().performClick()
        compose.onNodeWithText("Plex server URL").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("X-Plex-Token").assertExists()
    }
    @Test fun visiblePlexConnectionImportsAndStreamsWithoutSyncingRatings() {
        val audio = File(context.filesDir, "test-audio.wav").readBytes()
        val socket = ServerSocket(0)
        val requests = java.util.concurrent.CopyOnWriteArrayList<String>()
        val thread = Thread {
            while (!socket.isClosed) runCatching {
                socket.accept().use { client ->
                    val reader = client.getInputStream().bufferedReader()
                    val first = reader.readLine().orEmpty(); requests += first
                    var line = reader.readLine(); while (!line.isNullOrEmpty()) line = reader.readLine()
                    val path = first.substringAfter(' ').substringBefore(' ')
                    val json = when {
                        path == "/" -> """{"MediaContainer":{"machineIdentifier":"test-server"}}"""
                        path == "/library/sections" -> """{"MediaContainer":{"Directory":[{"type":"artist","key":"1","title":"Music"}]}}"""
                        path.contains("type=10") -> """{"MediaContainer":{"Metadata":[{"type":"track","ratingKey":"1","title":"Imported song","grandparentTitle":"Plex artist","userRating":8,"Media":[{"container":"wav","Part":[{"key":"/audio/1.wav"}]}]}]}}"""
                        else -> """{"MediaContainer":{"Metadata":[]}}"""
                    }
                    val body = if (path.startsWith("/audio/")) audio else json.toByteArray()
                    val type = if (path.startsWith("/audio/")) "audio/wav" else "application/json"
                    client.getOutputStream().use { out ->
                        out.write("HTTP/1.1 200 OK\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray()); out.write(body)
                    }
                }
            }
        }.apply { isDaemon = true; start() }
        try {
            store.credentials.save(PlexConfig())
            store.update { LibraryState(tracks = listOf(Track("plex:test-server:1", "Old metadata", remoteKey = "1", pendingRating = 1))) }
            compose.runOnUiThread { compose.activity.viewModelStore.clear() }
            compose.activityRule.scenario.recreate()
            compose.onNodeWithText("Connect Plex").performClick()
            compose.onNodeWithText("Advanced connection (server URL + token)").performScrollTo().performClick()
            compose.onNodeWithText("Plex server URL").performScrollTo().performTextInput("http://127.0.0.1:${socket.localPort}")
            compose.onNodeWithText("X-Plex-Token").performScrollTo().performTextInput("test-token")
            compose.onNodeWithText("Connect & import music").performScrollTo().performClick()
            compose.waitUntil(15_000) { store.state.value.tracks.any { it.title == "Imported song" } }
            assertEquals(1, store.state.value.tracks.single().pendingRating)
            assertEquals("test-server", store.credentials.read().serverId)
            compose.onNodeWithText("Open library to play").performScrollTo().performClick()
            compose.onNodeWithText("Imported song", useUnmergedTree = true).performClick()
            compose.waitUntil(15_000) { PlaybackService.status.value.playing }
            assertTrue(requests.any { it.startsWith("GET /audio/1.wav") })
            assertTrue(requests.all { it.startsWith("GET ") })
            compose.onNodeWithContentDescription("Pause").performClick()
            screenshot("plex-imported")
        } finally { socket.close(); thread.join(1000) }
    }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$name.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PocketMusic-Test")
        })!!
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let { bitmap ->
            context.contentResolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
    }
}
