package com.m3.pocketmusic

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFFB7F36B), onPrimary = Color(0xFF173000),
                secondary = Color(0xFFC1CEC1), background = Color(0xFF0C1210), surface = Color(0xFF141D18),
                surfaceVariant = Color(0xFF253228), onSurface = Color(0xFFE6EDE5),
                primaryContainer = Color(0xFF35482A), onPrimaryContainer = Color(0xFFD0F9AC),
                secondaryContainer = Color(0xFF354337), onSecondaryContainer = Color(0xFFDBEAD6))) {
                PocketMusic()
            }
        }
    }
}

@Composable private fun PocketMusic(vm: MusicViewModel = viewModel()) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val playing by PlaybackService.status.collectAsStateWithLifecycle()
    val connection by vm.connection.collectAsStateWithLifecycle()
    var tab by remember { mutableStateOf("Library") }
    var smartDownload by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    var group by remember { mutableStateOf("Tracks") }
    var groupValue by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var playlistId by remember { mutableStateOf<String?>(null) }
    var ratingIds by remember { mutableStateOf<Set<String>?>(null) }
    var deleteIds by remember { mutableStateOf<Set<String>?>(null) }
    var removeDownloadIds by remember { mutableStateOf<Set<String>?>(null) }
    var playlistIds by remember { mutableStateOf<List<String>?>(null) }
    var showSync by remember { mutableStateOf(false) }
    var showDiscard by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    var controller by remember { mutableStateOf<MediaController?>(null) }
    // Bind lazily, so editing connection settings does not create a stale playback service.
    var connectPlayback by remember { mutableStateOf(PlaybackService.instance != null) }
    var pendingPlay by remember { mutableStateOf<(() -> Unit)?>(null) }
    DisposableEffect(connectPlayback) {
        if (!connectPlayback) onDispose { }
        else {
            val future = MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync()
            future.addListener({
                runCatching { future.get() }.onSuccess { controller = it; pendingPlay?.invoke(); pendingPlay = null }
                    .onFailure { vm.message.value = "Could not start playback: ${it.message}" }
            }, ContextCompat.getMainExecutor(context))
            onDispose { MediaController.releaseFuture(future) }
        }
    }
    fun play(action: () -> Unit) { if (controller != null) action() else { pendingPlay = action; connectPlayback = true } }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun download(ids: Set<String>) {
        if (Build.VERSION.SDK_INT >= 33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        vm.downloads(ids)
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                vm.setFolder(uri.toString())
            }.onFailure { vm.message.value = "Folder access failed: ${it.message}" }
        }
    }
    val playlist = state.playlists.find { it.id == playlistId }
    val source = if (playlist == null) state.tracks else playlist.tracks.mapNotNull { id -> state.tracks.find { it.id == id } }
    val base = source.filter { (!state.offline || it.downloaded) && (search.isBlank() || "${it.title} ${it.artist} ${it.album} ${it.genres.joinToString()}".contains(search, true)) }
    fun keys(t: Track): List<String> = when (group) { "Artists" -> listOf(t.artist); "Albums" -> listOf("${t.artist} • ${t.album}"); "Genres" -> t.genres.ifEmpty { listOf("Unspecified") }; else -> emptyList() }
    val visible = base.filter { groupValue == null || keys(it).any { key -> key.equals(groupValue, ignoreCase = true) } }
    val activeSelection = selected.intersect(state.tracks.map { it.id }.toSet())
    val queued = state.tracks.filter { it.pendingRating != null }
    val now = state.tracks.find { it.id == playing.trackId }

    Scaffold(bottomBar = {
        if (now != null) NowPlaying(now, playing, controller, { ratingIds = setOf(now.id) }, { showQueue = true })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 18.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(APP_NAME, color = MaterialTheme.colorScheme.primary, fontSize = 11.sp, letterSpacing = 0.5.sp, fontWeight = FontWeight.Bold)
                    Text(if (tab == "Library") playlist?.name ?: "Your listening room" else tab, fontSize = 25.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                AssistChip(onClick = { vm.settings(offline = !state.offline) }, label = { Text(if (state.offline) "Offline" else "Online") })
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Library", "Plex", "Playlists", "Downloads", "Settings").forEach { name -> FilterChip(tab == name, { tab = name }, label = { Text(name) }) }
            }
            if (busy || state.downloads.any { it.state == "Downloading" }) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(if (busy) progress.ifBlank { "Working…" } else "Downloading: ${state.tracks.find { it.id == state.downloads.firstOrNull { it.state == "Downloading" }?.id }?.title.orEmpty()}",
                    fontSize = 12.sp, maxLines = 2, modifier = Modifier.padding(vertical = 6.dp))
            }
            if (message.isNotBlank()) {
                Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(message, fontSize = 12.sp, modifier = Modifier.weight(1f), maxLines = 6)
                    IconButton(onClick = { vm.message.value = "" }) { Icon(Icons.Default.Close, "Dismiss message") }
                }
            }
            when (tab) {
                "Plex" -> PlexScreen(vm, state, busy, { tab = "Library" }, { folderPicker.launch(null) }, { tab = "Downloads"; smartDownload = true })
                "Library" -> {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { tab = "Plex" }) { Text(if (connection.serverId.isBlank()) "Connect Plex" else "Plex connection") }
                        if (connection.serverId.isNotBlank()) TextButton(onClick = { vm.refresh() }, enabled = !busy && !state.offline) { Text("Import music") }
                    }
                    if (playlist != null) TextButton(onClick = { playlistId = null; selected = emptySet() }) { Text("← All music") }
                    OutlinedTextField(search, { search = it; selected = emptySet() }, placeholder = { Text("Search tracks, artists, albums…") },
                        leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("Tracks", "Artists", "Albums", "Genres").forEach { name ->
                            FilterChip(group == name, { group = name; groupValue = null; selected = emptySet() }, label = { Text(name) })
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        DropChoice(state.mode.label, PlayMode.entries.map { it.label }) { label -> vm.settings(mode = PlayMode.entries.first { it.label == label }) }
                        Checkbox(state.twoTrack, { vm.settings(two = it) })
                        Text("2 Track limit", fontSize = 12.sp)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = { play { PlaybackService.instance?.startRadio(visible.map { it.id }.toSet()) } }, enabled = visible.isNotEmpty()) {
                            Icon(Icons.Default.PlayArrow, null); Text("Start radio")
                        }
                        TextButton(onClick = { showSync = true }, enabled = queued.isNotEmpty() && !busy) { Text("Sync ratings (${queued.size})") }
                    }
                    if (groupValue != null) TextButton(onClick = { groupValue = null; selected = emptySet() }) { Text("← $group / $groupValue") }
                    if (activeSelection.isNotEmpty()) {
                        SelectionBar(activeSelection.size, { ratingIds = activeSelection }, { download(activeSelection) },
                            { deleteIds = activeSelection }, { playlistIds = visible.filter { it.id in activeSelection }.map { it.id } }, { selected = emptySet() }, busy)
                        if (state.tracks.any { it.id in activeSelection && it.downloaded }) TextButton(onClick = { removeDownloadIds = activeSelection }, enabled = !busy) { Text("Remove selected downloads") }
                        if (playlist != null && !playlist.plex) TextButton(onClick = { vm.removeFromPlaylist(playlist.id, activeSelection); selected = emptySet() }) { Text("Remove from playlist") }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(visible.isNotEmpty() && visible.all { it.id in activeSelection }, { checked -> selected = if (checked) visible.map { it.id }.toSet() else emptySet() }, modifier = Modifier.testTag("select-all"))
                        Text("${visible.size} tracks", fontSize = 12.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = { deleteIds = RatingRules.oneStar(visible).map { it.id }.toSet() }, enabled = RatingRules.oneStar(visible).isNotEmpty() && !busy) { Text("Clean 1★") }
                    }
                    if (state.tracks.isEmpty()) {
                        EmptyCard("Your music, wherever you go", "Open the Plex tab to sign in and import your music for streaming. Choose a download folder there for offline listening.")
                    } else if (group != "Tracks" && groupValue == null) {
                        val groups = base.flatMap { t -> keys(t).map { it.trim().lowercase(java.util.Locale.ROOT) to t } }.groupBy({ it.first }, { it.second }).toSortedMap()
                        LazyColumn(Modifier.weight(1f)) { items(groups.keys.toList()) { key ->
                            val tracks = groups.getValue(key)
                            val label = keys(tracks.first()).firstOrNull { it.trim().equals(key, ignoreCase = true) } ?: key
                            Row(Modifier.fillMaxWidth().clickable { groupValue = key; selected = emptySet() }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(tracks.all { it.id in selected }, { checked -> selected = if (checked) selected + tracks.map { it.id } else selected - tracks.map { it.id }.toSet() })
                                Column(Modifier.weight(1f)) { Text(label, fontWeight = FontWeight.SemiBold); Text("${tracks.size} tracks • ${tracks.count { it.downloaded }} offline", fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary) }
                                IconButton(onClick = { download(tracks.map { it.id }.toSet()) }, enabled = !busy && !state.offline && tracks.any { !it.downloaded && it.part.isNotBlank() }) { Icon(painterResource(R.drawable.ic_download), "Download all by $label") }
                                IconButton(onClick = { removeDownloadIds = tracks.map { it.id }.toSet() }, enabled = !busy && tracks.any { it.downloaded }) { Icon(Icons.Default.Delete, "Remove downloads by $label") }
                            }
                        } }
                    } else {
                        LazyColumn(Modifier.weight(1f)) { items(visible, key = { it.id }) { track ->
                            TrackRow(track, track.id in activeSelection, track.id == now?.id, { checked -> selected = if (checked) selected + track.id else selected - track.id },
                                { play { PlaybackService.instance?.playTracks(visible, visible.indexOf(track)) } }, { ratingIds = setOf(track.id) },
                                { if (track.downloaded) removeDownloadIds = setOf(track.id) else download(setOf(track.id)) },
                                !busy && (track.downloaded || (!state.offline && track.part.isNotBlank() && state.downloads.none { it.id == track.id && it.state != "Failed" })))
                            if (playlist != null && !playlist.plex && track.id in activeSelection) Row {
                                TextButton(onClick = { vm.movePlaylistTrack(playlist.id, track.id, -1) }) { Text("Move up") }
                                TextButton(onClick = { vm.movePlaylistTrack(playlist.id, track.id, 1) }) { Text("Move down") }
                            }
                        } }
                    }
                }
                "Playlists" -> {
                    Text("Plex playlists are imported when you refresh. Create local playlists from selected tracks, and use them offline.", fontSize = 13.sp, modifier = Modifier.padding(vertical = 12.dp))
                    Button(onClick = { playlistIds = emptyList() }) { Text("New playlist") }
                    LazyColumn { items(state.playlists, key = { it.id }) { p ->
                        Row(Modifier.fillMaxWidth().clickable { playlistId = p.id; tab = "Library"; group = "Tracks"; groupValue = null; search = ""; selected = emptySet() }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) { Text(p.name, fontSize = 18.sp); Text("${p.tracks.size} tracks • ${if (p.plex) "Plex" else "On this device"}", fontSize = 12.sp) }
                            if (!p.plex) IconButton(onClick = { vm.deletePlaylist(p.id) }) { Icon(Icons.Default.Delete, "Delete playlist only") }
                        }
                    } }
                }
                "Downloads" -> if (smartDownload) SmartDownloadScreen(state, busy, connection.serverId.isNotBlank(),
                    { smartDownload = false }, vm::refresh, { folderPicker.launch(null) }, ::download)
                else DownloadsScreen(state, busy, vm, { smartDownload = true }) { removeDownloadIds = it }
                "Settings" -> Settings(vm, state, busy, { folderPicker.launch(null) }, { showDiscard = true })
            }
        }
    }
    ratingIds?.let { ids ->
        AlertDialog(onDismissRequest = { ratingIds = null }, title = { Text("Rate ${ids.size} track${if (ids.size == 1) "" else "s"}") }, text = {
            Column {
                Text("Plex ratings stay on this device until you choose Sync ratings. Ratings for your own files stay local.")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    (1..5).forEach { stars -> TextButton(onClick = { vm.rate(ids, stars); ratingIds = null }) { Text("$stars★") } }
                }
                TextButton(onClick = { vm.rate(ids, 0); ratingIds = null }) { Text("Clear rating") }
            }
        }, confirmButton = { TextButton(onClick = { ratingIds = null }) { Text("Cancel") } })
    }
    deleteIds?.let { ids -> DeleteDialog(state.tracks.filter { it.id in ids }, state.offline, { deleteIds = null }) { local, plex -> vm.delete(ids, local, plex); deleteIds = null; selected = emptySet() } }
    removeDownloadIds?.let { ids ->
        val tracks = state.tracks.filter { it.id in ids && it.downloaded }
        RemoveDownloadsDialog(tracks, busy, { removeDownloadIds = null }) {
            vm.removeDownloads(tracks.map { it.id }.toSet()); removeDownloadIds = null; selected = selected - ids
        }
    }
    playlistIds?.let { ids -> PlaylistDialog(state.playlists.filterNot { it.plex }, { playlistIds = null }) { name, target -> vm.playlist(name, ids, target); playlistIds = null } }
    if (showSync) AlertDialog(onDismissRequest = { showSync = false }, title = { Text("Sync ${queued.size} ratings to Plex?") }, text = {
        Column { Text("Only ratings are sent. Failed changes remain queued. No music is deleted.")
            LazyColumn(Modifier.heightIn(max = 280.dp)) { items(queued) { Text("${it.pendingRating}★  ${it.artist} — ${it.title}", fontSize = 13.sp, modifier = Modifier.padding(vertical = 6.dp)) } }
        }
    }, confirmButton = { TextButton(onClick = { showSync = false; vm.syncRatings() }, enabled = !state.offline && !busy) { Text("Sync now") } }, dismissButton = { TextButton(onClick = { showSync = false }) { Text("Cancel") } })
    if (showDiscard) AlertDialog(onDismissRequest = { showDiscard = false }, title = { Text("Discard queued ratings?") }, text = { Text("This removes unsynced Plex rating changes from this device. Existing Plex ratings and local-only ratings are kept.") },
        confirmButton = { TextButton(onClick = { vm.discardRatings(); showDiscard = false }) { Text("Discard") } }, dismissButton = { TextButton(onClick = { showDiscard = false }) { Text("Cancel") } })
    if (showQueue) AlertDialog(onDismissRequest = { showQueue = false }, title = { Text("Playback queue") }, text = {
        LazyColumn(Modifier.heightIn(max = 400.dp)) {
            val c = controller
            if (c != null) items(c.mediaItemCount) { index -> val item = c.getMediaItemAt(index)
                Text("${if (index == c.currentMediaItemIndex) "▶ " else ""}${item.mediaMetadata.title}", modifier = Modifier.fillMaxWidth().clickable { c.seekTo(index, 0); c.play(); showQueue = false }.padding(12.dp))
            }
        }
    }, confirmButton = { TextButton(onClick = { showQueue = false }) { Text("Close") } })
}

