package com.m3.pocketmusic

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class RadioPlannerTest {
    private val library = (1..3).flatMap { artist -> (1..2).flatMap { album -> (1..4).map { n ->
        Track("$artist-$album-$n", "Song $n", "Artist $artist", "Album $album", "$artist-$album", "$artist", listOf(if (artist == 1) "Rock" else "Jazz"), number = n)
    } } }
    @Test fun albumsFinishInOrderAndChangeGroup() {
        val planner = RadioPlanner(Random(8)); var last = ""
        repeat(50) {
            val task = planner.next(library.shuffled(Random(it)), PlayMode.RANDOM_ALBUM, false)
            assertEquals(4, task.size); assertEquals(1, task.map { it.albumGroup }.distinct().size)
            assertEquals(listOf(1, 2, 3, 4), task.map { it.number }); assertNotEquals(last, task.first().albumGroup)
            last = task.first().albumGroup
        }
    }
    @Test fun artistsFinishAllAlbumsBeforeChanging() {
        val p = RadioPlanner(Random(2)); val first = p.next(library, PlayMode.RANDOM_ARTIST, false)
        assertEquals(8, first.size); assertEquals(1, first.map { it.artist }.distinct().size)
        assertNotEquals(first.first().artist, p.next(library, PlayMode.RANDOM_ARTIST, false).first().artist)
    }
    @Test fun everyModeHonorsTwoTrackLimit() {
        PlayMode.entries.forEach { mode ->
            val task = RadioPlanner(Random(0)).next(library, mode, true)
            assertEquals(if (mode == PlayMode.RANDOM_TRACK) 1 else 2, task.size)
        }
    }
    @Test fun randomTrackChangesOnEveryTask() {
        val p = RadioPlanner(Random(9)); var previous = ""
        repeat(100) { val task = p.next(library, PlayMode.RANDOM_TRACK, false); assertEquals(1, task.size); assertNotEquals(previous, task.single().id); previous = task.single().id }
    }
    @Test fun genrePlaysWholeGroupThenChanges() {
        val p = RadioPlanner(Random(3)); val a = p.next(library, PlayMode.RANDOM_GENRE, false); val b = p.next(library, PlayMode.RANDOM_GENRE, false)
        assertEquals(1, a.flatMap { it.genres }.distinct().size)
        assertNotEquals(a.first().genres, b.first().genres)
        assertEquals(library.size, a.size + b.size)
    }
    @Test fun overlappingGenreTagsDoNotDuplicateTracksInTask() {
        val pool = listOf(Track("a", "A", genres = listOf("Rock", "rock", "Pop")), Track("b", "B", genres = listOf("Rock")))
        repeat(10) { val task = RadioPlanner(Random(it)).next(pool, PlayMode.RANDOM_GENRE, false); assertEquals(task.size, task.distinctBy { it.id }.size) }
    }
    @Test fun emptyAndSingleItemLibrariesDoNotFail() {
        val p = RadioPlanner()
        PlayMode.entries.forEach { assertTrue(p.next(emptyList(), it, true).isEmpty()); assertEquals(1, p.next(library.take(1), it, true).size) }
    }
    @Test fun discOrderIsPreserved() {
        val tracks = listOf(Track("b", "B", albumId = "album", disc = 2, number = 1), Track("a", "A", albumId = "album", disc = 1, number = 9))
        assertEquals(listOf("a", "b"), RadioPlanner().next(tracks, PlayMode.RANDOM_ALBUM, false).map { it.id })
    }
}
