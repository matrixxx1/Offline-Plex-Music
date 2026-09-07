package com.m3.pocketmusic

import org.junit.Assert.*
import org.junit.Test

class LibraryIndexTest {
    @Test fun largePlaylistUsesSingleIndexPassRatherThanScanningForEachSong() {
        var reads = 0
        val source = object : AbstractList<Track>() {
            override val size = 31_382
            override fun get(index: Int): Track { reads++; return Track("$index", "Song $index") }
        }
        val index = LibraryIndex(source)
        assertEquals(source.size, reads)
        val playlist = Playlist("big", "All music", (source.size - 1 downTo 0).map { "$it" })
        val resolved = index.resolve(playlist)
        assertEquals(source.size, resolved.size)
        assertEquals("31381", resolved.first().id)
        assertEquals("0", resolved.last().id)
        assertEquals("Playlist resolution must not scan the source list again", source.size, reads)
    }
    @Test fun playlistOrderRepeatsAndMissingIdsAreHandledWithoutLosingMetadata() {
        val track = Track("a", "Song", pendingRating = 1, localUri = "file:///song")
        val index = LibraryIndex(listOf(track, Track("b", "Other")))
        assertEquals(listOf(track, track), index.resolve(Playlist("p", "P", listOf("a", "missing", "a"))))
        assertEquals(listOf(track), LibraryRequest(index, null, true, "Song", "Tracks", null).load().visible)
    }
}
