package com.m3.pocketmusic

/** Keep a large album/artist intact, but only hand a small chunk to the media player. */
class RadioBuffer(private val planner: RadioPlanner = RadioPlanner()) {
    private val pending = ArrayDeque<Track>()
    fun reset() { pending.clear(); planner.reset() }
    fun next(pool: List<Track>, mode: PlayMode, twoTrack: Boolean): List<Track> {
        val allowed = pool.map { it.id }.toSet()
        pending.removeAll { it.id !in allowed }
        if (pending.isEmpty()) pending.addAll(planner.next(pool, mode, twoTrack))
        return List(minOf(100, pending.size)) { pending.removeFirst() }
    }
}
