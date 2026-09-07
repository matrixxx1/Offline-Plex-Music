package com.m3.pocketmusic

import org.junit.Assert.*
import org.junit.Test

class PlaylistDownloadsTest {
    @Test fun overlapsAreDeduplicatedAndOnlyMissingRemoteFilesAreQueued() {
        val tracks = (1..6).map { Track("$it", "Song $it", remoteKey = "$it", part = "/$it") }.map {
            when (it.id) { "1" -> it.copy(localUri = "file:///1"); "5" -> it.copy(part = ""); "6" -> it.copy(remoteKey = ""); else -> it }
        }
        val state = LibraryState(tracks = tracks, downloads = listOf(DownloadJob("2", "Failed")), playlists = listOf(
            Playlist("a", "A", listOf("1", "2", "3", "missing"), true), Playlist("b", "B", listOf("3", "4", "5", "6"), true)))
        assertEquals(listOf("3", "4"), PlaylistDownloads.pending(state, setOf("a", "b")).map { it.id })
        assertTrue(PlaylistDownloads.pending(state, emptySet()).isEmpty())
    }
    @Test fun localPlaylistsDoNotBecomePlexDownloads() {
        val state = LibraryState(tracks = listOf(Track("1", "Song", remoteKey = "1", part = "/1")),
            playlists = listOf(Playlist("local", "Local", listOf("1"))))
        assertTrue(PlaylistDownloads.pending(state, setOf("local")).isEmpty())
    }
}
