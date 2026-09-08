package com.m3.pocketmusic

import org.junit.Assert.*
import org.junit.Test

class PendingChangesTest {
    @Test fun pendingDeletionAndArtworkSurviveRestartAndRatingChanges() {
        val track = Track("plex:server:1", "Song", remoteKey = "1", localUri = "content://song", pendingDeletion = true, artwork = "/library/metadata/album/thumb/1")
        val rated = RatingRules.rate(track, 4)
        val restored = LibraryJson.decode(LibraryJson.encode(LibraryState(tracks = listOf(rated)))).tracks.single()
        assertTrue(restored.pendingDeletion)
        assertEquals(4, restored.pendingRating)
        assertEquals(track.artwork, restored.artwork)
        assertTrue(restored.downloaded)
        assertTrue(RatingRules.acknowledged(restored, 4).pendingDeletion)
    }
    @Test fun oldLibrariesHaveNoDeletionFlags() {
        val restored = LibraryJson.decode("""{"tracks":[{"id":"old","title":"Old song"}]}""").tracks.single()
        assertFalse(restored.pendingDeletion)
        assertEquals("", restored.artwork)
    }
}
