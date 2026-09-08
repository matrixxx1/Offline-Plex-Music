package com.m3.pocketmusic

import com.sun.net.httpserver.HttpServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.net.URLDecoder

class PlexPlaylistSyncTest {
    private lateinit var server: HttpServer
    private lateinit var api: PlexApi
    private var name = "Driving"
    private var smart = false
    private var nextId = 14
    private val entries = mutableListOf("11" to "1", "12" to "2", "13" to "1")
    private val mutations = mutableListOf<String>()
    private var failDelete = false
    private var failAfterAdd = false
    private fun id(key: String) = "plex:server:$key"
    private val library get() = (1..3).map { Track(id("$it"), "Song $it", remoteKey = "$it") }
    private fun draft() = PlaylistEditing.edit(Playlist("plex:list", "Driving", listOf(id("1"), id("2"), id("1")), true),
        name = "Road & rain", tracks = listOf(id("2"), id("3"), id("1")))
    @Before fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { x ->
            val path = x.requestURI.path
            val query = x.requestURI.rawQuery.orEmpty().split('&').filter { '=' in it }.associate {
                it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8")
            }
            var status = 200
            if (x.requestMethod != "GET") {
                mutations += "${x.requestMethod} $path"
                when {
                    x.requestMethod == "DELETE" -> {
                        if (failDelete) status = 403 else entries.removeAll { it.first == path.substringAfterLast('/') }
                    }
                    path.endsWith("/move") -> {
                        val entry = entries.first { it.first == path.substringBeforeLast('/').substringAfterLast('/') }
                        entries.remove(entry)
                        val index = query["after"]?.let { after -> entries.indexOfFirst { it.first == after } + 1 } ?: 0
                        entries.add(index, entry)
                    }
                    path.endsWith("/items") -> {
                        query.getValue("uri").substringAfterLast('/').split(',').forEach { entries.add("${nextId++}" to it) }
                        if (failAfterAdd) status = 500
                    }
                    else -> { check(query["title.locked"] == "1"); name = query.getValue("title.value") }
                }
            }
            val container = JSONObject()
            when (path) {
                "/" -> container.put("machineIdentifier", "server")
                "/playlists/list" -> container.put("Metadata", JSONArray().put(JSONObject().put("ratingKey", "list").put("title", name).put("playlistType", "audio").put("smart", if (smart) 1 else 0)))
                "/playlists/list/items" -> container.put("Metadata", JSONArray(entries.map { (itemId, key) ->
                    JSONObject().put("type", "track").put("ratingKey", key).put("playlistItemID", itemId.toInt()).put("title", "Song $key")
                }))
            }
            val body = JSONObject().put("MediaContainer", container).toString().toByteArray()
            x.sendResponseHeaders(status, body.size.toLong()); x.responseBody.use { it.write(body) }
        }
        server.start(); api = PlexApi(PlexConfig("http://127.0.0.1:${server.address.port}", "token", "server"))
    }
    @After fun stop() = server.stop(0)
    @Test fun syncAddsRemovesReordersAndRenamesWithoutDeletingMedia() {
        val result = api.syncPlaylist(draft(), library, {}, {})
        assertEquals(draft().tracks, result.tracks); assertEquals(draft().name, result.name)
        assertTrue(mutations.contains("DELETE /playlists/list/items/13"))
        assertTrue(entries.any { it.first == "11" })
        assertTrue(mutations.none { it.contains("/library/metadata") || it == "DELETE /playlists/list/items" })
    }
    @Test fun remoteConflictStopsBeforeAnyWrite() {
        name = "Edited elsewhere"
        assertThrows(IllegalStateException::class.java) { api.syncPlaylist(draft(), library, {}, {}) }
        assertTrue(mutations.isEmpty())
    }
    @Test fun partialFailureCheckpointsAndRetryDoesNotDuplicateAdditions() {
        var saved = draft(); failDelete = true
        assertThrows(IllegalStateException::class.java) { api.syncPlaylist(saved, library, { saved = PlaylistEditing.checkpoint(saved, it, false) }, {}) }
        assertEquals(4, saved.serverTracks.size); assertTrue(saved.pendingSync)
        failDelete = false
        val result = api.syncPlaylist(saved, library, {}, {})
        assertEquals(draft().tracks, result.tracks)
        assertEquals(1, mutations.count { it == "PUT /playlists/list/items" })
    }
    @Test fun ambiguousAddAcknowledgedByReadbackCanBeRetried() {
        var saved = draft(); failAfterAdd = true
        assertThrows(IllegalStateException::class.java) { api.syncPlaylist(saved, library, { saved = PlaylistEditing.checkpoint(saved, it, false) }, {}) }
        assertEquals(4, saved.serverTracks.size)
        failAfterAdd = false
        assertEquals(draft().tracks, api.syncPlaylist(saved, library, {}, {}).tracks)
        assertEquals(1, mutations.count { it == "PUT /playlists/list/items" })
    }
    @Test fun smartPlaylistAndLocalFilesAreRejectedBeforeWrites() {
        smart = true
        assertThrows(IllegalStateException::class.java) { api.syncPlaylist(draft(), library, {}, {}) }
        smart = false
        val localDraft = draft().copy(tracks = listOf("local"))
        assertThrows(IllegalStateException::class.java) { api.syncPlaylist(localDraft, library + Track("local", "Own file"), {}, {}) }
        assertTrue(mutations.isEmpty())
    }
    @Test fun emptyPlaylistSyncOnlyRemovesEntries() {
        val empty = draft().copy(tracks = emptyList())
        assertTrue(api.syncPlaylist(empty, library, {}, {}).tracks.isEmpty())
        assertEquals(3, mutations.count { it.startsWith("DELETE /playlists/list/items/") })
    }
}
