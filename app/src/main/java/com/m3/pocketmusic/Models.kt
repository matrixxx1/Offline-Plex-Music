package com.m3.pocketmusic

import kotlin.random.Random

data class Track(
    val id: String, val title: String, val artist: String = "Unknown artist",
    val album: String = "Unknown album", val albumId: String = "", val artistId: String = "",
    val genres: List<String> = emptyList(), val disc: Int = 1, val number: Int = 0,
    val duration: Long = 0, val remoteKey: String = "", val part: String = "",
    val extension: String = "mp3", val localUri: String = "", val serverRating: Int = 0,
    val localRating: Int = 0, val pendingRating: Int? = null, val bytes: Long = 0,
    val exactPlexRating: Double? = null
) {
    val rating get() = pendingRating ?: if (remoteKey.isNotBlank()) serverRating else localRating
    val downloaded get() = localUri.isNotBlank()
    val ratingText get() = if (pendingRating == null && remoteKey.isNotBlank() && exactPlexRating != null)
        (exactPlexRating / 2).let { if (it % 1 == 0.0) it.toInt().toString() else it.toString() } else rating.toString()
    val albumGroup get() = albumId.ifBlank { "${artist.lowercase()}::${album.lowercase()}" }
    val artistGroup get() = artistId.ifBlank { artist.lowercase() }
}
data class Playlist(val id: String, val name: String, val tracks: List<String>, val plex: Boolean = false)
data class DownloadJob(val id: String, val state: String = "Queued", val error: String = "")
data class LibraryState(
    val tracks: List<Track> = emptyList(), val playlists: List<Playlist> = emptyList(),
    val downloads: List<DownloadJob> = emptyList(), val folder: String = "",
    val offline: Boolean = false, val mode: PlayMode = PlayMode.RANDOM_TRACK, val twoTrack: Boolean = false
)
enum class PlayMode(val label: String) {
    RANDOM_TRACK("Random Track"), RANDOM_ALBUM("Random Album"),
    RANDOM_ARTIST("Random Artist"), RANDOM_GENRE("Random Genre")
}

/** Generates one task at a time. A group cannot immediately repeat when alternatives exist. */
class RadioPlanner(private val random: Random = Random.Default) {
    var previousGroup: String? = null
        private set
    private var previousMode: PlayMode? = null
    fun reset() { previousGroup = null; previousMode = null }
    fun next(pool: List<Track>, mode: PlayMode, twoTrack: Boolean): List<Track> {
        if (pool.isEmpty()) return emptyList()
        if (mode != previousMode) { previousGroup = null; previousMode = mode }
        val groups = when (mode) {
            PlayMode.RANDOM_TRACK -> pool.associate { it.id to listOf(it) }
            PlayMode.RANDOM_ALBUM -> pool.groupBy { it.albumGroup }
            PlayMode.RANDOM_ARTIST -> pool.groupBy { it.artistGroup }
            PlayMode.RANDOM_GENRE -> pool.flatMap { t -> t.genres.ifEmpty { listOf("Unspecified") }.map { it.lowercase() to t } }
                .groupBy({ it.first }, { it.second })
        }
        val candidates = groups.keys.filter { it != previousGroup }.ifEmpty { groups.keys.toList() }
        val key = candidates.random(random)
        previousGroup = key
        val tracks = groups.getValue(key).distinctBy { it.id }
            .sortedWith(compareBy<Track> { it.albumGroup }.thenBy { it.disc }.thenBy { it.number }.thenBy { it.title })
        return if (twoTrack) tracks.take(2) else tracks
    }
}

object RatingRules {
    fun rate(track: Track, stars: Int): Track {
        require(stars in 0..5)
        return if (track.remoteKey.isBlank()) track.copy(localRating = stars)
        else track.copy(pendingRating = stars)
    }
    fun acknowledged(current: Track, sent: Int): Track = current.copy(
        serverRating = sent, exactPlexRating = sent * 2.0, pendingRating = current.pendingRating.takeUnless { it == sent })
    fun oneStar(tracks: List<Track>) = tracks.filter {
        if (it.pendingRating != null) it.pendingRating == 1
        else if (it.remoteKey.isNotBlank() && it.exactPlexRating != null) it.exactPlexRating == 2.0
        else it.rating == 1
    }
}
