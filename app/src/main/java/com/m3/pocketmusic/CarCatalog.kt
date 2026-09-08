package com.m3.pocketmusic

import java.util.Base64
import java.util.Locale

data class CarEntry(val id: String, val title: String, val subtitle: String = "", val track: Track? = null, val radio: PlayMode? = null) {
    val playable get() = track != null || radio != null
}
data class CarQueue(val tracks: List<Track>, val start: Int = 0, val mode: PlayMode? = null)

/** A cached, network-free car library. Explicit range folders work with hosts without pagination. */
class CarCatalog(private val state: LibraryState) {
    val tracks = state.tracks.filter { (!state.offline || it.downloaded) && (it.downloaded || it.part.isNotBlank()) }
    private val byId = tracks.associateBy { it.id }
    private val ordered = tracks.sortedWith(compareBy<Track> { it.artist.lowercase(Locale.ROOT) }.thenBy { it.albumGroup }.thenBy { it.disc }.thenBy { it.number }.thenBy { it.title })
    fun children(parent: String): List<CarEntry> {
        val range = parent.split('|')
        if (range.first() == "range" && range.size == 4) {
            val base = decode(range[1]) ?: return emptyList()
            val start = range[2].toIntOrNull() ?: return emptyList()
            val size = range[3].toIntOrNull() ?: return emptyList()
            if (start < 0 || size < 1 || size > 1_000_000 || base.startsWith("range|")) return emptyList()
            return bounded(base, rawChildren(base).drop(start).take(size), start)
        }
        return bounded(parent, rawChildren(parent))
    }
    private fun bounded(parent: String, items: List<CarEntry>, offset: Int = 0): List<CarEntry> {
        if (items.size <= 100) return items
        var size = 100
        while ((items.size.toLong() + size - 1) / size > 100) size *= 100
        return items.chunked(size).mapIndexed { i, chunk ->
            val start = offset + i * size
            CarEntry("range|${encode(parent)}|$start|$size", "${start + 1}–${start + chunk.size}", "${chunk.first().title} … ${chunk.last().title}")
        }
    }
    private fun rawChildren(parent: String): List<CarEntry> = when (parent) {
        ROOT -> listOf(CarEntry("library", "Library"), CarEntry("playlists", "Playlists"), CarEntry("downloads", "Downloads"), CarEntry("radio", "Radio"))
        "library" -> listOf(CarEntry("artists", "Artists"), CarEntry("albums", "Albums"), CarEntry("genres", "Genres"), CarEntry("tracks", "All tracks"))
        "artists" -> ordered.groupBy { it.artistGroup }.map { (key, list) -> CarEntry("artist|${encode(key)}", list.first().artist, "${list.size} tracks") }
        "albums" -> ordered.groupBy { it.albumGroup }.map { (key, list) -> CarEntry("album|${encode(key)}", list.first().album, list.first().artist) }
        "genres" -> ordered.flatMap { t -> t.genres.ifEmpty { listOf("Unspecified") }.map { it.lowercase(Locale.ROOT) to it } }.distinctBy { it.first }.sortedBy { it.first }
            .map { CarEntry("genre|${encode(it.first)}", it.second) }
        "playlists" -> state.playlists.map { CarEntry("playlist|${encode(it.id)}", it.name, if (it.plex) "Plex playlist" else "On this device") }
        "radio" -> PlayMode.entries.map { CarEntry("radio|${it.name}", it.label, if (state.twoTrack) "2 track limit on" else "Play complete groups", radio = it) }
        else -> scopedTracks(parent).map { t -> CarEntry(playId(parent, t.id), t.title, "${t.artist} • ${t.album}", track = t) }
    }
    private fun scopedTracks(parent: String): List<Track> {
        if (parent == "tracks") return ordered
        if (parent == "downloads") return ordered.filter { it.downloaded }
        val kind = parent.substringBefore('|'); val key = decode(parent.substringAfter('|', "")) ?: return emptyList()
        return when (kind) {
            "artist" -> ordered.filter { it.artistGroup == key }
            "album" -> ordered.filter { it.albumGroup == key }
            "genre" -> ordered.filter { t -> t.genres.ifEmpty { listOf("Unspecified") }.any { it.lowercase(Locale.ROOT) == key } }
            "playlist" -> state.playlists.find { it.id == key }?.tracks?.mapNotNull { id -> byId[id] }.orEmpty()
            "search" -> search(key)
            else -> emptyList()
        }
    }
    fun search(query: String): List<Track> {
        val words = query.trim().lowercase(Locale.ROOT).split(Regex("\\s+")).filter { it.isNotBlank() }
        return ordered.filter { t -> words.all { word -> "${t.title} ${t.artist} ${t.album} ${t.genres.joinToString(" ")}".lowercase(Locale.ROOT).contains(word) } }
    }
    fun searchEntries(query: String) = search(query).take(100).map { CarEntry(playId("search|${encode(query)}", it.id), it.title, it.artist, track = it) }
    fun queue(id: String): CarQueue {
        if (id.startsWith("radio|")) return PlayMode.entries.find { it.name == id.substringAfter('|') }?.let { CarQueue(tracks, mode = it) } ?: CarQueue(emptyList())
        val parts = id.split('|')
        if (parts.size == 3 && parts[0] == "play") {
            val parent = decode(parts[1]) ?: return CarQueue(emptyList())
            val trackId = decode(parts[2]) ?: return CarQueue(emptyList())
            val list = scopedTracks(parent)
            val index = list.indexOfFirst { it.id == trackId }
            // Keep full playlist scope here; the service publishes bounded playback chunks.
            if (index >= 0) {
                return CarQueue(list, index)
            }
            return CarQueue(emptyList())
        }
        return CarQueue(tracks.filter { it.id == id })
    }
    fun item(id: String): CarEntry? {
        if (id == ROOT) return CarEntry(ROOT, "Offline Plex music")
        if (id.startsWith("play|") || tracks.any { it.id == id }) return queue(id).let { q -> q.tracks.getOrNull(q.start)?.let { CarEntry(id, it.title, it.artist, track = it) } }
        if (id.startsWith("radio|")) return PlayMode.entries.find { it.name == id.substringAfter('|') }?.let { CarEntry(id, it.label, radio = it) }
        if (id.startsWith("range|")) return children(id).takeIf { it.isNotEmpty() }?.let { CarEntry(id, "More music") }
        return (rawChildren(ROOT) + rawChildren("library") + rawChildren("artists") + rawChildren("albums") + rawChildren("genres") + rawChildren("playlists")).find { it.id == id }
    }
    companion object {
        const val ROOT = "car-root"
        private fun encode(value: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))
        private fun decode(value: String) = runCatching { String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8) }.getOrNull()
        private fun playId(parent: String, id: String) = "play|${encode(parent)}|${encode(id)}"
    }
}
