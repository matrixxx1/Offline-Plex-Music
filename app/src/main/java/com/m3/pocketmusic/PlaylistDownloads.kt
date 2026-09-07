package com.m3.pocketmusic

object PlaylistDownloads {
    fun pending(state: LibraryState, playlistIds: Set<String>): List<Track> {
        val tracks = state.tracks.associateBy { it.id }
        val queued = state.downloads.map { it.id }.toSet()
        return state.playlists.filter { it.plex && it.id in playlistIds }.flatMap { it.tracks }.distinct()
            .mapNotNull { tracks[it] }.filter { !it.downloaded && it.id !in queued && it.remoteKey.isNotBlank() && it.part.isNotBlank() }
    }
}
