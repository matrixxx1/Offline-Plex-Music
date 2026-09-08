package com.m3.pocketmusic

import org.junit.Assert.*
import org.junit.Test

class PlaylistEditingTest {
    private val original = Playlist("plex:p", "Road", listOf("a", "b", "a"), true)
    @Test fun draftRetainsBaselineAndDuplicateOccurrencesAcrossRestart() {
        val moved = PlaylistEditing.move(original, 2, 0)
        val draft = PlaylistEditing.edit(moved, name = "Road trip")
        val restored = LibraryJson.decode(LibraryJson.encode(LibraryState(playlists = listOf(draft)))).playlists.single()
        assertEquals(listOf("a", "a", "b"), restored.tracks)
        assertEquals(original.tracks, restored.serverTracks)
        assertEquals("Road", restored.serverName)
        assertTrue(restored.pendingSync)
    }
    @Test fun refreshPreservesDraftEvenIfRemotePlaylistWasDeleted() {
        val draft = PlaylistEditing.edit(original, name = "My edits")
        assertEquals(draft, PlaylistEditing.merge(listOf(draft), listOf(original.copy(name = "Other device"))).single())
        assertEquals(draft, PlaylistEditing.merge(listOf(draft), emptyList()).single())
    }
    @Test fun oldSnapshotsUseOriginalPlaylistAsBaseline() {
        val restored = LibraryJson.decode("""{"playlists":[{"id":"plex:p","name":"Old","tracks":["a"],"plex":true}]}""").playlists.single()
        assertEquals(restored.tracks, restored.serverTracks)
        assertEquals(restored.name, restored.serverName)
        assertFalse(restored.pendingSync)
    }
    @Test fun revertingEditsClearsQueueAndAcknowledgementKeepsConcurrentChanges() {
        val draft = PlaylistEditing.edit(original, name = "New")
        assertFalse(PlaylistEditing.edit(draft, name = original.name).pendingSync)
        val remote = original.copy(name = "New")
        assertFalse(PlaylistEditing.checkpoint(draft, remote, true).pendingSync)
        assertTrue(PlaylistEditing.checkpoint(draft.copy(name = "Newer"), remote, true).pendingSync)
    }
    @Test fun localEditsNeverQueuePlexWritesAndSmartPlaylistsStayReadOnly() {
        assertFalse(PlaylistEditing.edit(original.copy(plex = false), name = "Local").pendingSync)
        assertThrows(IllegalArgumentException::class.java) { PlaylistEditing.edit(original.copy(smart = true), name = "No") }
    }
}
