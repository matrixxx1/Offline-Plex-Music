package com.m3.pocketmusic

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress

/** In-process contract fixture: never connects to a user's Plex server. */
class PlexApiTest {
    private lateinit var server: HttpServer
    private lateinit var api: PlexApi
    private val requests = mutableListOf<Pair<String, String>>()
    private var rating = 8
    private var trackType = "track"
    private var authHeader = ""
    @Before fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { x ->
            requests += x.requestMethod to x.requestURI.toString()
            authHeader = x.requestHeaders.getFirst("X-Plex-Token")
            val path = x.requestURI.path
            val response = when {
                path == "/" -> """{"MediaContainer":{"machineIdentifier":"test-server"}}"""
                path == "/playlists" -> """{"MediaContainer":{"Metadata":[{"ratingKey":"a","title":"Driving"},{"ratingKey":"b","title":"Favorites"}]}}"""
                path == "/playlists/a/items" -> """{"MediaContainer":{"Metadata":[${track("1")},${track("2")}]}}"""
                path == "/playlists/b/items" -> """{"MediaContainer":{"Metadata":[${track("2")}]}}"""
                path == "/library/sections" -> """{"MediaContainer":{"Directory":[{"type":"artist","key":"1","title":"Music"},{"type":"movie","key":"2","title":"Movies"}]}}"""
                path == "/library/sections/1/all" -> when {
                    x.requestURI.query.contains("type=8") -> """{"MediaContainer":{"Metadata":[{"ratingKey":"artist","Genre":[{"tag":"Rock"}],"Mood":[{"tag":"Energetic"}],"Style":[{"tag":"Alternative"}]}]}}"""
                    x.requestURI.query.contains("type=9") -> """{"MediaContainer":{"Metadata":[{"ratingKey":"album","Genre":[{"tag":"Indie"}],"Mood":[{"tag":" happy "},{"tag":""}],"Style":[{"tag":"Indie Rock"}]}]}}"""
                    x.requestURI.query.contains("Container-Start=0") -> """{"MediaContainer":{"totalSize":2,"Metadata":[${track("1")}]}}"""
                    else -> """{"MediaContainer":{"totalSize":2,"Metadata":[${track("2")}]}}"""
                }
                path == "/:/rate" -> { rating = x.requestURI.query.substringAfter("rating=").substringBefore('&').toInt(); "<html>OK</html>" }
                path.startsWith("/library/metadata/") -> if (x.requestMethod == "DELETE") "<html>OK</html>" else """{"MediaContainer":{"Metadata":[${track("1")}]}}"""
                else -> "{}"
            }
            x.responseHeaders.add("Content-Type", "application/json")
            x.sendResponseHeaders(200, response.toByteArray().size.toLong())
            x.responseBody.use { it.write(response.toByteArray()) }
        }
        server.start()
        api = PlexApi(PlexConfig("http://127.0.0.1:${server.address.port}", "test-token", "test-server"))
    }
    private fun track(id: String) = """{"type":"$trackType","ratingKey":"$id","title":"Song","grandparentTitle":"Artist","parentTitle":"Album","grandparentRatingKey":"artist","parentRatingKey":"album","userRating":$rating,"Mood":[{"tag":"Happy"},{"tag":"Playful"}],"Media":[{"container":"flac","Part":[{"key":"/library/parts/$id/file.flac","size":100}]}]}"""
    @After fun stop() { server.stop(0) }
    @Test fun paginatedMusicOnlyImportIncludesInheritedGenres() {
        val tracks = api.library { }
        assertEquals(2, tracks.size); assertEquals(listOf("Indie", "Rock"), tracks.first().genres)
        assertTrue(requests.none { it.second.startsWith("/library/sections/2") })
        assertEquals("test-token", authHeader)
        assertTrue(requests.none { "test-token" in it.second })
    }
    @Test fun playlistRefreshLoadsAudioMetadataWithoutScanningWholeLibraryOrWriting() {
        val (lists, tracks) = api.playlistsWithTracks()
        assertEquals(listOf("Driving", "Favorites"), lists.map { it.name })
        assertEquals(listOf("plex:test-server:1", "plex:test-server:2"), tracks.map { it.id })
        assertEquals(listOf("plex:test-server:2"), lists.last().tracks)
        assertTrue(tracks.all { it.part.isNotBlank() && it.remoteKey.isNotBlank() })
        assertTrue(requests.all { it.first == "GET" && it.second.startsWith("/playlists") })
    }
    @Test fun ratingUsesPutAndPlexTenPointScaleThenReadsBack() {
        api.rate(Track("id", "Song", remoteKey = "1"), 1)
        assertEquals(2, rating)
        assertEquals("PUT", requests.first().first)
        assertTrue(requests.last().second.startsWith("/library/metadata/1"))
    }
    @Test fun importsTrackAlbumAndArtistTagsWithoutDroppingAudioOrWritingMetadata() {
        val tracks = api.library { }
        tracks.forEach { t ->
            assertEquals(listOf("Happy", "Playful", "Energetic"), t.moods)
            assertEquals(listOf("Indie Rock", "Alternative"), t.styles)
            assertEquals("flac", t.extension); assertTrue(t.part.startsWith("/library/parts/"))
        }
        assertTrue(requests.all { it.first == "GET" })
        assertTrue(requests.filter { it.second.startsWith("/library/sections/1/all") }.all { it.second.contains("includeOptionalElements=Genre,Mood,Style") })
    }
    @Test fun deletionRefusesNonMusicMetadata() {
        trackType = "movie"
        assertThrows(IllegalStateException::class.java) { api.deleteTrack(Track("id", "Song", remoteKey = "1")) }
        assertTrue(requests.none { it.first == "DELETE" })
    }
    @Test fun changedServerIdentityStopsOperations() {
        val wrong = PlexApi(api.config.copy(serverId = "other"))
        assertThrows(IllegalStateException::class.java) { wrong.verifyServer() }
    }
    @Test fun deletionOnlyTargetsReviewedTrack() {
        api.deleteTrack(Track("id", "Song", remoteKey = "1"))
        assertEquals("DELETE" to "/library/metadata/1", requests.last())
    }
}
