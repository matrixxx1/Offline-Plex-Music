package com.m3.pocketmusic

object PlaylistEditing {
    fun edit(p: Playlist, name: String = p.name, tracks: List<String> = p.tracks): Playlist {
        require(!p.smart) { "Plex smart playlists are managed by Plex filters." }
        require(name.isNotBlank()) { "Enter a playlist name." }
        val changed = p.plex && (name.trim() != p.serverName || tracks != p.serverTracks)
        return p.copy(name = name.trim(), tracks = tracks, pendingSync = changed, syncError = "")
    }
    fun move(p: Playlist, from: Int, to: Int): Playlist {
        require(from in p.tracks.indices && to in p.tracks.indices)
        val tracks = p.tracks.toMutableList().apply { add(to, removeAt(from)) }
        return edit(p, tracks = tracks)
    }
    fun merge(local: List<Playlist>, remote: List<Playlist>): List<Playlist> {
        val drafts = local.filter { it.plex && it.pendingSync }.associateBy { it.id }
        val remoteIds = remote.map { it.id }.toSet()
        return local.filterNot { it.plex } + remote.map { drafts[it.id] ?: it } + drafts.values.filter { it.id !in remoteIds }
    }
    fun checkpoint(current: Playlist, remote: Playlist, complete: Boolean): Playlist = current.copy(
        serverName = remote.name, serverTracks = remote.tracks,
        pendingSync = !complete || current.name != remote.name || current.tracks != remote.tracks,
        syncError = "")
}
