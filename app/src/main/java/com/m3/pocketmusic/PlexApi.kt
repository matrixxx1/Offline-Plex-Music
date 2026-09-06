package com.m3.pocketmusic

import org.json.JSONObject
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import kotlin.math.roundToInt

class PlexApi(val config: PlexConfig) {
    init {
        val uri = URI(config.url)
        require(uri.scheme in listOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.rawQuery == null && uri.fragment == null) { "Enter a server URL such as https://your-server:32400" }
        require(config.token.isNotBlank()) { "Enter your Plex token" }
    }
    private fun connection(path: String, method: String): HttpURLConnection {
        require(path.startsWith("/") && !path.startsWith("//")) { "Invalid Plex media path" }
        return (URI(config.url.trimEnd('/') + path).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = method; connectTimeout = 20_000; readTimeout = 60_000
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("X-Plex-Token", config.token)
            setRequestProperty("X-Plex-Product", "Pocket Music")
            setRequestProperty("X-Plex-Client-Identifier", "pocket-music-android")
        }
    }
    fun request(path: String, method: String = "GET"): JSONObject {
        val c = connection(path, method)
        try {
            val status = c.responseCode
            check(status in 200..299) { "Plex HTTP $status. Check connection, account permissions, and server settings." }
            // Plex mutations may return an empty or HTML success body even with Accept: JSON.
            if (method != "GET") { c.inputStream.close(); return JSONObject() }
            val body = c.inputStream.bufferedReader().use { it.readText() }
            return if (body.isBlank()) JSONObject() else JSONObject(body).optJSONObject("MediaContainer") ?: JSONObject(body)
        } finally { c.disconnect() }
    }
    fun identity(): String = request("/").getString("machineIdentifier")
    fun verifyServer() { check(identity() == config.serverId) { "Server identity changed. Operation stopped." } }
    private fun pages(path: String, field: String = "Metadata", progress: (Int) -> Unit = {}): List<JSONObject> {
        val result = mutableListOf<JSONObject>()
        var start = 0
        while (true) {
            val page = request(path + (if ('?' in path) "&" else "?") + "X-Plex-Container-Start=$start&X-Plex-Container-Size=500")
            val rows = page.optJSONArray(field)?.objects().orEmpty()
            result.addAll(rows); progress(result.size)
            start += rows.size
            if (rows.isEmpty() || start >= page.optInt("totalSize", start)) break
        }
        return result
    }
    fun library(progress: (String) -> Unit): List<Track> {
        verifyServer()
        val sections = request("/library/sections").optJSONArray("Directory")?.objects().orEmpty().filter { it.optString("type") == "artist" }
        check(sections.isNotEmpty()) { "No music libraries are available to this account." }
        val tracks = mutableListOf<Track>()
        for (section in sections) {
            val root = "/library/sections/${encode(section.getString("key"))}/all"
            progress("Reading ${section.optString("title")} artists and genres")
            val artists = pages("$root?type=8").associateBy { it.getString("ratingKey") }
            val albums = pages("$root?type=9").associateBy { it.getString("ratingKey") }
            val rows = pages("$root?type=10") { progress("${section.optString("title")}: $it tracks loaded") }
            tracks += rows.filter { it.optString("type") == "track" }.map { row ->
                val track = parseTrack(row)
                val inherited = genres(albums[track.albumId]) + genres(artists[track.artistId])
                track.copy(genres = (track.genres + inherited).distinctBy { it.lowercase() })
            }
        }
        return tracks.distinctBy { it.id }
    }
    fun playlists(): List<Playlist> = pages("/playlists?playlistType=audio").map { p ->
        val key = p.getString("ratingKey")
        Playlist("plex:$key", p.getString("title"), pages("/playlists/${encode(key)}/items")
            .filter { it.optString("type") == "track" }.map { "plex:${config.serverId}:${it.getString("ratingKey")}" }, true)
    }
    fun metadata(key: String): JSONObject = request("/library/metadata/${encode(key)}").getJSONArray("Metadata").getJSONObject(0)
    fun rate(track: Track, stars: Int) {
        require(track.remoteKey.isNotBlank() && stars in 0..5)
        request("/:/rate?key=${encode(track.remoteKey)}&identifier=com.plexapp.plugins.library&rating=${stars * 2}", "PUT")
        val actual = metadata(track.remoteKey).optDouble("userRating", 0.0)
        check(kotlin.math.abs(actual - stars * 2.0) < 0.01) { "Plex did not confirm the rating; it remains queued." }
    }
    fun deleteTrack(track: Track) {
        require(track.remoteKey.isNotBlank())
        val fresh = metadata(track.remoteKey)
        check(fresh.optString("type") == "track" && fresh.getString("ratingKey") == track.remoteKey && fresh.getString("title") == track.title) { "Track identity changed; deletion stopped." }
        request("/library/metadata/${encode(track.remoteKey)}", "DELETE")
    }
    fun download(track: Track, consume: (InputStream, Long) -> Unit) {
        val c = connection(track.part, "GET")
        try {
            check(c.responseCode == 200) { "Download failed: Plex HTTP ${c.responseCode}" }
            val type = c.contentType.orEmpty()
            check(!type.contains("json") && !type.contains("text/") && !type.contains("xml")) { "Server returned a document instead of audio" }
            c.inputStream.use { consume(it, c.contentLengthLong) }
        } finally { c.disconnect() }
    }
    private fun parseTrack(o: JSONObject): Track {
        val media = o.optJSONArray("Media")?.optJSONObject(0)
        val part = media?.optJSONArray("Part")?.optJSONObject(0)
        val key = o.getString("ratingKey")
        return Track(id = "plex:${config.serverId}:$key", title = o.optString("title", "Untitled"),
            artist = o.optString("grandparentTitle", "Unknown artist"), album = o.optString("parentTitle", "Unknown album"),
            albumId = o.optString("parentRatingKey"), artistId = o.optString("grandparentRatingKey"),
            genres = genres(o), disc = o.optInt("parentIndex", 1), number = o.optInt("index"), duration = o.optLong("duration"),
            remoteKey = key, part = part?.optString("key").orEmpty(), extension = media?.optString("container", "mp3") ?: "mp3",
            serverRating = (o.optDouble("userRating", 0.0) / 2).roundToInt().coerceIn(0, 5), bytes = part?.optLong("size") ?: 0,
            exactPlexRating = o.optDouble("userRating", 0.0))
    }
    private fun genres(o: JSONObject?) = o?.optJSONArray("Genre")?.objects()?.map { it.optString("tag") }?.filter { it.isNotBlank() }.orEmpty()
    companion object { fun encode(value: String): String = URLEncoder.encode(value, "UTF-8") }
}
