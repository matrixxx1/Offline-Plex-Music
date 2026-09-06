package com.m3.pocketmusic

import java.util.Locale

enum class DownloadFilter(val label: String) {
    ALL("All downloads"), ARTIST("Artist"), ALBUM("Album"), GENRE("Genre"), BELOW_RATING("Rating below")
}
data class DownloadOption(val key: String, val label: String)

/** Uses exact, effective ratings; a queued rating takes precedence over Plex's value. */
object DownloadFilters {
    fun stars(t: Track): Double = t.pendingRating?.toDouble()
        ?: if (t.remoteKey.isNotBlank()) t.exactPlexRating?.div(2) ?: t.serverRating.toDouble() else t.localRating.toDouble()
    private fun normalized(value: String) = value.trim().lowercase(Locale.ROOT)
    private fun keys(t: Track, filter: DownloadFilter): List<String> = when (filter) {
        DownloadFilter.ARTIST -> listOf(normalized(t.artist))
        DownloadFilter.ALBUM -> listOf(t.albumGroup)
        DownloadFilter.GENRE -> t.genres.map(::normalized).filter { it.isNotBlank() }.ifEmpty { listOf("unspecified") }.distinct()
        else -> emptyList()
    }
    fun options(tracks: List<Track>, filter: DownloadFilter): List<DownloadOption> = tracks.filter { it.downloaded }.flatMap { t ->
        keys(t, filter).map { key -> DownloadOption(key, when (filter) {
            DownloadFilter.ARTIST -> t.artist.trim().ifBlank { "Unknown artist" }
            DownloadFilter.ALBUM -> "${t.artist} • ${t.album}"
            else -> t.genres.firstOrNull { normalized(it) == key }?.trim() ?: "Unspecified"
        }) }
    }.distinctBy { it.key }.sortedBy { normalized(it.label) }

    fun matching(tracks: List<Track>, filter: DownloadFilter, key: String? = null, below: Double = 3.0,
        includeUnrated: Boolean = false, includeOwnFiles: Boolean = true): List<Track> {
        require(below.isFinite() && below in 0.0..5.0)
        return tracks.filter { t ->
            t.downloaded && (includeOwnFiles || t.remoteKey.isNotBlank()) && when (filter) {
                DownloadFilter.ALL -> true
                DownloadFilter.BELOW_RATING -> stars(t).let { if (it == 0.0) includeUnrated else it < below }
                else -> key != null && key in keys(t, filter)
            }
        }
    }
}
