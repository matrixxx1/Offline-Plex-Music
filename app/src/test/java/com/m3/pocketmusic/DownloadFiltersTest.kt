package com.m3.pocketmusic

import org.junit.Assert.*
import org.junit.Test

class DownloadFiltersTest {
    private fun track(id: String, artist: String = "North Coast", albumId: String = "album-a", genre: List<String> = listOf("Indie"), rating: Double = 4.0) =
        Track(id, "Song $id", artist, "Shared album title", albumId = albumId, genres = genre,
            localUri = "content://downloads/$id", remoteKey = id, exactPlexRating = rating * 2)
    private val tracks = listOf(track("a", rating = 1.0), track("b", artist = "north coast", rating = 2.5),
        track("c", artist = "Afterglow", albumId = "album-b", genre = listOf(" Indie ", "Jazz"), rating = 3.0),
        track("unrated", rating = 0.0), track("stream").copy(localUri = ""),
        track("own").copy(remoteKey = "", localRating = 2))
    @Test fun allOnlyIncludesLocalFiles() {
        assertEquals(setOf("a", "b", "c", "unrated", "own"), DownloadFilters.matching(tracks, DownloadFilter.ALL).map { it.id }.toSet())
    }
    @Test fun artistIsCaseInsensitiveAndOptionsAreUnique() {
        assertEquals(2, DownloadFilters.options(tracks, DownloadFilter.ARTIST).size)
        assertEquals(setOf("a", "b", "unrated", "own"), DownloadFilters.matching(tracks, DownloadFilter.ARTIST, "north coast").map { it.id }.toSet())
    }
    @Test fun albumUsesIdentityRatherThanSharedTitle() {
        assertEquals(listOf("c"), DownloadFilters.matching(tracks, DownloadFilter.ALBUM, "album-b").map { it.id })
    }
    @Test fun genreTrimsAndHandlesMultipleTagsWithoutDuplicateTracks() {
        val match = DownloadFilters.matching(tracks, DownloadFilter.GENRE, "indie")
        assertEquals(5, match.size); assertEquals(match.size, match.distinctBy { it.id }.size)
        assertEquals(listOf("c"), DownloadFilters.matching(tracks, DownloadFilter.GENRE, "jazz").map { it.id })
    }
    @Test fun thresholdIsStrictAndHalfStarsAreNotRounded() {
        assertEquals(setOf("a", "own"), DownloadFilters.matching(tracks, DownloadFilter.BELOW_RATING, below = 2.5).map { it.id }.toSet())
        assertEquals(setOf("a", "b", "own"), DownloadFilters.matching(tracks, DownloadFilter.BELOW_RATING, below = 3.0).map { it.id }.toSet())
    }
    @Test fun queuedRatingOverridesRemoteAndUnratedIsOptIn() {
        val updated = tracks.map { if (it.id == "c") it.copy(pendingRating = 1) else if (it.id == "a") it.copy(pendingRating = 5) else it }
        assertEquals(setOf("b", "c", "own"), DownloadFilters.matching(updated, DownloadFilter.BELOW_RATING, below = 3.0).map { it.id }.toSet())
        assertTrue(DownloadFilters.matching(updated, DownloadFilter.BELOW_RATING, below = 3.0, includeUnrated = true).any { it.id == "unrated" })
    }
    @Test fun ownFilesCanBeExcludedAndNullGroupNeverSelectsAll() {
        assertFalse(DownloadFilters.matching(tracks, DownloadFilter.ALL, includeOwnFiles = false).any { it.id == "own" })
        assertTrue(DownloadFilters.matching(tracks, DownloadFilter.ALBUM).isEmpty())
    }
    @Test fun blankGenreIsExplicitUnspecifiedChoice() {
        val t = track("blank", genre = listOf(" ", ""))
        assertEquals("Unspecified", DownloadFilters.options(listOf(t), DownloadFilter.GENRE).single().label)
        assertEquals(listOf(t), DownloadFilters.matching(listOf(t), DownloadFilter.GENRE, "unspecified"))
    }
}