@Composable private fun DropChoice(label: String, options: List<String>, choose: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box { TextButton(onClick = { expanded = true }) { Text("$label ▾") }
        DropdownMenu(expanded, { expanded = false }) { options.forEach { value -> DropdownMenuItem(text = { Text(value) }, onClick = { choose(value); expanded = false }) } }
    }
}
@Composable private fun TrackRow(t: Track, selected: Boolean, current: Boolean, select: (Boolean) -> Unit, play: () -> Unit, rate: () -> Unit, transfer: () -> Unit, transferEnabled: Boolean) {
    Row(Modifier.fillMaxWidth().background(if (current) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent, RoundedCornerShape(12.dp)).padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(selected, select)
        Column(Modifier.weight(1f).clickable(onClick = play).padding(vertical = 9.dp)) {
            Text(t.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            Text("${t.artist} • ${t.album}", fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.secondary)
            Text("${if (t.downloaded) "↓ On device" else "Plex stream"}  •  ${time(t.duration)}", fontSize = 11.sp, color = MaterialTheme.colorScheme.secondary)
        }
        TextButton(onClick = rate) { Text("${if (t.rating == 0) "☆" else "${t.ratingText}★"}${if (t.pendingRating != null) " ·" else ""}") }
        IconButton(onClick = transfer, enabled = transferEnabled, modifier = Modifier.testTag("track-download-${t.id}")) {
            if (t.downloaded) Icon(Icons.Default.Delete, "Remove download: ${t.title}") else Icon(painterResource(R.drawable.ic_download), "Download: ${t.title}")
        }
    }
}
@Composable private fun SelectionBar(count: Int, rate: () -> Unit, download: () -> Unit, delete: () -> Unit, playlist: () -> Unit, clear: () -> Unit, busy: Boolean) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)).padding(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Text("$count selected", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)); TextButton(onClick = clear) { Text("Clear") } }
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            TextButton(onClick = rate) { Text("Rate") }; TextButton(onClick = download, enabled = !busy) { Text("Download") }
            TextButton(onClick = playlist) { Text("+ Playlist") }; TextButton(onClick = delete, enabled = !busy) { Text("Delete") }
        }
    }
}
@Composable private fun NowPlaying(t: Track, playing: Playing, controller: MediaController?, rate: () -> Unit, queue: () -> Unit) {
    var position by remember { mutableLongStateOf(0L) }; var duration by remember { mutableLongStateOf(t.duration) }
    LaunchedEffect(controller, t.id) { while (true) { position = controller?.currentPosition ?: 0; duration = (controller?.duration?.takeIf { it > 0 } ?: t.duration).coerceAtLeast(0); delay(500) } }
    Surface(tonalElevation = 6.dp) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 18.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text(t.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(t.artist, fontSize = 12.sp) }
                TextButton(onClick = rate) { Text("${t.ratingText}★") }
                IconButton(onClick = { controller?.seekToPreviousMediaItem() }) { Icon(painterResource(R.drawable.ic_previous), "Previous track") }
                FilledIconButton(onClick = { controller?.let { if (it.isPlaying) it.pause() else { it.prepare(); it.play() } } }) { if (playing.playing) Icon(painterResource(R.drawable.ic_pause), "Pause") else Icon(Icons.Default.PlayArrow, "Play") }
                IconButton(onClick = { controller?.seekToNextMediaItem() }) { Icon(painterResource(R.drawable.ic_next), "Next track") }
            }
            Slider(value = position.coerceIn(0, duration.coerceAtLeast(1)).toFloat(), onValueChange = { controller?.seekTo(it.toLong()); position = it.toLong() }, valueRange = 0f..duration.coerceAtLeast(1).toFloat(), modifier = Modifier.height(24.dp))
            Row { Text("${time(position)} / ${time(duration)}", fontSize = 10.sp, modifier = Modifier.weight(1f)); Text(if (playing.radio) "Radio • Queue ›" else "Queue ›", fontSize = 11.sp, modifier = Modifier.clickable(onClick = queue)) }
            if (playing.error.isNotBlank()) Text(playing.error, color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
        }
    }
}
private fun time(ms: Long): String = "%d:%02d".format(ms.coerceAtLeast(0) / 60_000, ms.coerceAtLeast(0) / 1000 % 60)

