@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.m3.pocketmusic

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.Rating
import androidx.media3.common.StarRating
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.LibraryResult
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionCommand
import androidx.media3.session.CommandButton
import com.google.common.util.concurrent.SettableFuture
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class Playing(val trackId: String? = null, val playing: Boolean = false, val error: String = "", val radio: Boolean = false)
class PlaybackService : MediaLibraryService() {
    private lateinit var player: ExoPlayer
    private var session: MediaLibrarySession? = null
    private val subscriptions = mutableMapOf<MediaSession.ControllerInfo, MutableSet<String>>()
    private val planner = RadioBuffer()
    private var appending = false
    private var sequence: List<Track> = emptyList()
    private var sequencePosition = 0
    private var radio = false
    private var moreCarActions = false
    private var removingIds: Set<String> = emptySet()
    private var scopeIds: Set<String>? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var config = PlexConfig()
    private lateinit var http: DefaultHttpDataSource.Factory
    override fun onCreate() {
        super.onCreate()
        instance = this
        config = musicStore.credentials.read()
        http = DefaultHttpDataSource.Factory().setDefaultRequestProperties(mapOf("X-Plex-Token" to config.token))
        player = ExoPlayer.Builder(this).setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http))).build()
        player.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
        player.setHandleAudioBecomingNoisy(true)
        player.setWakeMode(C.WAKE_MODE_LOCAL)
        session = MediaLibrarySession.Builder(this, object : ForwardingPlayer(player) {
            override fun stop() { stopPlayback() }
        }, CarCallback()).setCustomLayout(carButtons()).setMediaButtonPreferences(carButtons()).setSessionActivity(PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build()
        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                status.value = status.value.copy(trackId = player.currentMediaItem?.mediaId, playing = player.isPlaying, radio = radio)
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                updateCarButtons()
                val track = musicStore.state.value.tracks.find { it.id == mediaItem?.mediaId }
                if (track != null && mediaItem?.mediaMetadata?.artworkData == null) scope.launch {
                    val art = ArtworkCache.load(this@PlaybackService, track)
                    if (art != null && player.currentMediaItem?.mediaId == track.id) {
                        val bytes = java.io.ByteArrayOutputStream().also { art.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
                        val current = player.currentMediaItem!!
                        player.replaceMediaItem(player.currentMediaItemIndex, current.buildUpon().setMediaMetadata(current.mediaMetadata.buildUpon().setArtworkData(bytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER).build()).build())
                    }
                }
                if (mediaItem != null && player.currentMediaItemIndex >= player.mediaItemCount - 2) {
                    if (radio) appendTask() else appendSequential()
                }
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED && radio) {
                    appendTask()
                    if (radio && player.mediaItemCount > 0) { player.prepare(); player.play() } else stopPlayback()
                }
            }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS) stopPlayback()
            }
            override fun onPlayerError(error: PlaybackException) {
                stopPlayback()
                status.value = status.value.copy(error = "Playback failed (${error.errorCodeName}). Check file access or Plex connection, then tap a track to retry.")
            }
        })
        scope.launch {
            var previousOffline = musicStore.state.value.offline
            var previousCatalog: LibraryState? = null
            musicStore.state.collect { state ->
                if (state.offline && !previousOffline) {
                    // Rebuild URIs too: a previously queued stream may have since downloaded.
                    val wasRadio = radio; radio = false
                    val current = player.currentMediaItem?.mediaId
                    val position = player.currentPosition
                    val wasPlaying = player.playWhenReady
                    val available = state.tracks.filter { it.downloaded }.associateBy { it.id }
                    val upcoming = (player.currentMediaItemIndex.coerceAtLeast(0) until player.mediaItemCount)
                        .mapNotNull { available[player.getMediaItemAt(it).mediaId] }.mapNotNull { media(it) }
                    player.setMediaItems(upcoming, 0, if (upcoming.firstOrNull()?.mediaId == current) position else 0)
                    radio = wasRadio
                    if (radio) appendTask()
                    player.prepare(); player.playWhenReady = wasPlaying
                }
                previousOffline = state.offline
                if (subscriptions.isNotEmpty() && (previousCatalog?.tracks != state.tracks || previousCatalog?.playlists != state.playlists || previousCatalog?.offline != state.offline)) {
                    val parents = subscriptions.values.flatMap { it }.distinct()
                    val counts = withContext(Dispatchers.Default) {
                        val catalog = CarCatalog(state)
                        parents.associateWith { catalog.children(it).size }
                    }
                    counts.forEach { (parent, count) -> session?.notifyChildrenChanged(parent, count, null) }
                }
                val tracksChanged = previousCatalog?.tracks !== state.tracks
                previousCatalog = state
                if (!tracksChanged) return@collect
                updateCarButtons()
                val index = player.currentMediaItemIndex
                val current = player.currentMediaItem
                val updated = state.tracks.find { it.id == current?.mediaId }
                if (current != null && updated != null && current.mediaMetadata.userRating != starRating(updated)) {
                    player.replaceMediaItem(index, current.buildUpon().setMediaMetadata(current.mediaMetadata.buildUpon().setUserRating(starRating(updated)).build()).build())
                }
            }
        }
    }
    private fun saveCarChange(change: () -> Unit): ListenableFuture<SessionResult> {
        val result = SettableFuture.create<SessionResult>()
        scope.launch {
            try {
                withContext(Dispatchers.IO) { change() }
                result.set(SessionResult(SessionResult.RESULT_SUCCESS))
            } catch (e: Exception) {
                status.value = status.value.copy(error = "Could not save changes: ${e.message}")
                result.set(SessionResult(SessionError.ERROR_IO))
            }
        }
        return result
    }
    fun reloadConnection() {
        stopPlayback()
        config = musicStore.credentials.read()
        http.setDefaultRequestProperties(mapOf("X-Plex-Token" to config.token))
    }
    private fun carButtons(): List<CommandButton> {
        val track = musicStore.state.value.tracks.find { it.id == player.currentMediaItem?.mediaId }
        fun button(icon: Int, resource: Int, label: String, action: String) = CommandButton.Builder(icon)
            .setIconResId(resource).setDisplayName(label).setSlots(CommandButton.SLOT_OVERFLOW)
            .setSessionCommand(SessionCommand(action, Bundle.EMPTY)).build()
        val more = button(CommandButton.ICON_UNDEFINED, R.drawable.ic_more, if (moreCarActions) "Back to playback actions" else "More song actions", MORE_ACTIONS)
        return if (!moreCarActions) listOf(
            button(CommandButton.ICON_SHUFFLE_ON, R.drawable.ic_shuffle, "Shuffle playlist", SHUFFLE),
            button(CommandButton.ICON_STAR_FILLED, R.drawable.ic_star, "Rating ${track?.rating ?: 0}/5 · tap for next rating", RATE_CYCLE),
            button(CommandButton.ICON_NEXT, R.drawable.ic_next, "Next artist in playlist", NEXT_ARTIST), more
        ) else listOf(
            button(CommandButton.ICON_UNDEFINED, R.drawable.ic_delete, if (track?.pendingDeletion == true) "Unflag Plex deletion" else "Flag Plex deletion on sync", FLAG_DELETE),
            button(CommandButton.ICON_UNDEFINED, R.drawable.ic_delete, "Delete downloaded copy", DELETE_LOCAL),
            button(CommandButton.ICON_STOP, R.drawable.ic_stop, "Stop playback", STOP), more
        )
    }
    private fun updateCarButtons() {
        session?.setCustomLayout(carButtons())
        session?.setMediaButtonPreferences(carButtons())
    }
    private fun playlistSequence(): List<Track> = if (sequence.isNotEmpty()) sequence else
        (0 until player.mediaItemCount).mapNotNull { i -> musicStore.state.value.tracks.find { it.id == player.getMediaItemAt(i).mediaId } }
    fun shufflePlaylist() { playTracks(playlistSequence().shuffled()) }
    fun nextArtist() {
        val tracks = playlistSequence()
        val current = tracks.indexOfFirst { it.id == player.currentMediaItem?.mediaId }
        val artist = tracks.getOrNull(current)?.artistGroup ?: return
        val next = ((current + 1 until tracks.size) + (0 until current)).firstOrNull {
            tracks[it].artistGroup != artist && (!musicStore.state.value.offline || tracks[it].downloaded)
        } ?: return
        playTracks(tracks, next)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == android.provider.MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH) {
            val query = intent.getStringExtra(android.app.SearchManager.QUERY).orEmpty()
            if (query.isBlank()) startRadio() else playTracks(CarCatalog(musicStore.state.value).search(query))
            return START_NOT_STICKY
        }
        return super.onStartCommand(intent, flags, startId)
    }
    fun startRadio(ids: Set<String>? = null) {
        radio = false; sequence = emptyList(); scopeIds = ids; planner.reset()
        player.stop(); player.clearMediaItems()
        radio = true; appendTask(); appendTask()
        status.value = status.value.copy(error = "")
        if (player.mediaItemCount > 0) { player.prepare(); player.play() } else stopPlayback()
    }
    fun playTracks(tracks: List<Track>, start: Int = 0) {
        radio = false; scopeIds = null; sequence = tracks
        val chosen = start.coerceIn(0, (tracks.size - 1).coerceAtLeast(0))
        sequencePosition = (chosen - 50).coerceAtLeast(0)
        val chunkStart = sequencePosition
        val playable = nextSequentialChunk()
        if (playable.isEmpty()) { stopPlayback(); return }
        val selectedId = tracks.getOrNull(chosen)?.id
        val occurrence = tracks.subList(chunkStart, chosen).count { it.id == selectedId }
        val selectedIndex = playable.withIndex().filter { it.value.mediaId == selectedId }.getOrNull(occurrence)?.index ?: 0
        appending = true
        try { player.setMediaItems(playable, selectedIndex, 0) }
        finally { appending = false }
        status.value = status.value.copy(error = "")
        player.prepare(); player.play()
    }
    private fun nextSequentialChunk(): List<MediaItem> {
        val current = musicStore.state.value.tracks.associateBy { it.id }
        val chunk = mutableListOf<MediaItem>()
        while (sequencePosition < sequence.size && chunk.size < 100) {
            val id = sequence[sequencePosition++].id
            if (id !in removingIds) current[id]?.let { media(it) }?.let { chunk.add(it) }
        }
        return chunk
    }
    private fun appendSequential() {
        if (radio || appending || sequencePosition >= sequence.size) return
        appending = true
        try {
            player.addMediaItems(nextSequentialChunk())
            if (player.currentMediaItemIndex > 100) player.removeMediaItems(0, player.currentMediaItemIndex - 50)
        } finally { appending = false }
    }
    fun settingsChanged() {
        if (radio) {
            if (player.currentMediaItemIndex + 1 < player.mediaItemCount) player.removeMediaItems(player.currentMediaItemIndex + 1, player.mediaItemCount)
            planner.reset(); appendTask()
        }
    }
    private fun appendTask() {
        if (!radio || appending) return
        appending = true
        try {
            val s = musicStore.state.value
            val pool = s.tracks.filter { it.id !in removingIds && (scopeIds == null || it.id in scopeIds!!) && (!s.offline || it.downloaded) && (it.downloaded || (it.part.isNotBlank() && config.url.isNotBlank())) }
            val next = planner.next(pool, s.mode, s.twoTrack).mapNotNull { media(it) }
            if (next.isEmpty()) { radio = false; return }
            player.addMediaItems(next)
            // Keep bounded history without losing the previous button.
            if (player.currentMediaItemIndex > 100) player.removeMediaItems(0, player.currentMediaItemIndex - 50)
        } finally { appending = false }
    }
    private fun media(t: Track): MediaItem? {
        val uri = if (t.downloaded) t.localUri else {
            if (musicStore.state.value.offline || t.part.isBlank() || config.url.isBlank()) return null
            if (!t.part.startsWith("/") || t.part.startsWith("//")) return null
            config.url.trimEnd('/') + t.part
        }
        return MediaItem.Builder().setMediaId(t.id).setUri(Uri.parse(uri))
            .setMediaMetadata(trackMetadata(t)).build()
    }
    private fun starRating(t: Track): StarRating = if (t.rating == 0 && t.exactPlexRating == null) StarRating(5)
        else StarRating(5, t.ratingText.toFloat().coerceIn(0f, 5f))
    private fun trackMetadata(t: Track) = MediaMetadata.Builder().setTitle(t.title).setArtist(t.artist).setAlbumTitle(t.album)
        .setIsBrowsable(false).setIsPlayable(true).setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC).setUserRating(starRating(t)).build()
    private fun carItem(entry: CarEntry): MediaItem = MediaItem.Builder().setMediaId(entry.id).setMediaMetadata(
        (entry.track?.let { trackMetadata(it).buildUpon() } ?: MediaMetadata.Builder())
            .setTitle(entry.title).setSubtitle(entry.subtitle).setIsBrowsable(!entry.playable).setIsPlayable(entry.playable)
            .setMediaType(if (entry.playable) MediaMetadata.MEDIA_TYPE_MUSIC else MediaMetadata.MEDIA_TYPE_FOLDER_MIXED).build()
    ).build()
    private fun resolveCarRequest(items: List<MediaItem>, startIndex: Int, position: Long): MediaSession.MediaItemsWithStartPosition {
        sequence = emptyList()
        val catalog = CarCatalog(musicStore.state.value.let { it.copy(tracks = it.tracks.filterNot { t -> t.id in removingIds }) })
        val requested = items.getOrNull(startIndex.coerceAtLeast(0)) ?: items.firstOrNull()
        val query = requested?.requestMetadata?.searchQuery
        val queue = if (query != null) CarQueue(catalog.search(query)) else catalog.queue(requested?.mediaId.orEmpty())
        val resolved: List<MediaItem>
        var index = queue.start
        if (queue.mode != null) {
            scopeIds = null; planner.reset(); radio = true
            saveCarChange { musicStore.update { it.copy(mode = queue.mode) } }
            val s = musicStore.state.value.copy(mode = queue.mode)
            val playable = queue.tracks.filter { it.downloaded || config.url.isNotBlank() }
            resolved = (planner.next(playable, s.mode, s.twoTrack) + planner.next(playable, s.mode, s.twoTrack)).mapNotNull { media(it) }
            if (resolved.isEmpty()) radio = false
            index = 0
        } else {
            radio = false; scopeIds = null
            sequence = queue.tracks
            sequencePosition = (index - 50).coerceAtLeast(0)
            // Only resolve IDs from our own cached library. Ignore controller-provided URIs.
            resolved = nextSequentialChunk()
            val selected = queue.tracks.getOrNull(index)?.id
            index = resolved.indexOfFirst { it.mediaId == selected }.coerceAtLeast(0)
        }
        status.value = status.value.copy(error = if (resolved.isEmpty()) "No playable music. Connect Plex or scan downloads on your phone." else "")
        return MediaSession.MediaItemsWithStartPosition(resolved, index, position.takeIf { it >= 0 } ?: 0)
    }
    private inner class CarCallback : MediaLibrarySession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                .add(SessionCommand(STOP, Bundle.EMPTY)).add(SessionCommand(RATE_ONE, Bundle.EMPTY)).add(SessionCommand(RATE_FIVE, Bundle.EMPTY))
                .add(SessionCommand(SHUFFLE, Bundle.EMPTY)).add(SessionCommand(NEXT_ARTIST, Bundle.EMPTY))
                .add(SessionCommand(RATE_CYCLE, Bundle.EMPTY)).add(SessionCommand(FLAG_DELETE, Bundle.EMPTY))
                .add(SessionCommand(DELETE_LOCAL, Bundle.EMPTY)).add(SessionCommand(MORE_ACTIONS, Bundle.EMPTY)).build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller).setAvailableSessionCommands(commands)
                .setAvailablePlayerCommands(MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS).build()
        }
        override fun onGetLibraryRoot(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?): ListenableFuture<LibraryResult<MediaItem>> {
            val extras = Bundle().apply { putBoolean("android.media.browse.SEARCH_SUPPORTED", true) }
            return Futures.immediateFuture(LibraryResult.ofItem(carItem(CarEntry(CarCatalog.ROOT, APP_NAME)), LibraryParams.Builder().setExtras(extras).build()))
        }
        override fun onGetItem(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String): ListenableFuture<LibraryResult<MediaItem>> {
            val item = CarCatalog(musicStore.state.value).item(mediaId)
            return Futures.immediateFuture(if (item != null) LibraryResult.ofItem(carItem(item), null) else LibraryResult.ofError(SessionError.ERROR_BAD_VALUE))
        }
        override fun onGetChildren(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String, page: Int, pageSize: Int, params: LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val catalog = CarCatalog(musicStore.state.value)
            if (parentId != CarCatalog.ROOT && catalog.tracks.isEmpty()) return Futures.immediateFuture(LibraryResult.ofError(
                SessionError(SessionError.ERROR_SESSION_SETUP_REQUIRED, "Open Offline Plex music on your phone to connect Plex or scan your music folder.")))
            val children = catalog.children(parentId).map { carItem(it) }
            return Futures.immediateFuture(LibraryResult.ofItemList(children, params))
        }
        override fun onSubscribe(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String, params: LibraryParams?): ListenableFuture<LibraryResult<Void>> {
            subscriptions.getOrPut(browser) { mutableSetOf() }.add(parentId)
            session.notifyChildrenChanged(browser, parentId, CarCatalog(musicStore.state.value).children(parentId).size, params)
            return Futures.immediateFuture(LibraryResult.ofVoid())
        }
        override fun onUnsubscribe(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String): ListenableFuture<LibraryResult<Void>> {
            subscriptions[browser]?.remove(parentId)
            return Futures.immediateFuture(LibraryResult.ofVoid())
        }
        override fun onDisconnected(session: MediaSession, controller: MediaSession.ControllerInfo) { subscriptions.remove(controller) }
        override fun onSearch(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, params: LibraryParams?): ListenableFuture<LibraryResult<Void>> {
            session.notifySearchResultChanged(browser, query, CarCatalog(musicStore.state.value).searchEntries(query).size, params)
            return Futures.immediateFuture(LibraryResult.ofVoid())
        }
        override fun onGetSearchResult(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, page: Int, pageSize: Int, params: LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
            Futures.immediateFuture(LibraryResult.ofItemList(CarCatalog(musicStore.state.value).searchEntries(query).map { carItem(it) }, params))
        override fun onSetMediaItems(session: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
            Futures.immediateFuture(resolveCarRequest(mediaItems, startIndex, startPositionMs))
        override fun onAddMediaItems(session: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: List<MediaItem>): ListenableFuture<List<MediaItem>> =
            Futures.immediateFuture(mediaItems.mapNotNull { item -> musicStore.state.value.tracks.find { it.id == item.mediaId }?.let { media(it) } })
        override fun onPlaybackResumption(session: MediaSession, controller: MediaSession.ControllerInfo, isForPlayback: Boolean): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
            Futures.immediateFuture(if (isForPlayback) resolveCarRequest(listOf(MediaItem.Builder().setMediaId("radio|${musicStore.state.value.mode.name}").build()), 0, 0)
            else MediaSession.MediaItemsWithStartPosition(CarCatalog(musicStore.state.value).tracks.take(1).mapNotNull { media(it) }, 0, 0))
        override fun onSetRating(session: MediaSession, controller: MediaSession.ControllerInfo, rating: Rating): ListenableFuture<SessionResult> =
            onSetRating(session, controller, player.currentMediaItem?.mediaId.orEmpty(), rating)
        override fun onSetRating(session: MediaSession, controller: MediaSession.ControllerInfo, mediaId: String, rating: Rating): ListenableFuture<SessionResult> {
            val track = CarCatalog(musicStore.state.value).item(mediaId)?.track
            val stars = (rating as? StarRating)?.takeIf { it.isRated && it.maxStars == 5 }?.starRating
            if (track == null || stars == null || stars % 1f != 0f || stars !in 1f..5f) return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
            return saveCarChange { musicStore.rate(setOf(track.id), stars.toInt()) }
        }
        override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo, customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
            if (customCommand.customAction == STOP) { stopPlayback(); return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS)) }
            if (customCommand.customAction == MORE_ACTIONS) {
                moreCarActions = !moreCarActions; updateCarButtons()
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            val track = musicStore.state.value.tracks.find { it.id == player.currentMediaItem?.mediaId }
                ?: return Futures.immediateFuture(SessionResult(SessionError.ERROR_INVALID_STATE))
            when (customCommand.customAction) {
                SHUFFLE -> shufflePlaylist()
                NEXT_ARTIST -> nextArtist()
                RATE_CYCLE -> return saveCarChange { musicStore.patch(track.id) { RatingRules.rate(it, (it.rating + 1) % 6) } }
                FLAG_DELETE -> {
                    if (track.remoteKey.isBlank()) return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
                    return saveCarChange { musicStore.patch(track.id) { it.copy(pendingDeletion = !it.pendingDeletion) } }
                }
                DELETE_LOCAL -> {
                    if (!track.downloaded || musicStore.state.value.downloads.any { it.id == track.id }) return Futures.immediateFuture(SessionResult(SessionError.ERROR_INVALID_STATE))
                    removeTracks(setOf(track.id))
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                LocalMusic.delete(this@PlaybackService, track)
                                musicStore.update { s ->
                                    val remaining = s.tracks.map { if (it.id == track.id) it.copy(localUri = "") else it }.filterNot { it.id == track.id && it.remoteKey.isBlank() }
                                    val ids = remaining.map { it.id }.toSet()
                                    s.copy(tracks = remaining, playlists = s.playlists.map { it.copy(tracks = it.tracks.filter { id -> id in ids }) })
                                }
                            }
                        } catch (e: Exception) { status.value = status.value.copy(error = "Local deletion failed: ${e.message}") }
                        finally { finishRemoval(setOf(track.id)) }
                    }
                }
                else -> {
            val stars = when (customCommand.customAction) { RATE_ONE -> 1f; RATE_FIVE -> 5f; else -> return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED)) }
            return onSetRating(session, controller, StarRating(5, stars))
                }
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }
    fun removeTracks(ids: Set<String>) {
        removingIds = removingIds + ids
        val wasRadio = radio
        val wasAppending = appending
        radio = false; appending = true
        try { for (i in player.mediaItemCount - 1 downTo 0) if (player.getMediaItemAt(i).mediaId in ids) player.removeMediaItem(i) }
        finally { radio = wasRadio; appending = wasAppending }
    }
    fun finishRemoval(ids: Set<String>) {
        removingIds = removingIds - ids
        if (player.mediaItemCount - player.currentMediaItemIndex <= 2) { if (radio) appendTask() else appendSequential() }
    }
    fun stopPlayback() {
        radio = false; planner.reset(); scopeIds = null; sequence = emptyList()
        player.pause(); player.stop(); player.clearMediaItems()
        status.value = Playing()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.playWhenReady || player.mediaItemCount == 0 || player.playerError != null) stopPlayback()
        super.onTaskRemoved(rootIntent)
    }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session
    override fun onDestroy() { instance = null; scope.cancel(); session?.release(); player.release(); status.value = Playing(); super.onDestroy() }
    companion object {
        const val STOP = "com.m3.pocketmusic.STOP"
        const val RATE_ONE = "com.m3.pocketmusic.RATE_ONE"
        const val RATE_FIVE = "com.m3.pocketmusic.RATE_FIVE"
        const val RATE_CYCLE = "com.m3.pocketmusic.RATE_CYCLE"
        const val SHUFFLE = "com.m3.pocketmusic.SHUFFLE"
        const val NEXT_ARTIST = "com.m3.pocketmusic.NEXT_ARTIST"
        const val FLAG_DELETE = "com.m3.pocketmusic.FLAG_DELETE"
        const val DELETE_LOCAL = "com.m3.pocketmusic.DELETE_LOCAL"
        const val MORE_ACTIONS = "com.m3.pocketmusic.MORE_ACTIONS"
        var instance: PlaybackService? = null
            private set
        val status = MutableStateFlow(Playing())
    }
}
