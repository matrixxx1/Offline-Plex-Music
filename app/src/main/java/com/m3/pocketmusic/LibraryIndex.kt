package com.m3.pocketmusic

import java.util.Locale

/** One pass over the library, then one lookup per playlist entry (including repeats). */
class LibraryIndex(val tracks: List<Track>) {
    val byId = tracks.associateBy { it.id }
    fun resolve(playlist: Playlist?): List<Track> = playlist?.tracks?.mapNotNull { byId[it] } ?: tracks
}

data class LibraryRequest(val index: LibraryIndex?, val playlist: Playlist?, val offline: Boolean,
    val search: String, val group: String, val groupValue: String?) {
    fun load(): LibraryView {
        val base = index?.resolve(playlist).orEmpty().filter { (!offline || it.downloaded) &&
            (search.isBlank() || "${it.title} ${it.artist} ${it.album} ${it.genres.joinToString()}".contains(search, true)) }
        val visible = if (groupValue == null) base else base.filter { groupKeys(it, group).any { it.equals(groupValue, true) } }
        val groups = if (group == "Tracks" || groupValue != null) emptyMap() else base.flatMap { t ->
            groupKeys(t, group).map { it.trim().lowercase(Locale.ROOT) to t }
        }.groupBy({ it.first }, { it.second }).toSortedMap()
        return LibraryView(visible, groups, RatingRules.oneStar(visible))
    }
}
data class LibraryView(val visible: List<Track>, val groups: Map<String, List<Track>>, val oneStar: List<Track>)
fun groupKeys(t: Track, group: String): List<String> = when (group) {
    "Artists" -> listOf(t.artist)
    "Albums" -> listOf("${t.artist} • ${t.album}")
    "Genres" -> t.genres.ifEmpty { listOf("Unspecified") }
    else -> emptyList()
}
