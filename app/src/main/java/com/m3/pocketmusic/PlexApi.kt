package com.m3.pocketmusic

import org.json.JSONObject
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import kotlin.math.roundToInt

class PlexApi(val config: PlexConfig, private val open: (java.net.URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
    private val readTimeoutMs: Int = 60_000) {
    init {
        val uri = URI(config.url)
        require(uri.scheme in listOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.rawQuery == null && uri.fragment == null) { "Enter a server URL such as https://your-server:32400" }
        require(config.token.isNotBlank()) { "Enter your Plex token" }
    }
    private fun connection(path: String, method: String): HttpURLConnection {
        require(path.startsWith("/") && !path.startsWith("//")) { "Invalid Plex media path" }
        return open(URI(config.url.trimEnd('/') + path).toURL()).apply {
            requestMethod = method; connectTimeout = 20_000; readTimeout = readTimeoutMs
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("X-Plex-Token", config.token)
            setRequestProperty("X-Plex-Product", APP_NAME)
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
            progress("Reading ${section.optString("title")} artists, genres and moods")
            // Include optional tags without excluding Media/Part data needed for downloads.
            val tagParams = "includeOptionalElements=Genre,Mood,Style"
            val artists = pages("$root?type=8&$tagParams").associateBy { it.getString("ratingKey") }
            val albums = pages("$root?type=9&$tagParams").associateBy { it.getString("ratingKey") }
            val rows = pages("$root?type=10&$tagParams") { progress("${section.optString("title")}: $it tracks loaded") }
            tracks += rows.filter { it.optString("type") == "track" }.map { row ->
                val track = parseTrack(row)
                val album = albums[track.albumId]; val artist = artists[track.artistId]
                track.copy(genres = cleanTags(track.genres + tags(album, "Genre") + tags(artist, "Genre")),
                    moods = cleanTags(track.moods + tags(album, "Mood") + tags(artist, "Mood")),
                    styles = cleanTags(track.styles + tags(album, "Style") + tags(artist, "Style")))
            }
        }
        return tracks.distinctBy { it.id }
    }
    fun playlists(): List<Playlist> = pages("/playlists?playlistType=audio").map { p ->
        val key = p.getString("ratingKey")
        Playlist("plex:$key", p.getString("title"), pages("/playlists/${encode(key)}/items")
            .filter { it.optString("type") == "track" }.map { "plex:${config.serverId}:${it.getString("ratingKey")}" }, true, smart = p.optInt("smart") == 1 || p.optBoolean("smart"))
    }
    fun playlistsWithTracks(): Pair<List<Playlist>, List<Track>> {
        val tracks = linkedMapOf<String, Track>()
        val lists = pages("/playlists?playlistType=audio").map { p ->
            val key = p.getString("ratingKey")
            val songs = pages("/playlists/${encode(key)}/items").filter { it.optString("type") == "track" }.map { parseTrack(it) }
            songs.forEach { tracks[it.id] = it }
            Playlist("plex:$key", p.getString("title"), songs.map { it.id }, true, smart = p.optInt("smart") == 1 || p.optBoolean("smart"))
        }
        return lists to tracks.values.toList()
    }
    fun metadata(key: String): JSONObject = request("/library/metadata/${encode(key)}").getJSONArray("Metadata").getJSONObject(0)
    data class PlaylistItem(val itemId: String, val trackId: String)
    data class PlaylistSnapshot(val playlist: Playlist, val items: List<PlaylistItem>, val tracks: List<Track>)
    fun playlistSnapshot(id: String): PlaylistSnapshot {
        require(id.startsWith("plex:"))
        val key = id.removePrefix("plex:")
        val meta = request("/playlists/${encode(key)}").getJSONArray("Metadata").getJSONObject(0)
        check(meta.getString("ratingKey") == key && meta.optString("playlistType") == "audio") { "This is not the original audio playlist." }
        val rows = pages("/playlists/${encode(key)}/items")
        check(rows.all { it.optString("type") == "track" }) { "Playlist contains unsupported media." }
        val tracks = rows.map { parseTrack(it) }
        val items = rows.map { PlaylistItem(it.get("playlistItemID").toString(), "plex:${config.serverId}:${it.getString("ratingKey")}") }
        check(items.all { it.itemId.toLongOrNull() != null }) { "Plex returned invalid playlist entry IDs." }
        check(items.map { it.itemId }.distinct().size == items.size) { "Plex returned duplicate playlist entry IDs." }
        return PlaylistSnapshot(Playlist(id, meta.getString("title"), tracks.map { it.id }, true,
            smart = meta.optInt("smart") == 1 || meta.optBoolean("smart")), items, tracks)
    }
    /** Update membership by entry ID; never deletes media files or clears a playlist to rebuild it. */
    fun syncPlaylist(draft: Playlist, library: List<Track>, checkpoint: (Playlist) -> Unit, progress: (String) -> Unit): Playlist {
        require(draft.plex && !draft.smart)
        verifyServer()
        var snapshot = playlistSnapshot(draft.id)
        check(!snapshot.playlist.smart) { "Smart playlists are managed by Plex filters." }
        if (snapshot.playlist.name == draft.name && snapshot.playlist.tracks == draft.tracks) return snapshot.playlist
        check(snapshot.playlist.name == draft.serverName && snapshot.playlist.tracks == draft.serverTracks) {
            "Plex playlist changed since your last refresh. Your edits are saved. Reload from Plex to discard them, or save a local copy first."
        }
        val byId = library.associateBy { it.id }
        val desired = draft.tracks.map { id ->
            val track = byId[id] ?: error("A playlist track is missing from the library. Refresh music before syncing.")
            check(track.remoteKey.isNotBlank() && track.id == "plex:${config.serverId}:${track.remoteKey}") { "Plex playlists can only contain tracks from this Plex server." }
            track
        }
        val root = "/playlists/${encode(draft.id.removePrefix("plex:"))}"
        var name = snapshot.playlist.name
        val items = snapshot.items.toMutableList()
        fun current() = Playlist(draft.id, name, items.map { it.trackId }, true)
        fun mutate(path: String, method: String, expected: Playlist, apply: () -> Unit) {
            try { request(path, method) }
            catch (e: Exception) {
                // A timeout can happen after Plex applies a change. Only advance to an exact readback.
                val actual = runCatching { playlistSnapshot(draft.id).playlist }.getOrNull()
                if (actual?.name == expected.name && actual.tracks == expected.tracks) checkpoint(actual)
                throw e
            }
            apply(); checkpoint(current())
        }
        // Add missing occurrences first, preserving existing playlist entry identities and duplicates.
        val available = items.groupingBy { it.trackId }.eachCount().toMutableMap()
        val missing = desired.filter { track ->
            val count = available[track.id] ?: 0
            if (count > 0) { available[track.id] = count - 1; false } else true
        }
        missing.chunked(100).forEachIndexed { index, batch ->
            progress("Adding playlist songs: batch ${index + 1}/${(missing.size + 99) / 100}")
            val expected = current().copy(tracks = items.map { it.trackId } + batch.map { it.id })
            val uri = "server://${config.serverId}/com.plexapp.plugins.library/library/metadata/${batch.joinToString(",") { it.remoteKey }}"
            try { request("$root/items?uri=${encode(uri)}", "PUT") }
            catch (e: Exception) {
                val actual = runCatching { playlistSnapshot(draft.id).playlist }.getOrNull()
                if (actual?.name == expected.name && actual.tracks == expected.tracks) checkpoint(actual)
                throw e
            }
            snapshot = playlistSnapshot(draft.id)
            check(snapshot.playlist.name == expected.name && snapshot.playlist.tracks == expected.tracks) { "Plex did not confirm added songs. Your edits are still saved." }
            items.clear(); items.addAll(snapshot.items); checkpoint(snapshot.playlist)
        }
        val needed = draft.tracks.groupingBy { it }.eachCount().toMutableMap()
        val remove = items.filter { item ->
            val count = needed[item.trackId] ?: 0
            if (count > 0) { needed[item.trackId] = count - 1; false } else true
        }
        remove.forEachIndexed { index, item ->
            progress("Removing playlist entries ${index + 1}/${remove.size}")
            val expected = current().copy(tracks = items.filterNot { it.itemId == item.itemId }.map { it.trackId })
            mutate("$root/items/${encode(item.itemId)}", "DELETE", expected) { items.remove(item) }
        }
        draft.tracks.forEachIndexed { index, id ->
            if (items[index].trackId != id) {
                progress("Ordering playlist ${index + 1}/${draft.tracks.size}")
                val from = (index until items.size).first { items[it].trackId == id }
                val item = items[from]
                val expectedItems = items.toMutableList().apply { add(index, removeAt(from)) }
                val after = if (index == 0) "" else "?after=${encode(items[index - 1].itemId)}"
                mutate("$root/items/${encode(item.itemId)}/move$after", "PUT", current().copy(tracks = expectedItems.map { it.trackId })) {
                    items.clear(); items.addAll(expectedItems)
                }
            }
        }
        if (name != draft.name) mutate("$root?title.value=${encode(draft.name)}&title.locked=1", "PUT", current().copy(name = draft.name)) { name = draft.name }
        progress("Verifying Plex playlist")
        val verified = playlistSnapshot(draft.id).playlist
        check(verified.name == draft.name && verified.tracks == draft.tracks) { "Plex did not confirm the complete playlist. Your edits remain saved." }
        return verified
    }
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
    fun artwork(path: String): ByteArray {
        val c = connection(path, "GET")
        try {
            check(c.responseCode == 200) { "Artwork unavailable" }
            return c.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val n = input.read(buffer); if (n < 0) break
                    check(output.size() + n <= 5 * 1024 * 1024) { "Artwork too large" }
                    output.write(buffer, 0, n)
                }
                output.toByteArray()
            }
        } finally { c.disconnect() }
    }
    private fun parseTrack(o: JSONObject): Track {
        val media = o.optJSONArray("Media")?.optJSONObject(0)
        val part = media?.optJSONArray("Part")?.optJSONObject(0)
        val key = o.getString("ratingKey")
        return Track(id = "plex:${config.serverId}:$key", title = o.optString("title", "Untitled"),
            artist = o.optString("grandparentTitle", "Unknown artist"), album = o.optString("parentTitle", "Unknown album"),
            albumId = o.optString("parentRatingKey"), artistId = o.optString("grandparentRatingKey"),
            genres = tags(o, "Genre"), moods = tags(o, "Mood"), styles = tags(o, "Style"),
            disc = o.optInt("parentIndex", 1), number = o.optInt("index"), duration = o.optLong("duration"),
            remoteKey = key, part = part?.optString("key").orEmpty(), extension = media?.optString("container", "mp3") ?: "mp3",
            serverRating = (o.optDouble("userRating", 0.0) / 2).roundToInt().coerceIn(0, 5), bytes = part?.optLong("size") ?: 0,
            exactPlexRating = o.optDouble("userRating", 0.0),
            artwork = o.optString("parentThumb").ifBlank { o.optString("thumb") })
    }
    private fun tags(o: JSONObject?, field: String) = cleanTags(o?.optJSONArray(field)?.objects()?.map { it.optString("tag") }.orEmpty())
    private fun cleanTags(values: List<String>) = values.map { it.trim() }.filter { it.isNotBlank() }.distinctBy { it.lowercase(java.util.Locale.ROOT) }
    companion object { fun encode(value: String): String = URLEncoder.encode(value, "UTF-8") }
}
