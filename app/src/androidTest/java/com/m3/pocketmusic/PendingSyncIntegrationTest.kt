package com.m3.pocketmusic

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class PendingSyncIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun waitDone(vm: MusicViewModel) {
        val end = System.currentTimeMillis() + 15_000
        while (vm.busy.value && System.currentTimeMillis() < end) Thread.sleep(25)
        assertFalse("Sync did not finish", vm.busy.value)
    }
    @Test fun deletionWaitsForExplicitSyncRetainsFailuresAndKeepsLocalCopy() {
        val requests = Collections.synchronizedList(mutableListOf<String>())
        val rating = AtomicInteger(0)
        val failDelete = AtomicBoolean(true)
        val socket = ServerSocket(0)
        val server = thread(isDaemon = true) {
            while (!socket.isClosed) runCatching {
                socket.accept().use { client ->
                    val reader = client.getInputStream().bufferedReader()
                    val request = reader.readLine().orEmpty(); requests.add(request)
                    while (!reader.readLine().isNullOrBlank()) { }
                    val method = request.substringBefore(' ')
                    val path = request.substringAfter(' ').substringBefore(' ')
                    val body = when {
                        path == "/" -> """{"MediaContainer":{"machineIdentifier":"sync-fixture"}}"""
                        path.startsWith("/:/rate") -> { rating.set(path.substringAfter("rating=").toInt()); "{}" }
                        method == "DELETE" -> "{}"
                        else -> """{"MediaContainer":{"Metadata":[{"type":"track","ratingKey":"1","title":"Song","userRating":${rating.get()}}]}}"""
                    }.toByteArray()
                    val status = if (method == "DELETE" && failDelete.get()) "403 Forbidden" else "200 OK"
                    client.getOutputStream().apply {
                        write("HTTP/1.1 $status\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray()); write(body); flush()
                    }
                }
            }
        }
        try {
            val store = context.musicStore
            store.credentials.save(PlexConfig("http://127.0.0.1:${socket.localPort}", "fixture-token", "sync-fixture"))
            store.update { LibraryState(offline = true, tracks = listOf(Track("fixture", "Song", remoteKey = "1", localUri = "content://kept-copy"))) }
            val vm = MusicViewModel(context.applicationContext as MusicApp)
            main { vm.delete(setOf("fixture"), local = false, server = true) }; waitDone(vm)
            assertTrue(store.state.value.tracks.single().pendingDeletion)
            assertTrue(requests.isEmpty())
            main { vm.rate(setOf("fixture"), 4); vm.settings(offline = false); vm.syncRatings() }; waitDone(vm)
            assertNull(store.state.value.tracks.single().pendingRating)
            assertEquals(8, rating.get())
            assertTrue(requests.none { it.startsWith("DELETE") })
            main { vm.syncRatings(setOf("fixture")) }; waitDone(vm)
            assertTrue(store.state.value.tracks.single().pendingDeletion)
            assertEquals("1", store.state.value.tracks.single().remoteKey)
            failDelete.set(false)
            main { vm.syncRatings(setOf("fixture")) }; waitDone(vm)
            val kept = store.state.value.tracks.single()
            assertFalse(kept.pendingDeletion)
            assertEquals("", kept.remoteKey)
            assertEquals("content://kept-copy", kept.localUri)
            assertEquals(4, kept.localRating)
            assertEquals(2, requests.count { it.startsWith("DELETE") })
        } finally { socket.close(); server.join(1000) }
    }
    @Test fun albumArtIsFetchedOnceAndReusedOffline() {
        val png = java.io.ByteArrayOutputStream().also { Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val socket = ServerSocket(0)
        val requests = AtomicInteger(0)
        val server = thread(isDaemon = true) {
            while (!socket.isClosed) runCatching {
                socket.accept().use { client ->
                    val reader = client.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrBlank()) { }
                    requests.incrementAndGet()
                    client.getOutputStream().apply { write("HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nContent-Length: ${png.size}\r\nConnection: close\r\n\r\n".toByteArray()); write(png); flush() }
                }
            }
        }
        try {
            val store = context.musicStore
            store.credentials.save(PlexConfig("http://127.0.0.1:${socket.localPort}", "fixture", "art-${socket.localPort}"))
            val track = Track("art", "Song", artwork = "/cover-${System.nanoTime()}")
            store.update { LibraryState(tracks = listOf(track)) }
            assertNotNull(runBlocking { ArtworkCache.load(context, track) })
            store.update { it.copy(offline = true) }
            assertNotNull(runBlocking { ArtworkCache.load(context, track.copy(id = "another-song-on-album")) })
            assertEquals(1, requests.get())
        } finally { socket.close(); server.join(1000) }
    }
}
