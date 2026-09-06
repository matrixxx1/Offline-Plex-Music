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
        compose.waitForIdle()
    }
    @Test fun bulkArtistRatingStaysQueuedAndPersists() {
        compose.onNodeWithText("Artists", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Afterglow", useUnmergedTree = true).performClick()
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
        val thread = Thread {
            while (!socket.isClosed) runCatching {
                socket.accept().use { client ->
                    val reader = client.getInputStream().bufferedReader()
                    val first = reader.readLine().orEmpty(); requests += first
                    var line = reader.readLine(); while (!line.isNullOrEmpty()) line = reader.readLine()
                    val body = if (first.contains("/audio/")) audio else """{"MediaContainer":{"machineIdentifier":"test-server"}}""".toByteArray()
                    val type = if (first.contains("/audio/")) "audio/wav" else "application/json"
                    client.getOutputStream().use { out ->
                        out.write("HTTP/1.1 200 OK\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray()); out.write(body)
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
            store.update { LibraryState(tracks = tracks, folder = tree.toString()) }
            compose.runOnUiThread { PlaybackService.instance?.reloadConnection() }
            compose.onNodeWithText("Download 1", useUnmergedTree = true).performClick()
            compose.waitUntil(15_000) { PlaybackService.status.value.playing }
            assertTrue(requests.any { it.startsWith("GET /audio/1.wav") })
            compose.onNodeWithContentDescription("Pause").performClick()
            val vm = MusicViewModel(context.applicationContext as MusicApp)
            compose.onNodeWithTag("track-download-download:1").performClick()
            compose.waitUntil(30_000) { store.state.value.tracks.first().downloaded || store.state.value.downloads.any { it.state == "Failed" } }
            assertTrue(store.state.value.downloads.toString(), store.state.value.tracks.first().downloaded)
            compose.runOnUiThread { vm.downloads(tracks.map { it.id }.toSet()) }
            compose.waitUntil(30_000) { store.state.value.tracks.all { it.downloaded } || store.state.value.downloads.any { it.state == "Failed" } }
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
        } finally { socket.close(); thread.join(1000) }
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
