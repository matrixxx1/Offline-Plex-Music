package com.m3.pocketmusic

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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
            try { message.value = block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { message.value = e.message ?: "Operation failed. Your queued ratings are saved." }
            finally { busy.value = false; progress.value = "" }
        }
    }
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
            check(old.token.isBlank() || old.token == candidate.token || state.value.tracks.none { it.pendingRating != null }) {
                "Sync or discard queued ratings before changing the Plex account token."
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
                val merged = loaded.map { t -> old[t.id]?.let { t.copy(localUri = it.localUri, pendingRating = it.pendingRating, localRating = it.localRating) } ?: t }
                // Retain missing remote tracks with downloads or unsynced ratings for explicit resolution.
                s.copy(tracks = merged + s.tracks.filter { it.id !in ids && (it.remoteKey.isBlank() || it.downloaded || it.pendingRating != null) })
            }
            progress.value = "Loading Plex playlists"
            val playlistResult = runCatching { api.playlists() }
            playlistResult.getOrNull()?.let { lists -> store.update { it.copy(playlists = it.playlists.filterNot { p -> p.plex } + lists) } }
            "Imported ${loaded.size} tracks. Open Library and tap a track to stream, or use its download button for offline listening." + if (playlistResult.isFailure) " Playlist import failed; existing playlists were kept." else " Plex playlists updated."
    }
    fun syncRatings() = task {
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
            "$done ratings synced. ${failures.size} remain queued." + failures.take(3).joinToString("\n", prefix = if (failures.isEmpty()) "" else "\n")
        }
    }
    fun scan() = task {
        check(state.value.downloads.none { it.state != "Failed" }) { "Finish or cancel downloads before scanning the folder." }
        val count = LocalMusic.scan(getApplication()) { progress.value = it }; "Found $count local tracks."
    }
    fun setFolder(uri: String) { store.update { it.copy(folder = uri) }; scan() }
    fun rate(ids: Set<String>, stars: Int) { store.rate(ids, stars); message.value = "Rated ${ids.size} tracks. Plex changes wait for Sync." }
    fun discardRatings() { store.update { it.copy(tracks = it.tracks.map { t -> t.copy(pendingRating = null) }) } }
    fun settings(offline: Boolean = state.value.offline, mode: PlayMode = state.value.mode, two: Boolean = state.value.twoTrack) {
        store.update { it.copy(offline = offline, mode = mode, twoTrack = two) }
        if (offline) {
            cancelSignIn()
            WorkManager.getInstance(getApplication()).cancelUniqueWork("music-downloads")
        }
        PlaybackService.instance?.settingsChanged()
    }
    fun downloads(ids: Set<String>) {
        if (busy.value) return
        if (state.value.offline) { message.value = "Turn off Offline only to download."; return }
        if (state.value.folder.isBlank()) { message.value = "Choose your music folder in Settings first."; return }
        val tracks = state.value.tracks.filter { it.id in ids && !it.downloaded && it.part.isNotBlank() }
        store.update { s -> s.copy(downloads = (s.downloads + tracks.map { DownloadJob(it.id) }).distinctBy { it.id }.map {
            if (it.id in ids && it.state == "Failed") it.copy(state = "Queued", error = "") else it
        }) }
        enqueueDownloads()
        message.value = "${tracks.size} tracks queued for download."
    }
    fun retryDownloads() {
        if (busy.value) return
        if (state.value.offline) { message.value = "Turn off Offline only to resume downloads."; return }
        store.update { s -> s.copy(downloads = s.downloads.map { it.copy(state = "Queued", error = "") }) }
        enqueueDownloads()
    }
    fun pauseDownloads() { WorkManager.getInstance(getApplication()).cancelUniqueWork("music-downloads"); message.value = "Downloads paused. Tap Resume to continue." }
    fun cancelDownloads() {
        WorkManager.getInstance(getApplication()).cancelUniqueWork("music-downloads")
        store.update { it.copy(downloads = emptyList()) }
        message.value = "Download queue canceled. Completed files are kept."
    }
    private fun enqueueDownloads() {
        val work = OneTimeWorkRequestBuilder<DownloadWorker>().setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        WorkManager.getInstance(getApplication()).enqueueUniqueWork("music-downloads", ExistingWorkPolicy.APPEND_OR_REPLACE, work)
    }
    fun playlist(name: String, ids: List<String>, target: String? = null) {
        require(name.isNotBlank())
        store.update { s ->
            val old = s.playlists.find { it.id == target && !it.plex }
            if (old == null) s.copy(playlists = s.playlists + Playlist(UUID.randomUUID().toString(), name.trim(), ids.distinct()))
            else s.copy(playlists = s.playlists.map { if (it.id == old.id) it.copy(tracks = (it.tracks + ids).distinct()) else it })
        }
    }
    fun removeFromPlaylist(playlistId: String, ids: Set<String>) = store.update { s -> s.copy(playlists = s.playlists.map {
        if (it.id == playlistId && !it.plex) it.copy(tracks = it.tracks.filterNot { id -> id in ids }) else it
    }) }
    fun movePlaylistTrack(playlistId: String, trackId: String, delta: Int) = store.update { s -> s.copy(playlists = s.playlists.map { p ->
        if (p.id != playlistId || p.plex) p else {
            val items = p.tracks.toMutableList(); val from = items.indexOf(trackId); val to = (from + delta).coerceIn(0, (items.size - 1).coerceAtLeast(0))
            if (from >= 0) { items.removeAt(from); items.add(to, trackId) }; p.copy(tracks = items)
        }
    }) }
    fun deletePlaylist(id: String) = store.update { it.copy(playlists = it.playlists.filterNot { p -> p.id == id && !p.plex }) }
    fun removeDownloads(ids: Set<String>) = delete(state.value.tracks.filter { it.id in ids && it.downloaded }.map { it.id }.toSet(), local = true, server = false)
    fun delete(ids: Set<String>, local: Boolean, server: Boolean) = task {
        check(local || server)
        check(state.value.downloads.none { it.id in ids }) { "Finish downloads before deleting these tracks." }
        if (server) check(!state.value.offline) { "Turn off Offline only for Plex deletion." }
        PlaybackService.instance?.removeTracks(ids)
        try { withContext(Dispatchers.IO) {
            val api = if (server) PlexApi(config).also { it.verifyServer() } else null
            val tracks = state.value.tracks.filter { it.id in ids }
            var done = 0; val failures = mutableListOf<String>()
            tracks.forEachIndexed { index, t ->
                progress.value = "Deleting ${index + 1}/${tracks.size}: ${t.title}"
                try {
                    if (local && t.downloaded) {
                        LocalMusic.delete(getApplication(), t)
                        store.patch(t.id) { it.copy(localUri = "") }
                    }
                    if (server && t.remoteKey.isNotBlank()) {
                        api!!.deleteTrack(t)
                        store.patch(t.id) { it.copy(remoteKey = "", part = "", localRating = it.rating, pendingRating = null) }
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
