package com.m3.pocketmusic

import kotlin.random.Random

data class PlaylistDownloadPlan(val tracks: List<Track>, val bytes: Long, val unknownSizes: Int)

object PlaylistDownloads {
    const val BYTES_PER_MB = 1_000_000L
    fun byteLimit(text: String): Long? = text.toLongOrNull()?.takeIf { it in 1..(Long.MAX_VALUE / BYTES_PER_MB) }?.times(BYTES_PER_MB)

    /** Budget counts only new files. Unknown sizes cannot safely fit a strict byte cap. */
    fun plan(candidates: List<Track>, maxBytes: Long? = null, perArtist: Int? = null, random: Random = Random.Default): PlaylistDownloadPlan {
        require(maxBytes == null || maxBytes > 0)
        require(perArtist == null || perArtist > 0)
        val unique = candidates.distinctBy { it.id }
        val unknown = unique.count { it.bytes <= 0 }
        if (maxBytes == null) return PlaylistDownloadPlan(unique, unique.sumOf { it.bytes.coerceAtLeast(0) }, unknown)
        var total = 0L
        val counts = mutableMapOf<String, Int>()
        val selected = unique.shuffled(random).filter { track ->
            val artist = track.artistId.ifBlank { track.artist.trim().lowercase(java.util.Locale.ROOT) }
            if (track.bytes <= 0 || track.bytes > maxBytes - total || (perArtist != null && counts.getOrDefault(artist, 0) >= perArtist)) false
            else { total += track.bytes; counts[artist] = counts.getOrDefault(artist, 0) + 1; true }
        }
        return PlaylistDownloadPlan(selected, total, unknown)
    }
    fun pending(state: LibraryState, playlistIds: Set<String>): List<Track> {
        val tracks = state.tracks.associateBy { it.id }
        val queued = state.downloads.map { it.id }.toSet()
        return state.playlists.filter { it.plex && it.id in playlistIds }.flatMap { it.tracks }.distinct()
            .mapNotNull { tracks[it] }.filter { !it.downloaded && it.id !in queued && it.remoteKey.isNotBlank() && it.part.isNotBlank() }
    }
}
