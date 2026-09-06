@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.m3.pocketmusic

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

data class Playing(val trackId: String? = null, val playing: Boolean = false, val error: String = "", val radio: Boolean = false)
class PlaybackService : MediaSessionService() {
    private lateinit var player: ExoPlayer
    private var session: MediaSession? = null
    private val planner = RadioPlanner()
    private var radio = false
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
        session = MediaSession.Builder(this, player).setSessionActivity(PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build()
        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                status.value = status.value.copy(trackId = player.currentMediaItem?.mediaId, playing = player.isPlaying, radio = radio)
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (radio && player.currentMediaItemIndex >= player.mediaItemCount - 2) appendTask()
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED && radio) { appendTask(); player.prepare(); player.play() }
            }
            override fun onPlayerError(error: PlaybackException) {
                status.value = status.value.copy(error = "Playback failed (${error.errorCodeName}). Check file access or Plex connection, then retry or skip.")
            }
        })
        scope.launch {
            var previousOffline = musicStore.state.value.offline
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
            }
        }
    }
    fun reloadConnection() {
        radio = false; player.stop(); player.clearMediaItems()
        config = musicStore.credentials.read()
        http.setDefaultRequestProperties(mapOf("X-Plex-Token" to config.token))
    }
    fun startRadio(ids: Set<String>? = null) {
        scopeIds = ids; planner.reset(); radio = true
        player.clearMediaItems(); appendTask(); appendTask()
        status.value = status.value.copy(error = "")
        player.prepare(); player.play()
    }
    fun playTracks(tracks: List<Track>, start: Int = 0) {
        radio = false; scopeIds = null
        val playable = tracks.mapNotNull { media(it) }
        if (playable.isEmpty()) return
        val selectedId = tracks.getOrNull(start)?.id
        player.setMediaItems(playable, playable.indexOfFirst { it.mediaId == selectedId }.coerceAtLeast(0), 0)
        status.value = status.value.copy(error = "")
        player.prepare(); player.play()
    }
    fun settingsChanged() {
        if (radio) {
            if (player.currentMediaItemIndex + 1 < player.mediaItemCount) player.removeMediaItems(player.currentMediaItemIndex + 1, player.mediaItemCount)
            planner.reset(); appendTask()
        }
    }
    private fun appendTask() {
        val s = musicStore.state.value
        val pool = s.tracks.filter { it.id !in removingIds && (scopeIds == null || it.id in scopeIds!!) && (!s.offline || it.downloaded) && (it.downloaded || it.part.isNotBlank()) }
        val next = planner.next(pool, s.mode, s.twoTrack).mapNotNull { media(it) }
        if (next.isEmpty()) { radio = false; return }
        player.addMediaItems(next)
        // Keep bounded history without losing the previous button.
        if (player.currentMediaItemIndex > 100) player.removeMediaItems(0, player.currentMediaItemIndex - 50)
    }
    private fun media(t: Track): MediaItem? {
        val uri = if (t.downloaded) t.localUri else {
            if (musicStore.state.value.offline || t.part.isBlank() || config.url.isBlank()) return null
            if (!t.part.startsWith("/") || t.part.startsWith("//")) return null
            config.url.trimEnd('/') + t.part
        }
        return MediaItem.Builder().setMediaId(t.id).setUri(Uri.parse(uri))
            .setMediaMetadata(MediaMetadata.Builder().setTitle(t.title).setArtist(t.artist).setAlbumTitle(t.album).build()).build()
    }
    fun removeTracks(ids: Set<String>) {
        removingIds = removingIds + ids
        val wasRadio = radio
        radio = false
        try { for (i in player.mediaItemCount - 1 downTo 0) if (player.getMediaItemAt(i).mediaId in ids) player.removeMediaItem(i) }
        finally { radio = wasRadio }
    }
    fun finishRemoval(ids: Set<String>) {
        removingIds = removingIds - ids
        if (radio && player.mediaItemCount - player.currentMediaItemIndex <= 2) appendTask()
    }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session
    override fun onDestroy() { instance = null; scope.cancel(); session?.release(); player.release(); status.value = Playing(); super.onDestroy() }
    companion object {
        var instance: PlaybackService? = null
            private set
        val status = MutableStateFlow(Playing())
    }
}