@Composable private fun Settings(vm: MusicViewModel, state: LibraryState, busy: Boolean, folder: () -> Unit, discard: () -> Unit) {
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Offline music folder", fontSize = 21.sp, fontWeight = FontWeight.Bold)
        Text("Choose a dedicated folder such as Music/OfflinePlexMusic. Plex downloads go here. You can copy your own music into the same folder, then tap Scan folder. Subfolders are included.", fontSize = 13.sp)
        Text(if (state.folder.isBlank()) "No folder selected" else android.net.Uri.decode(state.folder.substringAfterLast('/')), fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
        Row { Button(onClick = folder, enabled = !busy && state.downloads.isEmpty()) { Text("Choose folder") }; TextButton(onClick = { vm.scan() }, enabled = !busy && state.folder.isNotBlank()) { Text("Scan folder") } }
        HorizontalDivider()
        Text("Ratings & cleanup", fontSize = 21.sp, fontWeight = FontWeight.Bold)
        Text("A dot beside a rating means it is waiting to sync. Sync is always manual. Select an artist, album, genre, playlist, or individual tracks to rate, download, or delete in bulk. Clean 1★ reviews one-star tracks in your current view, including queued ratings.", fontSize = 13.sp)
        Text("Plex deletion removes the server’s media file, and requires an account with deletion permission plus Allow media deletion in Plex settings. The review asks you to choose this device, Plex, or both.", fontSize = 13.sp)
        TextButton(onClick = discard, enabled = !busy && state.tracks.any { it.pendingRating != null }) { Text("Discard queued Plex ratings") }
        HorizontalDivider()
        Text("Android Auto", fontSize = 21.sp, fontWeight = FontWeight.Bold)
        Text("Connect your phone to Android Auto and open Offline Plex music. Browse Library, Downloads, Playlists, or Radio. Radio uses your 2 Track limit setting. Car rating buttons save ratings until you sync on your phone.", fontSize = 13.sp)
        Text("For this GitHub APK, enable Unknown sources in Android Auto’s developer settings if the app is missing from the car launcher. Complete Plex sign-in, imports, and download setup on your phone before driving.", fontSize = 13.sp)
        Text("Offline Plex music 0.6.0 • Original-quality streaming and downloads. Device codec support determines which files can play.", fontSize = 11.sp, modifier = Modifier.padding(bottom = 20.dp))
    }
}
@Composable private fun EmptyCard(title: String, body: String) {
    Card(Modifier.fillMaxWidth().padding(vertical = 18.dp)) { Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(painterResource(R.drawable.ic_note), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(42.dp))
        Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold); Text(body, fontSize = 14.sp)
    } }
}
@Composable private fun DeleteDialog(tracks: List<Track>, offline: Boolean, dismiss: () -> Unit, confirm: (Boolean, Boolean) -> Unit) {
    var local by remember { mutableStateOf(true) }; var plex by remember { mutableStateOf(false) }; var typed by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Review music deletion") }, text = {
        Column {
            Text("${tracks.size} selected tracks. This deletes files. Unsynced ratings are not sent first.", fontSize = 13.sp)
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(local, { local = it }); Text("This device (${tracks.count { it.downloaded }})") }
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(plex, { plex = it }, enabled = !offline); Text("Plex server (${tracks.count { it.remoteKey.isNotBlank() }})") }
            if (plex) {
                Text("Server deletion is permanent and affects other devices. Type DELETE to confirm.", color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                OutlinedTextField(typed, { typed = it }, singleLine = true, label = { Text("DELETE") })
            }
            LazyColumn(Modifier.heightIn(max = 180.dp)) { items(tracks) { Text("${it.ratingText}★ ${it.artist} — ${it.title}", fontSize = 12.sp, modifier = Modifier.padding(vertical = 5.dp)) } }
        }
    }, confirmButton = { TextButton(onClick = { confirm(local, plex) }, enabled = tracks.isNotEmpty() && (local && tracks.any { it.downloaded } || plex && tracks.any { it.remoteKey.isNotBlank() }) && (!plex || typed == "DELETE")) { Text("Delete selected files") } }, dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}
@Composable private fun PlaylistDialog(playlists: List<Playlist>, dismiss: () -> Unit, confirm: (String, String?) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Save to playlist") }, text = {
        Column {
            OutlinedTextField(name, { name = it }, label = { Text("New playlist name") }, singleLine = true)
            LazyColumn(Modifier.heightIn(max = 220.dp)) { items(playlists) { p -> TextButton(onClick = { confirm(p.name, p.id) }) { Text("Add to ${p.name}") } } }
        }
    }, confirmButton = { TextButton(onClick = { confirm(name, null) }, enabled = name.isNotBlank()) { Text("Create") } }, dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}
