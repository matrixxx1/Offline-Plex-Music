package com.m3.pocketmusic

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.BackoffPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class MusicViewModel(app: Application) : AndroidViewModel(app) {
    val store = app.musicStore
    val state = store.state
    val message = MutableStateFlow("")
    val busy = MutableStateFlow(false)
    val progress = MutableStateFlow("")
    val config get() = store.credentials.read()
    val connection = MutableStateFlow(config)
    val servers = MutableStateFlow<List<PlexServer>>(emptyList())
    val signingIn = MutableStateFlow(false)
    val login = MutableStateFlow(store.credentials.readLogin())
    val loginUrl = MutableStateFlow(login.value.pin?.let { PlexAccountApi(store.credentials.clientId).authUrl(it) }.orEmpty())
    private var operation: Job? = null
    private fun task(block: suspend () -> String) {
        if (busy.value) return
        busy.value = true; message.value = ""
        operation = viewModelScope.launch {
            try { editLock.withLock { }; message.value = block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { message.value = e.message ?: "Operation failed. Your queued ratings are saved." }
            finally { busy.value = false; progress.value = "" }
        }
    }
    private val editLock = Mutex()
    // Preserve tap order without ever waiting for the download writer on the UI thread.
    private fun edit(block: suspend () -> Unit) {
        viewModelScope.launch {
            editLock.withLock {
                try { block() }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { message.value = "Could not save changes: ${e.message}" }
            }
        }
    }
    private suspend fun save(change: (LibraryState) -> LibraryState) = withContext(Dispatchers.IO) { store.update(change) }
    fun signIn(openBrowser: (String) -> Unit) = task {
        check(!state.value.offline) { "Turn off Offline only before signing in." }
        signingIn.value = true; servers.value = emptyList()
        try {
            val api = PlexAccountApi(store.credentials.clientId)
            val flow = PlexLoginFlow(api, { login.value }, { saved ->
                store.credentials.saveLogin(saved)
                login.value = saved
                loginUrl.value = saved.pin?.let { api.authUrl(it) }.orEmpty()
            })
            servers.value = flow.resume(openBrowser) { progress.value = it }
            if (servers.value.isEmpty()) "Signed in, but no accessible servers were found. Check that your server is claimed by this Plex account and online."
            else "Signed in. Choose your server below to connect and import music."
        } finally { signingIn.value = false }
    }
    fun cancelSignIn() { if (signingIn.value) { operation?.cancel(); message.value = "Connection paused. Your sign-in is saved; tap Retry connection to continue." } }
    fun resetSignIn() {
        if (busy.value) return
        store.credentials.saveLogin(PlexLogin()); login.value = PlexLogin(); loginUrl.value = ""; servers.value = emptyList()
        message.value = "Ready for a new Plex sign-in. Your saved server, music, and ratings are unchanged."
    }
    fun connectServer(server: PlexServer) = task {
        check(!state.value.offline) { "Turn off Offline only before connecting." }
        val candidate = withContext(Dispatchers.IO) {
            server.connections.firstOrNull { url ->
                progress.value = "Checking ${server.name}: ${java.net.URI(url).host}"
                runCatching { PlexApi(PlexConfig(url, server.token)).identity() == server.id }.getOrDefault(false)
            }?.let { PlexConfig(it, server.token, server.id) }
        }
        check(candidate != null) { "Could not reach ${server.name}. Check your Wi-Fi or Plex Remote Access, or enter a server address under Advanced connection." }
        connectAndImport(candidate)
    }
    fun connect(url: String, token: String) = task { connectAndImport(PlexConfig(url.trim().trimEnd('/'), token.trim())) }
    private suspend fun connectAndImport(candidate: PlexConfig): String {
        withContext(Dispatchers.IO) {
            check(!state.value.offline) { "Turn off Offline only before connecting." }
            progress.value = "Connecting to Plex"
            val id = PlexApi(candidate).identity()
            val old = config
            check(old.serverId.isBlank() || old.serverId == id) { "This library belongs to another server. Use the original server to preserve queued ratings and downloads." }
            check(old.token.isBlank() || old.token == candidate.token || (state.value.tracks.none { it.pendingRating != null || it.pendingDeletion } && state.value.playlists.none { it.pendingSync })) {
                "Sync or discard queued changes before changing the Plex account token."
            }
            store.credentials.save(candidate.copy(serverId = id))
            connection.value = config
        }
        PlaybackService.instance?.reloadConnection()
        servers.value = emptyList()
        return loadLibrary()
    }
    fun refresh() = task { loadLibrary() }
    private suspend fun loadLibrary(): String = withContext(Dispatchers.IO) {
            check(!state.value.offline) { "Turn off Offline only to refresh Plex." }
            val api = PlexApi(config)
            val loaded = api.library { progress.value = it }
            store.update { s ->
                val old = s.tracks.associateBy { it.id }
                val ids = loaded.map { it.id }.toSet()
                val merged = loaded.map { t -> old[t.id]?.let { t.copy(localUri = it.localUri, pendingRating = it.pendingRating, localRating = it.localRating, pendingDeletion = it.pendingDeletion) } ?: t }
                // Retain missing remote tracks with downloads or unsynced ratings for explicit resolution.
                s.copy(tracks = merged + s.tracks.filter { it.id !in ids && (it.remoteKey.isBlank() || it.downloaded || it.pendingRating != null || it.pendingDeletion) })
            }
            progress.value = "Loading Plex playlists"
            val playlistResult = runCatching { api.playlists() }
            playlistResult.getOrNull()?.let { lists -> store.update { it.copy(playlists = PlaylistEditing.merge(it.playlists, lists)) } }
            "Imported ${loaded.size} tracks. Open Library and tap a track to stream, or download Plex playlists for offline listening." + if (playlistResult.isFailure) " Playlist import failed; existing playlists were kept." else " Plex playlists updated."
    }
    fun refreshPlaylists() = task {
        withContext(Dispatchers.IO) {
            check(!state.value.offline) { "Turn off Offline only to refresh playlists." }
            val api = PlexApi(config); api.verifyServer()
            progress.value = "Loading Plex playlists"
            val (lists, tracks) = api.playlistsWithTracks()
            store.update { s ->
                val existing = s.tracks.map { it.id }.toSet()
                s.copy(playlists = PlaylistEditing.merge(s.playlists, lists),
                    tracks = s.tracks + tracks.filter { it.id !in existing })
            }
            "${lists.size} Plex playlists refreshed. Select playlists to download."
        }
    }
    fun syncRatings(deleteIds: Set<String> = emptySet()) = task {
        withContext(Dispatchers.IO) {
            check(!state.value.offline) { "Turn off Offline only before syncing." }
            val api = PlexApi(config); api.verifyServer()
            val queued = state.value.tracks.filter { it.pendingRating != null && it.remoteKey.isNotBlank() }
            val failures = mutableListOf<String>(); var done = 0
            queued.forEachIndexed { index, track ->
                progress.value = "Syncing ${index + 1}/${queued.size}: ${track.title}"
                try {
                    val sent = track.pendingRating!!; api.rate(track, sent)
                    store.patch(track.id) { RatingRules.acknowledged(it, sent) }; done++
                } catch (e: Exception) { failures += "${track.title}: ${e.message}" }
            }
            var deleted = 0
            val deletions = state.value.tracks.filter { it.pendingDeletion && it.id in deleteIds }
            deletions.forEachIndexed { index, track ->
                if (state.value.tracks.none { it.id == track.id && it.pendingDeletion }) return@forEachIndexed
                progress.value = "Deleting from Plex ${index + 1}/${deletions.size}: ${track.title}"
                try {
                    check(state.value.downloads.none { it.id == track.id }) { "Finish or cancel this download first" }
                    withContext(Dispatchers.Main) { PlaybackService.instance?.removeTracks(setOf(track.id)) }
                    api.deleteTrack(track)
                    store.update { s ->
                        val remaining = s.tracks.map { if (it.id == track.id) it.copy(remoteKey = "", part = "", pendingDeletion = false, localRating = it.rating, pendingRating = null) else it }
                            .filterNot { it.id == track.id && !it.downloaded }
                        val valid = remaining.map { it.id }.toSet()
                        s.copy(tracks = remaining, playlists = s.playlists.map { it.copy(tracks = it.tracks.filter { id -> id in valid }) })
                    }
                    deleted++
                } catch (e: Exception) { failures += "${track.title}: ${e.message}" }
                finally { withContext(Dispatchers.Main) { PlaybackService.instance?.finishRemoval(setOf(track.id)) } }
            }
            "$done ratings synced; $deleted Plex tracks deleted. ${failures.size} failed changes remain queued." + failures.take(3).joinToString("\n", prefix = if (failures.isEmpty()) "" else "\n")
        }
    }
    fun scan() = task {
        check(state.value.downloads.none { it.state != "Failed" }) { "Finish or cancel downloads before scanning the folder." }
        val count = LocalMusic.scan(getApplication()) { progress.value = it }; "Found $count local tracks."
    }
    fun setFolder(uri: String) = edit { save { it.copy(folder = uri) }; scan() }
    fun rate(ids: Set<String>, stars: Int) = edit { withContext(Dispatchers.IO) { store.rate(ids, stars) }; message.value = "Rated ${ids.size} tracks. Plex changes wait for Sync." }
    fun discardRatings() = edit { save { it.copy(tracks = it.tracks.map { t -> t.copy(pendingRating = null) }) } }
    fun settings(offline: Boolean? = null, mode: PlayMode? = null, two: Boolean? = null) = edit {
        save { it.copy(offline = offline ?: it.offline, mode = mode ?: it.mode, twoTrack = two ?: it.twoTrack) }
        if (offline == true) {
            cancelSignIn()
            WorkManager.getInstance(getApplication()).cancelUniqueWork("music-downloads")
        }
        PlaybackService.instance?.settingsChanged()
    }
    fun downloads(ids: Set<String>) = task {
        withContext(Dispatchers.IO) {
            check(!state.value.offline) { "Turn off Offline only to download." }
            check(state.value.folder.isNotBlank()) { "Choose your music folder in Settings first." }
            progress.value = "Saving download queue"
            val tracks = state.value.tracks.filter { it.id in ids && !it.downloaded && it.part.isNotBlank() }
            store.update { s -> s.copy(downloadsPaused = false, downloads = (s.downloads + tracks.map { DownloadJob(it.id) }).distinctBy { it.id }.map {
                if (it.id in ids && it.state == "Failed") it.copy(state = "Queued", error = "") else it
            }) }
            enqueueDownloads()
            "${tracks.size} tracks queued for download." + if (state.value.wifiOnlyDownloads) " Downloads start automatically on Wi-Fi." else " Wi-Fi or mobile data may be used."
        }
    }
    fun retryDownloads() = edit {
        if (busy.value) return@edit
        if (state.value.offline) { message.value = "Turn off Offline only to resume downloads."; return@edit }
        save { s -> s.copy(downloadsPaused = false, downloads = s.downloads.map { it.copy(state = "Queued", error = "") }) }
        enqueueDownloads()
    }
    fun pauseDownloads() = edit {
        save { it.copy(downloadsPaused = true) }
        WorkManager.getInstance(getApplication()).cancelUniqueWork("music-downloads"); message.value = "Downloads paused. Tap Resume to continue."
    }
    fun setDownloadWifiOnly(enabled: Boolean) = edit {
        if (enabled == state.value.wifiOnlyDownloads) return@edit
        save { it.copy(wifiOnlyDownloads = enabled) }
        WorkManager.getInstance(getApplication()).cancelUniqueWork("music-downloads")
        if (!state.value.offline && !state.value.downloadsPaused && state.value.downloads.isNotEmpty()) enqueueDownloads()
        message.value = if (enabled) "Downloads will wait for Wi-Fi." else "Downloads may use Wi-Fi or mobile data."
    }
    fun cancelDownloads() = edit {
        WorkManager.getInstance(getApplication()).cancelUniqueWork("music-downloads")
        save { it.copy(downloads = emptyList(), downloadsPaused = false) }
        message.value = "Download queue canceled. Completed files are kept."
    }
    private fun enqueueDownloads() {
        val work = OneTimeWorkRequestBuilder<DownloadWorker>().setConstraints(DownloadPolicy.constraints(state.value.wifiOnlyDownloads))
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, java.util.concurrent.TimeUnit.SECONDS).build()
        WorkManager.getInstance(getApplication()).enqueueUniqueWork("music-downloads", ExistingWorkPolicy.APPEND_OR_REPLACE, work)
    }
    fun playlist(name: String, ids: List<String>, target: String? = null) = edit {
        require(name.isNotBlank())
        save { s ->
            val old = s.playlists.find { it.id == target && !it.plex }
            if (old == null) s.copy(playlists = s.playlists + Playlist(UUID.randomUUID().toString(), name.trim(), ids.distinct()))
            else s.copy(playlists = s.playlists.map { if (it.id == old.id) it.copy(tracks = (it.tracks + ids).distinct()) else it })
        }
    }
    fun removeFromPlaylist(playlistId: String, ids: Set<String>) = edit { save { s -> s.copy(playlists = s.playlists.map {
        if (it.id == playlistId && !it.plex) it.copy(tracks = it.tracks.filterNot { id -> id in ids }) else it
    }) } }

    fun movePlaylistTrack(playlistId: String, trackId: String, delta: Int) = edit { save { s -> s.copy(playlists = s.playlists.map { p ->
        if (p.id != playlistId || p.plex) p else {
            val items = p.tracks.toMutableList(); val from = items.indexOf(trackId); val to = (from + delta).coerceIn(0, (items.size - 1).coerceAtLeast(0))
            if (from >= 0) { items.removeAt(from); items.add(to, trackId) }; p.copy(tracks = items)
        }
    }) } }

    fun deletePlaylist(id: String) = edit { save { it.copy(playlists = it.playlists.filterNot { p -> p.id == id && !p.plex }) } }

    fun editPlaylist(id: String, change: (Playlist) -> Playlist) {
        if (busy.value) return
        edit { save { s -> s.copy(playlists = s.playlists.map { if (it.id == id) change(it) else it }) } }
    }
    fun addPlaylistTracks(id: String, ids: List<String>) {
        val valid = state.value.tracks.associateBy { it.id }
        editPlaylist(id) { p -> PlaylistEditing.edit(p, tracks = p.tracks + ids.filter { key ->
            valid[key]?.let { !p.plex || (it.remoteKey.isNotBlank() && it.id == "plex:${config.serverId}:${it.remoteKey}") } == true
        }) }
    }
    fun copyPlaylist(id: String) {
        if (busy.value) return
        edit {
            state.value.playlists.find { it.id == id }?.let { p ->
                save { it.copy(playlists = it.playlists + Playlist(UUID.randomUUID().toString(), "${p.name} (copy)", p.tracks)) }
                message.value = "Saved a local playlist copy."
            }
        }
    }
    fun reloadPlaylist(id: String) = task {
        withContext(Dispatchers.IO) {
            check(!state.value.offline) { "Turn off Offline before reloading a Plex playlist." }
            val api = PlexApi(config); api.verifyServer()
            val fresh = api.playlistSnapshot(id)
            store.update { s ->
                val existing = s.tracks.map { it.id }.toSet()
                s.copy(playlists = s.playlists.map { if (it.id == id) fresh.playlist else it }, tracks = s.tracks + fresh.tracks.filter { it.id !in existing }.distinctBy { it.id })
            }
            "Playlist reloaded from Plex."
        }
    }
    fun syncPlaylist(id: String) = task {
        withContext(Dispatchers.IO) {
            check(!state.value.offline) { "Turn off Offline before syncing a playlist." }
            val draft = state.value.playlists.first { it.id == id }
            try {
                val verified = PlexApi(config).syncPlaylist(draft, state.value.tracks, { checkpoint ->
                    store.update { s -> s.copy(playlists = s.playlists.map { if (it.id == id) PlaylistEditing.checkpoint(it, checkpoint, false) else it }) }
                }, { progress.value = it })
                store.update { s -> s.copy(playlists = s.playlists.map { if (it.id == id) PlaylistEditing.checkpoint(it, verified, true) else it }) }
                "Playlist synced to Plex and verified. Ratings and music deletion flags remain separate."
            } catch (e: Exception) {
                store.update { s -> s.copy(playlists = s.playlists.map { if (it.id == id) it.copy(pendingSync = true, syncError = e.message ?: "Sync failed; edits are saved.") else it }) }
                throw e
            }
        }
    }
    fun flagDeletion(ids: Set<String>, flagged: Boolean) = edit {
        save { s -> s.copy(tracks = s.tracks.map { if (it.id in ids && it.remoteKey.isNotBlank()) it.copy(pendingDeletion = flagged) else it }) }
        message.value = if (flagged) "Flagged for Plex deletion at next manual sync. Local downloads are kept." else "Plex deletion flag removed."
    }
    fun removeDownloads(ids: Set<String>) = delete(state.value.tracks.filter { it.id in ids && it.downloaded }.map { it.id }.toSet(), local = true, server = false)
    fun delete(ids: Set<String>, local: Boolean, server: Boolean) = task {
        check(local || server)
        check(state.value.downloads.none { it.id in ids }) { "Finish downloads before deleting these tracks." }
        if (server) withContext(Dispatchers.IO) { store.update { s -> s.copy(tracks = s.tracks.map { if (it.id in ids && it.remoteKey.isNotBlank()) it.copy(pendingDeletion = true) else it }) } }
        PlaybackService.instance?.removeTracks(ids)
        try { withContext(Dispatchers.IO) {
            val tracks = state.value.tracks.filter { it.id in ids }
            var done = 0; val failures = mutableListOf<String>()
            tracks.forEachIndexed { index, t ->
                progress.value = "Deleting ${index + 1}/${tracks.size}: ${t.title}"
                try {
                    if (local && t.downloaded) {
                        LocalMusic.delete(getApplication(), t)
                        store.patch(t.id) { it.copy(localUri = "") }
                    }
                    store.update { s ->
                        val remaining = s.tracks.filterNot { it.id == t.id && it.remoteKey.isBlank() && !it.downloaded }
                        val valid = remaining.map { it.id }.toSet()
                        s.copy(tracks = remaining, playlists = s.playlists.map { it.copy(tracks = it.tracks.filter { id -> id in valid }) })
                    }
                    done++
                } catch (e: Exception) { failures += "${t.title}: ${e.message}" }
            }
            "Processed $done tracks; ${failures.size} failed." + failures.take(4).joinToString("\n", prefix = if (failures.isEmpty()) "" else "\n")
        } } finally { PlaybackService.instance?.finishRemoval(ids) }
    }
}
