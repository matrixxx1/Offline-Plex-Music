package com.m3.pocketmusic

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.random.Random

@Composable fun PlaylistDownloadScreen(state: LibraryState, busy: Boolean, connected: Boolean,
    back: () -> Unit, refresh: () -> Unit, folder: () -> Unit, wifi: (Boolean) -> Unit, download: (Set<String>) -> Unit,
    initialPlaylistId: String? = null) {
    val keyboard = LocalSoftwareKeyboardController.current
    var selected by remember { mutableStateOf(initialPlaylistId?.let { setOf(it) }.orEmpty()) }
    var configure by remember { mutableStateOf(initialPlaylistId != null) }
    var search by remember { mutableStateOf("") }
    var limited by remember { mutableStateOf(false) }
    var megabytes by remember { mutableStateOf("500") }
    var artistLimit by remember { mutableStateOf(false) }
    var songsPerArtist by remember { mutableStateOf("3") }
    var seed by remember { mutableIntStateOf(Random.nextInt()) }
    val playlists = remember(state.playlists, search) { state.playlists.filter { it.plex && it.name.contains(search, true) }.sortedBy { it.name.lowercase() } }
    val request = remember(state, selected) { state to selected }
    val loaded by produceState<Pair<Pair<LibraryState, Set<String>>, List<Track>>?>(null, request) {
        value = withContext(Dispatchers.Default) { request to PlaylistDownloads.pending(request.first, request.second) }
    }
    val candidates = loaded?.takeIf { it.first === request }?.second
    val maxBytes = if (limited) PlaylistDownloads.byteLimit(megabytes) else null
    val perArtist = if (limited && artistLimit) songsPerArtist.toIntOrNull()?.takeIf { it > 0 } else null
    val valid = (!limited || maxBytes != null) && (!limited || !artistLimit || perArtist != null)
    val planRequest = remember(candidates, limited, maxBytes, perArtist, valid, seed) { Any() }
    val planned by produceState<Pair<Any, PlaylistDownloadPlan>?>(null, planRequest) {
        value = if (candidates == null || !valid) null else withContext(Dispatchers.Default) {
            planRequest to PlaylistDownloads.plan(candidates, maxBytes, perArtist, Random(seed))
        }
    }
    val plan = planned?.takeIf { it.first === planRequest }?.second
    val names = remember(state.playlists, selected) { state.playlists.filter { it.id in selected }.joinToString { it.name } }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        TextButton(onClick = { if (configure) configure = false else back() }) { Text(if (configure) "← Choose playlists" else "← Downloads") }
        Text(if (configure) "Playlist download options" else "Download Plex playlists", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        if (!configure) {
            Text("Select playlists made in Plex. Shared songs download once; existing downloads and queued songs are skipped.", fontSize = 13.sp)
            TextButton(onClick = refresh, enabled = connected && !busy && !state.offline) { Text("Refresh Plex playlists") }
            OutlinedTextField(search, { search = it }, placeholder = { Text("Search playlists") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row {
                TextButton(onClick = { selected = selected + playlists.map { it.id } }) { Text("Select all shown") }
                TextButton(onClick = { selected = emptySet() }) { Text("Clear selection") }
            }
            LazyColumn(Modifier.weight(1f)) {
                if (playlists.isEmpty()) item { Text("No Plex playlists found. Create an audio playlist in Plex, then refresh here.", modifier = Modifier.padding(12.dp)) }
                items(playlists, key = { it.id }) { playlist ->
                    Row(Modifier.fillMaxWidth().testTag("download-playlist-${playlist.id}").clickable {
                        selected = if (playlist.id in selected) selected - playlist.id else selected + playlist.id
                    }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(playlist.id in selected, onCheckedChange = null)
                        Column(Modifier.weight(1f)) { Text(playlist.name, fontWeight = FontWeight.SemiBold); Text("${playlist.tracks.size} songs", fontSize = 12.sp) }
                    }
                }
            }
            Button(onClick = { configure = true }, enabled = selected.isNotEmpty(), modifier = Modifier.fillMaxWidth().testTag("playlist-download-options")) { Text("Download options") }
        } else {
            LazyColumn(Modifier.weight(1f).testTag("playlist-download-preview")) {
                item {
                    Text(names, fontWeight = FontWeight.SemiBold)
                    Text("Existing downloads and queued songs are skipped. The size limit applies to new files in this batch.", fontSize = 12.sp)
                    DownloadChoice("All songs", !limited, "download-all") { limited = false }
                    DownloadChoice("Up to a size limit", limited, "download-limited") { limited = true }
                    if (limited) {
                        OutlinedTextField(megabytes, { megabytes = it }, label = { Text("Maximum MB") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }), isError = maxBytes == null,
                            modifier = Modifier.fillMaxWidth().testTag("download-max-mb"))
                        if (maxBytes == null) Text("Enter a positive whole number of MB.", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                        DownloadChoice("Completely random", !artistLimit, "download-random") { artistLimit = false }
                        DownloadChoice("Limit songs per artist", artistLimit, "download-per-artist") { artistLimit = true }
                        if (artistLimit) {
                            OutlinedTextField(songsPerArtist, { songsPerArtist = it }, label = { Text("Songs per artist") }, singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }), isError = perArtist == null,
                                modifier = Modifier.fillMaxWidth().testTag("download-artist-count"))
                            Text("Random songs, up to this many per artist and within the total MB limit. An artist may contribute fewer when space is tight.", fontSize = 12.sp)
                        }
                        TextButton(onClick = { seed = Random.nextInt() }, modifier = Modifier.testTag("download-reshuffle")) { Text("Reshuffle selection") }
                    }
                    DownloadWifiSetting(state.wifiOnlyDownloads, wifi)
                    if (!connected) Text("Connect your server in the Plex tab first.", fontSize = 13.sp)
                    if (state.folder.isBlank()) OutlinedButton(onClick = folder, enabled = !busy) { Text("Choose download folder") }
                    if (plan == null && valid) Text("Loading download selection…")
                    if (plan != null) {
                        Text("${plan.tracks.size} new songs • ${String.format(java.util.Locale.ROOT, "%.2f", plan.bytes / 1_000_000.0)} MB", fontWeight = FontWeight.Bold, modifier = Modifier.testTag("download-plan-summary"))
                        if (plan.unknownSizes > 0) Text(if (limited) "${plan.unknownSizes} songs excluded because Plex has no file size." else "${plan.unknownSizes} songs have unknown sizes; total size may be larger.", fontSize = 12.sp)
                        if (limited) Text("1 MB = 1,000,000 bytes. Selection uses Plex's original file sizes; retries can use additional network data.", fontSize = 11.sp)
                        if (plan.tracks.isEmpty()) Text("No new songs fit this selection. Increase the limit, or use All songs.", fontSize = 12.sp)
                    }
                }
                items(plan?.tracks.orEmpty(), key = { it.id }) { track ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).testTag("download-preview-${track.id}")) {
                        Text(track.title, maxLines = 1)
                        Text("${track.artist} • ${track.album}", fontSize = 12.sp, maxLines = 1)
                    }
                }
            }
            Button(onClick = { plan?.let { download(it.tracks.map { t -> t.id }.toSet()); back() } },
                enabled = plan?.tracks?.isNotEmpty() == true && connected && state.folder.isNotBlank() && !state.offline && !busy && valid,
                modifier = Modifier.fillMaxWidth().testTag("playlist-download-confirm")) { Text("Queue ${plan?.tracks?.size ?: 0} songs") }
            Text("Ratings and Plex deletion stay separate from downloading.", fontSize = 11.sp)
        }
    }
}

@Composable private fun DownloadChoice(label: String, selected: Boolean, tag: String, choose: () -> Unit) {
    Row(Modifier.fillMaxWidth().testTag(tag).clickable(onClick = choose), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick = null); Text(label, fontSize = 14.sp)
    }
}
