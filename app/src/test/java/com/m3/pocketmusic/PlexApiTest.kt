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
                path == "/library/sections" -> """{"MediaContainer":{"Directory":[{"type":"artist","key":"1","title":"Music"},{"type":"movie","key":"2","title":"Movies"}]}}"""
                path == "/library/sections/1/all" -> when {
                    x.requestURI.query.contains("type=8") -> """{"MediaContainer":{"Metadata":[{"ratingKey":"artist","Genre":[{"tag":"Rock"}]}]}}"""
                    x.requestURI.query.contains("type=9") -> """{"MediaContainer":{"Metadata":[{"ratingKey":"album","Genre":[{"tag":"Indie"}]}]}}"""
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
    private fun track(id: String) = """{"type":"$trackType","ratingKey":"$id","title":"Song","grandparentTitle":"Artist","parentTitle":"Album","grandparentRatingKey":"artist","parentRatingKey":"album","userRating":$rating,"Media":[{"container":"flac","Part":[{"key":"/library/parts/$id/file.flac","size":100}]}]}"""
    @After fun stop() { server.stop(0) }
    @Test fun paginatedMusicOnlyImportIncludesInheritedGenres() {
        val tracks = api.library { }
        assertEquals(2, tracks.size); assertEquals(listOf("Indie", "Rock"), tracks.first().genres)
        assertTrue(requests.none { it.second.startsWith("/library/sections/2") })
        assertEquals("test-token", authHeader)
        assertTrue(requests.none { "test-token" in it.second })
    }
    @Test fun ratingUsesPutAndPlexTenPointScaleThenReadsBack() {
        api.rate(Track("id", "Song", remoteKey = "1"), 1)
        assertEquals(2, rating)
        assertEquals("PUT", requests.first().first)
        assertTrue(requests.last().second.startsWith("/library/metadata/1"))
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
