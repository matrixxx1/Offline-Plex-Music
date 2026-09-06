package com.m3.pocketmusic

import org.junit.Assert.*
import org.junit.Test

class RatingRulesTest {
    private val remote = Track("plex:server:1", "Song", remoteKey = "1", serverRating = 4)
    @Test fun ratingsStayQueuedAndCoalesce() {
        val t = RatingRules.rate(RatingRules.rate(remote, 2), 1)
        assertEquals(4, t.serverRating); assertEquals(1, t.rating); assertEquals(1, t.pendingRating)
    }
    @Test fun syncClearsOnlyAcknowledgedRating() {
        val t = RatingRules.acknowledged(RatingRules.rate(remote, 1), 1)
        assertNull(t.pendingRating); assertEquals(1, t.serverRating)
    }
    @Test fun newRatingDuringSyncIsNotLost() {
        val t = RatingRules.acknowledged(RatingRules.rate(remote, 5), 1)
        assertEquals(5, t.pendingRating); assertEquals(5, t.rating); assertEquals(1, t.serverRating)
    }
    @Test fun localFilesNeverCreatePlexQueueEntries() {
        val t = RatingRules.rate(Track("local:a", "Local"), 1)
        assertNull(t.pendingRating); assertEquals(1, t.localRating)
    }
    @Test fun cleanupUsesEffectiveRatingIncludingUnsynced() {
        val local = RatingRules.rate(Track("local:a", "Local"), 1)
        assertEquals(listOf(remote.id, local.id), RatingRules.oneStar(listOf(RatingRules.rate(remote, 1), local, remote.copy(id = "other"))).map { it.id })
    }
    @Test fun clearingRatingIsQueuedAsZero() {
        val t = RatingRules.rate(remote, 0); assertEquals(0, t.pendingRating); assertEquals(0, t.rating)
    }
    @Test fun halfStarPlexRatingsAreNeverRoundedIntoCleanup() {
        val tracks = listOf(remote.copy(exactPlexRating = 1.0, serverRating = 1), remote.copy(id = "one", exactPlexRating = 2.0, serverRating = 1), remote.copy(id = "half", exactPlexRating = 3.0, serverRating = 2))
        assertEquals(listOf("one"), RatingRules.oneStar(tracks).map { it.id })
        assertEquals("0.5", tracks.first().ratingText)
    }
    @Test fun queuedRatingsDownloadsAndPlaylistsSurviveSerialization() {
        val state = LibraryState(tracks = listOf(RatingRules.rate(remote, 1).copy(localUri = "content://music/a")),
            playlists = listOf(Playlist("p", "Road trip", listOf(remote.id))), downloads = listOf(DownloadJob(remote.id, "Failed", "Disconnected")),
            folder = "content://music", mode = PlayMode.RANDOM_GENRE, twoTrack = true, offline = true)
        assertEquals(state, LibraryJson.decode(LibraryJson.encode(state)))
    }
    @Test(expected = IllegalArgumentException::class) fun invalidRatingRejected() { RatingRules.rate(remote, 6) }
}
