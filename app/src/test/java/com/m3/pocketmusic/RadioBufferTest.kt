package com.m3.pocketmusic

import org.junit.Assert.*
import org.junit.Test

class RadioBufferTest {
    private val tracks = (1..350).map { Track("$it", "Song $it", artist = "Artist", album = "Album", number = it) }
    @Test fun largeArtistPlaysAllTracksInBoundedChunksBeforeRepeating() {
        val buffer = RadioBuffer()
        val chunks = List(4) { buffer.next(tracks, PlayMode.RANDOM_ARTIST, false) }
        assertEquals(listOf(100, 100, 100, 50), chunks.map { it.size })
        assertEquals(tracks.map { it.id }, chunks.flatten().map { it.id })
        assertEquals("1", buffer.next(tracks, PlayMode.RANDOM_ARTIST, false).first().id)
    }
    @Test fun resetAndRemovedTracksCannotLeakIntoNextTask() {
        val buffer = RadioBuffer()
        buffer.next(tracks, PlayMode.RANDOM_ALBUM, false)
        assertEquals(listOf("350"), buffer.next(tracks.takeLast(1), PlayMode.RANDOM_ALBUM, false).map { it.id })
        buffer.reset()
        assertEquals(listOf("1", "2"), buffer.next(tracks, PlayMode.RANDOM_ARTIST, true).map { it.id })
        assertTrue(buffer.next(emptyList(), PlayMode.RANDOM_ARTIST, false).isEmpty())
    }
}
