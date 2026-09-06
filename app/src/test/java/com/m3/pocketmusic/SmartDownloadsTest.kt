package com.m3.pocketmusic

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class SmartDownloadsTest {
    private fun song(id: String, artist: String = "Artist", album: String = "Album", genres: List<String> = listOf("Rock")) =
        Track(id, "Song $id", artist, album, genres = genres, remoteKey = id, part = "/audio/$id", bytes = 100)

    @Test fun onlyNewDownloadablePlexTracksArePlanned() {
        val ready = song("ready")
        val state = LibraryState(tracks = listOf(ready, ready, song("local").copy(remoteKey = ""),
            song("downloaded").copy(localUri = "content://music/1"), song("queued"), song("failed"),
            song("missing").copy(part = ""), song("bad").copy(part = "//other-host/audio")),
            downloads = listOf(DownloadJob("queued"), DownloadJob("failed", "Failed")))
        assertEquals(listOf(ready), SmartDownloads.plan(state, SmartDownloadRequest()).tracks)
    }
    @Test fun perArtistRandomSelectionHonorsLimitsAndShortGroups() {
        val state = LibraryState(tracks = (1..20).map { song("a$it", "Alpha") } + song("b1", "Beta"))
        val request = SmartDownloadRequest(DownloadCategory.ARTIST, amount = DownloadAmount.PER_GROUP, count = 3)
        val plan = SmartDownloads.plan(state, request, Random(42))
        assertEquals(3, plan.tracks.count { it.artist == "Alpha" }); assertEquals(1, plan.tracks.count { it.artist == "Beta" })
        assertEquals(2, plan.groups)
        assertEquals(plan, SmartDownloads.plan(state, request, Random(42)))
        assertNotEquals(plan.tracks, SmartDownloads.plan(state, request, Random(99)).tracks)
    }
    @Test fun perGenreSamplesEachGroupAndDownloadsOverlapsOnce() {
        val shared = song("shared", genres = listOf("Rock", "Jazz", " rock "))
        val state = LibraryState(tracks = listOf(shared, song("rock"), song("jazz", genres = listOf("Jazz"))))
        val plan = SmartDownloads.plan(state, SmartDownloadRequest(DownloadCategory.GENRE, amount = DownloadAmount.PER_GROUP, count = 2), Random(7))
        assertEquals(setOf("shared", "rock", "jazz"), plan.tracks.map { it.id }.toSet())
        assertEquals(3, plan.tracks.size); assertEquals(300, plan.bytes); assertEquals(2, plan.groups)
    }
    @Test fun randomTotalIsGlobalAndSelectedGroupsAreExact() {
        val state = LibraryState(tracks = (1..30).map { song("$it", genres = listOf(if (it % 2 == 0) "Rock" else "Rockabilly")) })
        val plan = SmartDownloads.plan(state, SmartDownloadRequest(DownloadCategory.GENRE, setOf("rock"), DownloadAmount.TOTAL, 5), Random(3))
        assertEquals(5, plan.tracks.size); assertTrue(plan.tracks.all { it.genres == listOf("Rock") })
    }
    @Test fun selectingAlbumsKeepsAlbumsWithSameTitleSeparate() {
        val one = song("1", "Alpha", "Greatest Hits").copy(albumId = "album-1")
        val two = song("2", "Beta", "Greatest Hits").copy(albumId = "album-2")
        val state = LibraryState(tracks = listOf(one, two))
        val groups = SmartDownloads.groups(state.tracks, DownloadCategory.ALBUM)
        assertEquals(2, groups.size)
        assertEquals(listOf(two), SmartDownloads.plan(state, SmartDownloadRequest(DownloadCategory.ALBUM, setOf("album-2"))).tracks)
    }
    @Test fun moodsAndStylesUseOnlyExistingNormalizedTags() {
        val state = LibraryState(tracks = listOf(song("1").copy(moods = listOf(" Happy ", "HAPPY", ""), styles = listOf("Indie Rock")),
            song("2").copy(moods = listOf("Sad")), song("3")))
        assertEquals(listOf("Happy", "Sad"), SmartDownloads.groups(state.tracks, DownloadCategory.MOOD).map { it.label })
        assertEquals(listOf("1"), SmartDownloads.plan(state, SmartDownloadRequest(DownloadCategory.MOOD, setOf("happy"))).tracks.map { it.id })
        assertEquals(listOf("1"), SmartDownloads.plan(state, SmartDownloadRequest(DownloadCategory.STYLE, setOf("indie rock"))).tracks.map { it.id })
        assertTrue(SmartDownloads.plan(state, SmartDownloadRequest(DownloadCategory.MOOD, setOf("angry"))).tracks.isEmpty())
    }
    @Test fun emptySelectionsAndMissingTagsNeverExpandToWholeLibrary() {
        val state = LibraryState(tracks = listOf(song("1", genres = listOf(" "))))
        assertTrue(SmartDownloads.plan(state, SmartDownloadRequest(groups = emptySet())).tracks.isEmpty())
        assertTrue(SmartDownloads.plan(state, SmartDownloadRequest(DownloadCategory.GENRE)).tracks.isEmpty())
        assertTrue(SmartDownloads.plan(state, SmartDownloadRequest(DownloadCategory.MOOD)).tracks.isEmpty())
    }
    @Test fun invalidCountsAreRejectedAndOversizedRequestsUseAvailableSongs() {
        val state = LibraryState(tracks = listOf(song("1")))
        listOf(0, -1, 100001).forEach { count ->
            assertThrows(IllegalArgumentException::class.java) { SmartDownloads.plan(state, SmartDownloadRequest(amount = DownloadAmount.TOTAL, count = count)) }
        }
        assertEquals(1, SmartDownloads.plan(state, SmartDownloadRequest(amount = DownloadAmount.TOTAL, count = 100)).tracks.size)
    }
    @Test fun newTagsPersistWhileOlderLibrariesKeepRatingsAndDownloads() {
        val original = LibraryState(tracks = listOf(song("1").copy(moods = listOf("Happy"), styles = listOf("Indie"),
            pendingRating = 1, localUri = "content://music/1")))
        assertEquals(original, LibraryJson.decode(LibraryJson.encode(original)))
        val old = JSONObject(LibraryJson.encode(original)).apply { getJSONArray("tracks").getJSONObject(0).apply { remove("moods"); remove("styles") } }
        val decoded = LibraryJson.decode(old.toString()).tracks.single()
        assertTrue(decoded.moods.isEmpty()); assertTrue(decoded.styles.isEmpty())
        assertEquals(1, decoded.pendingRating); assertTrue(decoded.downloaded)
    }
}
