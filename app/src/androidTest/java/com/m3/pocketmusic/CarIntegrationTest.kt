@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.m3.pocketmusic

import android.content.ComponentName
import android.content.Intent
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.session.SessionToken
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Exercises the actual platform browser/transport bridge used by car hosts, without a phone UI. */
class CarIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val store get() = context.musicStore
    private var browser: MediaBrowser? = null
    private lateinit var controller: MediaController
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun until(condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 15_000
        while (!condition() && System.currentTimeMillis() < end) Thread.sleep(50)
        assertTrue("Timed out waiting for media service", condition())
    }
    @Before fun seed() {
        main { context.stopService(Intent(context, PlaybackService::class.java)) }
        until { PlaybackService.instance == null }
        val wav = File(context.filesDir, "car-test.wav")
        val samples = 44_100 * 30
        val bytes = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("RIFF".toByteArray()); bytes.putInt(36 + samples * 2); bytes.put("WAVEfmt ".toByteArray())
        bytes.putInt(16); bytes.putShort(1); bytes.putShort(1); bytes.putInt(44_100); bytes.putInt(88_200)
        bytes.putShort(2); bytes.putShort(16); bytes.put("data".toByteArray()); bytes.putInt(samples * 2)
        wav.writeBytes(bytes.array())
        store.credentials.save(PlexConfig())
        store.update { LibraryState(tracks = listOf(
            Track("car-a", "First car song", "Car artist", "Car album", number = 1, duration = 30_000, remoteKey = "1", localUri = wav.toURI().toString()),
            Track("car-b", "Second car song", "Car artist", "Car album", number = 2, duration = 30_000, localUri = wav.toURI().toString()),
            Track("car-c", "Streaming only", "Remote artist", part = "/audio/3", remoteKey = "3")
        ), playlists = listOf(Playlist("car-list", "Driving", listOf("car-b", "car-a")))) }
    }
    @After fun stop() {
        main {
            // Stop foreground playback before unbinding; a car leaving must not be simulated as
            // an actively playing service being force-stopped while its transport call is queued.
            PlaybackService.instance?.reloadConnection()
            browser?.disconnect()
        }
        instrumentation.waitForIdleSync()
        main {
            context.stopService(Intent(context, PlaybackService::class.java))
        }
        until { PlaybackService.instance == null }
    }
    private fun connect() {
        val services = context.packageManager.queryIntentServices(Intent("android.media.browse.MediaBrowserService").setPackage(context.packageName), 0)
        assertTrue(services.any { it.serviceInfo.name == PlaybackService::class.java.name && it.serviceInfo.exported })
        val info = context.packageManager.getApplicationInfo(context.packageName, android.content.pm.PackageManager.GET_META_DATA)
        assertTrue(info.metaData.getInt("com.google.android.gms.car.application") != 0)
        val connected = CountDownLatch(1)
        main {
            browser = MediaBrowser(context, ComponentName(context, PlaybackService::class.java), object : MediaBrowser.ConnectionCallback() {
                override fun onConnected() { controller = MediaController(context, browser!!.sessionToken); connected.countDown() }
                override fun onConnectionFailed() { connected.countDown() }
            }, null).also { it.connect() }
        }
        assertTrue(connected.await(15, TimeUnit.SECONDS))
        main { assertTrue(browser!!.isConnected) }
    }
    private fun children(parent: String): List<MediaBrowser.MediaItem> {
        val loaded = CountDownLatch(1)
        var result: List<MediaBrowser.MediaItem>? = null
        main { browser!!.subscribe(parent, object : MediaBrowser.SubscriptionCallback() {
            override fun onChildrenLoaded(parentId: String, children: MutableList<MediaBrowser.MediaItem>) { result = children; loaded.countDown() }
            override fun onError(parentId: String) { loaded.countDown() }
        }) }
        assertTrue("Browse $parent timed out", loaded.await(15, TimeUnit.SECONDS))
        return requireNotNull(result) { "Browse $parent failed" }
    }
    @Test fun legacyCarBrowserPlaysPlaylistSkipsSeeksAndQueuesRatings() {
        connect()
        assertEquals(4, children(CarCatalog.ROOT).size)
        val playlist = children("playlists").single()
        val songs = children(playlist.mediaId!!)
        assertEquals(listOf("Second car song", "First car song"), songs.map { it.description.title.toString() })
        main { controller.transportControls.playFromMediaId(songs.first().mediaId, Bundle.EMPTY) }
        until { PlaybackService.status.value.playing && PlaybackService.status.value.trackId == "car-b" }
        main { controller.transportControls.skipToNext() }
        until { PlaybackService.status.value.trackId == "car-a" }
        main { controller.transportControls.sendCustomAction(PlaybackService.RATE_ONE, Bundle.EMPTY) }
        until { store.state.value.tracks.first().pendingRating == 1 }
        assertEquals(1, MusicStore(context).state.value.tracks.first().pendingRating)
        assertEquals(0, store.state.value.tracks.first().serverRating)
        main { controller.transportControls.setRating(android.media.Rating.newStarRating(android.media.Rating.RATING_5_STARS, 4f)) }
        until { store.state.value.tracks.first().pendingRating == 4 }
        main { controller.transportControls.seekTo(5000); controller.transportControls.pause() }
        until { !PlaybackService.status.value.playing }
        assertTrue(controller.playbackState!!.position >= 4500)
        main { controller.transportControls.seekTo(0); controller.transportControls.skipToPrevious(); controller.transportControls.play() }
        until { PlaybackService.status.value.playing && PlaybackService.status.value.trackId == "car-b" }
        main { controller.transportControls.stop() }
        until { !PlaybackService.status.value.playing }
    }
    @Test fun legacyVoiceSearchOfflineBrowsingAndRadioRespectPhoneSettings() {
        connect()
        assertEquals(3, children("tracks").size)
        assertEquals(2, children("downloads").size)
        store.update { it.copy(offline = true, twoTrack = true) }
        assertEquals(2, children("tracks").size)
        main { controller.transportControls.playFromSearch("first car song", Bundle.EMPTY) }
        until { PlaybackService.status.value.playing && PlaybackService.status.value.trackId == "car-a" }
        val modes = children("radio")
        assertEquals(4, modes.size)
        main { controller.transportControls.playFromMediaId("radio|RANDOM_ALBUM", Bundle.EMPTY) }
        until { PlaybackService.status.value.radio && PlaybackService.status.value.playing }
        assertEquals(PlayMode.RANDOM_ALBUM, store.state.value.mode)
        assertTrue(store.state.value.twoTrack)
        assertTrue(controller.queue!!.all { it.description.mediaId in listOf("car-a", "car-b") })
    }
    @Test fun media3BrowserSearchAndIdResolutionRejectForeignUris() {
        lateinit var future: com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.MediaBrowser>
        main { future = androidx.media3.session.MediaBrowser.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync() }
        val modern = future.get(15, TimeUnit.SECONDS)
        try {
            lateinit var search: com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.LibraryResult<com.google.common.collect.ImmutableList<MediaItem>>>
            main { search = modern.getSearchResult("second car", 0, 100, null) }
            val results = search.get(15, TimeUnit.SECONDS).value!!
            assertEquals(1, results.size)
            main { modern.setMediaItem(results.single()); modern.prepare(); modern.play() }
            until { PlaybackService.status.value.playing && PlaybackService.status.value.trackId == "car-b" }
            main { modern.setMediaItem(MediaItem.Builder().setMediaId("foreign-track").setUri("https://untrusted.invalid/audio.mp3").build()) }
            until { PlaybackService.status.value.trackId == null }
            main { assertEquals(0, modern.mediaItemCount); modern.release() }
        } finally { main { if (modern.isConnected) modern.release() } }
    }
}
