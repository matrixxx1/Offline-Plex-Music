package com.m3.pocketmusic

import org.junit.Assert.*
import org.junit.Test

class CarCatalogTest {
    private val a = Track("plex:1", "First", "Artist", "Album", number = 1, remoteKey = "1", part = "/audio/1", localUri = "content://music/1", genres = listOf("ROCK"))
    private val b = a.copy(id = "plex:2", title = "Second", number = 2, localUri = "", genres = listOf("Rock"))
    private val own = Track("local:3", "Own song", "Beyoncé / AC|DC", "Local", localUri = "content://music/3")
    private val state = LibraryState(tracks = listOf(b, own, a), playlists = listOf(Playlist("p|one", "My playlist", listOf(b.id, a.id))))
    @Test fun rootHasFourBrowsableCategoriesAndRadioHasAllModes() {
        val c = CarCatalog(state)
        assertEquals(listOf("Library", "Downloads", "Playlists", "Radio"), c.children(CarCatalog.ROOT).map { it.title })
        assertTrue(c.children(CarCatalog.ROOT).none { it.playable })
        assertEquals(PlayMode.entries.toList(), c.children("radio").map { it.radio })
        assertTrue(CarCatalog(state.copy(twoTrack = true)).children("radio").all { it.subtitle.contains("2 track") })
    }
    @Test fun offlineCatalogSearchAndPlaylistsExcludeUndownloadedTracks() {
        val c = CarCatalog(state.copy(offline = true))
        assertEquals(setOf(a.id, own.id), c.children("tracks").map { it.track!!.id }.toSet())
        assertTrue(c.search("Second").isEmpty())
        val playlist = c.children("playlists").single()
        assertEquals(listOf(a.id), c.children(playlist.id).map { it.track!!.id })
        assertTrue(c.queue(b.id).tracks.isEmpty())
    }
    @Test fun downloadsIncludeOwnFilesAndGenresAreNormalized() {
        val c = CarCatalog(state)
        assertEquals(setOf(a.id, own.id), c.children("downloads").map { it.track!!.id }.toSet())
        assertEquals(2, c.children("genres").size)
        assertEquals(listOf(a.id, b.id), c.children(c.children("genres").first { it.title.equals("rock", true) }.id).map { it.track!!.id })
    }
    @Test fun playlistSelectionPreservesOrderAndStartIndex() {
        val c = CarCatalog(state)
        val children = c.children(c.children("playlists").single().id)
        val q = c.queue(children.last().id)
        assertEquals(listOf(b.id, a.id), q.tracks.map { it.id }); assertEquals(1, q.start)
        assertEquals(a, c.item(children.last().id)!!.track)
    }
    @Test fun opaqueIdsRoundTripUnicodeAndSeparators() {
        val c = CarCatalog(state)
        val artist = c.children("artists").first { it.title == own.artist }
        val track = c.children(artist.id).single()
        assertEquals(own, c.queue(track.id).tracks.single())
    }
    @Test fun malformedAndForeignPlaybackIdsNeverResolve() {
        val c = CarCatalog(state)
        listOf("https://example.com/song.mp3", "file:///secret", "play|%%%|broken", "radio|INVALID", "unknown", "range|%%%|-1|2").forEach {
            assertTrue(it, c.queue(it).tracks.isEmpty())
        }
    }
    @Test fun largeLibrariesUseReachableRangeFoldersAndBoundedQueue() {
        val c = CarCatalog(LibraryState(tracks = (0 until 10_005).map { a.copy(id = "$it", number = it) }))
        fun leaves(parent: String): List<CarEntry> {
            val rows = c.children(parent)
            assertTrue(rows.size <= 100)
            return rows.flatMap { if (it.playable) listOf(it) else leaves(it.id) }
        }
        val all = leaves("tracks")
        assertEquals(10_005, all.size); assertEquals(10_005, all.map { it.id }.distinct().size)
        val q = c.queue(all[555].id)
        assertTrue(q.tracks.size <= 500); assertEquals("555", q.tracks[q.start].id)
    }
    @Test fun searchMatchesWordsAcrossMetadataAndHonorsSelectedResult() {
        val c = CarCatalog(state)
        assertEquals(listOf(a), c.search("artist FIRST"))
        assertEquals(listOf(own), c.search("Beyoncé"))
        assertEquals(a, c.queue(c.searchEntries("first").single().id).tracks.single())
    }
}
