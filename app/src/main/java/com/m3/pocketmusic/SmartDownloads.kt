package com.m3.pocketmusic

import java.util.Locale
import kotlin.random.Random

enum class DownloadCategory(val label: String, val singular: String) {
    ALL("All music", "library"), GENRE("Genres", "genre"), ARTIST("Artists", "artist"),
    ALBUM("Albums", "album"), MOOD("Moods", "mood"), STYLE("Styles", "style")
}
enum class DownloadAmount { ALL, TOTAL, PER_GROUP }
data class SmartDownloadRequest(val category: DownloadCategory = DownloadCategory.ALL,
    // null means all groups; an empty set means no groups, never all.
    val groups: Set<String>? = null, val amount: DownloadAmount = DownloadAmount.ALL, val count: Int = 10)
data class SmartDownloadGroup(val key: String, val label: String, val tracks: List<Track>)
data class SmartDownloadPlan(val tracks: List<Track>, val groups: Int) {
    val bytes get() = tracks.sumOf { it.bytes.coerceAtLeast(0) }
    val unknownSizes get() = tracks.count { it.bytes <= 0 }
}

/** Plans new downloads only. A preview is a fixed list, not a rule re-evaluated by the worker. */
object SmartDownloads {
    private fun norm(value: String) = value.trim().lowercase(Locale.ROOT)
    fun available(state: LibraryState): List<Track> {
        val queued = state.downloads.map { it.id }.toHashSet()
        return state.tracks.filter { it.remoteKey.isNotBlank() && it.part.startsWith("/") &&
            !it.part.startsWith("//") && !it.downloaded && it.id !in queued }.distinctBy { it.id }
    }
    fun groups(tracks: List<Track>, category: DownloadCategory): List<SmartDownloadGroup> {
        val labels = mutableMapOf<String, String>()
        val grouped = linkedMapOf<String, MutableList<Track>>()
        tracks.distinctBy { it.id }.forEach { t ->
            val pairs = when (category) {
                DownloadCategory.ALL -> listOf("all" to "All music")
                DownloadCategory.ARTIST -> listOf(t.artistId.ifBlank { norm(t.artist) } to t.artist.trim())
                DownloadCategory.ALBUM -> listOf(t.albumId.ifBlank { "${norm(t.artist)}::${norm(t.album)}" } to "${t.artist.trim()} • ${t.album.trim()}")
                else -> (when (category) {
                    DownloadCategory.GENRE -> t.genres
                    DownloadCategory.MOOD -> t.moods
                    DownloadCategory.STYLE -> t.styles
                    else -> emptyList()
                }).map { norm(it) to it.trim() }
            }
            pairs.filter { it.first.isNotBlank() && it.second.isNotBlank() }.distinctBy { it.first }.forEach { (key, label) ->
                labels.putIfAbsent(key, label)
                grouped.getOrPut(key) { mutableListOf() }.add(t)
            }
        }
        return grouped.map { (key, tracks) -> SmartDownloadGroup(key, labels.getValue(key), tracks) }
            .sortedBy { norm(it.label) }
    }
    fun plan(state: LibraryState, request: SmartDownloadRequest, random: Random = Random.Default): SmartDownloadPlan {
        require(request.amount == DownloadAmount.ALL || request.count in 1..100_000) { "Enter a song count from 1 to 100000." }
        val groups = groups(available(state), request.category).filter { request.groups == null || it.key in request.groups }
        val pool = groups.flatMap { it.tracks }.distinctBy { it.id }
        val chosen = when (request.amount) {
            DownloadAmount.ALL -> pool
            DownloadAmount.TOTAL -> pool.shuffled(random).take(request.count)
            // Each group is sampled independently; overlapping tracks are downloaded once.
            DownloadAmount.PER_GROUP -> groups.flatMap { it.tracks.shuffled(random).take(request.count) }.distinctBy { it.id }
        }
        return SmartDownloadPlan(chosen, groups.size)
    }
}
