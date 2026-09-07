package com.m3.pocketmusic

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class PlaylistDownloadsTest {
    private fun songs() = (1..60).map { Track("$it", "Song $it", artistId = "${it % 6}", bytes = 4_000_000) }
    @Test fun randomSelectionHonorsExactCapAndIsReproducibleWithoutDuplicates() {
        val tracks = songs()
        val a = PlaylistDownloads.plan(tracks + tracks, 20_000_000, random = Random(42))
        assertEquals(5, a.tracks.size)
        assertEquals(20_000_000L, a.bytes)
        assertEquals(a, PlaylistDownloads.plan(tracks, 20_000_000, random = Random(42)))
        assertNotEquals(a.tracks, PlaylistDownloads.plan(tracks, 20_000_000, random = Random(99)).tracks)
    }
    @Test fun artistCapAndByteCapBothApplyToRandomSelection() {
        val plan = PlaylistDownloads.plan(songs(), 200_000_000, 2, Random(7))
        assertEquals(12, plan.tracks.size)
        assertTrue(plan.tracks.groupingBy { it.artistId }.eachCount().values.all { it == 2 })
        val small = PlaylistDownloads.plan(songs(), 12_000_000, 2, Random(7))
        assertEquals(3, small.tracks.size)
        assertTrue(small.tracks.groupingBy { it.artistId }.eachCount().values.all { it <= 2 })
    }
    @Test fun unknownAndOversizedFilesCannotBreakCapAndSmallFilesCanStillFit() {
        val tracks = listOf(Track("unknown", "Unknown"), Track("huge", "Huge", bytes = 10_000_000), Track("small", "Small", bytes = 1_000_000))
        val plan = PlaylistDownloads.plan(tracks, 1_000_000, random = Random(4))
        assertEquals(listOf("small"), plan.tracks.map { it.id })
        assertEquals(1, plan.unknownSizes)
        assertTrue(PlaylistDownloads.plan(tracks, 999_999).tracks.isEmpty())
        assertEquals(tracks, PlaylistDownloads.plan(tracks).tracks)
    }
    @Test fun invalidAndOverflowingNumericLimitsAreRejected() {
        listOf("", "0", "-1", "1.5", "abc", Long.MAX_VALUE.toString()).forEach { assertNull(PlaylistDownloads.byteLimit(it)) }
        assertEquals(500_000_000L, PlaylistDownloads.byteLimit("500"))
        assertThrows(IllegalArgumentException::class.java) { PlaylistDownloads.plan(songs(), 0) }
        assertThrows(IllegalArgumentException::class.java) { PlaylistDownloads.plan(songs(), 10, 0) }
    }
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
