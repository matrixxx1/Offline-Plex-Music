package com.m3.pocketmusic

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable fun PlaylistEditorScreen(playlist: Playlist?, state: LibraryState, byId: Map<String, Track>, busy: Boolean,
    vm: MusicViewModel, choose: () -> Unit, browse: () -> Unit, play: (List<Track>) -> Unit) {
    if (playlist == null) {
        Column(Modifier.padding(vertical = 24.dp)) {
            Text("Select a playlist to edit its name, songs, and order.")
            Button(onClick = choose) { Text("Choose playlist") }
        }
        return
    }
    val p = playlist
    val focus = LocalFocusManager.current
    var name by remember(p.id, p.name) { mutableStateOf(p.name) }
    var addSongs by remember(p.id) { mutableStateOf(false) }
    var reviewSync by remember(p.id) { mutableStateOf(false) }
    var reload by remember(p.id) { mutableStateOf(false) }
    var moveIndex by remember(p.id) { mutableStateOf<Int?>(null) }
    val editable = !busy && !p.smart
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            TextButton(onClick = choose) { Text("Change playlist") }
            TextButton(onClick = browse) { Text("Browse songs") }
            TextButton(onClick = { play(p.tracks.mapNotNull { byId[it] }.filter { !state.offline || it.downloaded }) }, enabled = p.tracks.isNotEmpty()) { Text("Play playlist") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(name, { name = it }, label = { Text("Playlist name") }, modifier = Modifier.weight(1f).testTag("playlist-name"), singleLine = true, enabled = editable)
            TextButton(onClick = { vm.editPlaylist(p.id) { PlaylistEditing.edit(it, name = name) }; focus.clearFocus() }, enabled = editable && name.isNotBlank() && name.trim() != p.name) { Text("Save name") }
        }
        Text("${p.tracks.size} songs • ${if (p.plex) "Plex playlist" else "Local playlist"}${if (p.pendingSync) " • Unsynced edits" else ""}", modifier = Modifier.padding(vertical = 8.dp))
        if (p.smart) Text("Smart playlist: Plex controls the songs and order. Save a local copy to edit.", style = MaterialTheme.typography.bodySmall)
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            TextButton(onClick = { addSongs = true }, enabled = editable) { Text("Add songs") }
            TextButton(onClick = { vm.editPlaylist(p.id) { PlaylistEditing.edit(it, tracks = it.tracks.shuffled()) } }, enabled = editable && p.tracks.size > 1) { Text("Shuffle order") }
            TextButton(onClick = { vm.copyPlaylist(p.id) }, enabled = !busy) { Text("Save local copy") }
        }
        if (p.plex) {
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                Button(onClick = { reviewSync = true }, enabled = editable && p.pendingSync && !state.offline) { Text("Sync playlist") }
                TextButton(onClick = { reload = true }, enabled = !busy && !state.offline) { Text("Reload from Plex") }
            }
        }
        if (p.plex) Text(if (state.offline) "Edits are saved on this device. Go online to sync." else "Edits stay on this device until you choose Sync playlist.", style = MaterialTheme.typography.bodySmall)
        if (p.syncError.isNotBlank()) Text(p.syncError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        if (p.tracks.isEmpty()) Text("This playlist is empty. Add songs to get started.", modifier = Modifier.padding(16.dp))
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {
            items(p.tracks.size, key = { "$it:${p.tracks[it]}" }) { index ->
                val track = byId[p.tracks[index]]
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${index + 1}. ${track?.title ?: "Unavailable song"}", maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(track?.artist.orEmpty(), style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { moveIndex = index }, enabled = editable && p.tracks.size > 1, modifier = Modifier.testTag("playlist-move-$index")) { Text("Move") }
                    IconButton(onClick = { vm.editPlaylist(p.id) { current -> PlaylistEditing.edit(current, tracks = current.tracks.filterIndexed { i, _ -> i != index }) } }, enabled = editable, modifier = Modifier.testTag("playlist-remove-$index")) { Icon(Icons.Default.Delete, "Remove playlist entry ${index + 1}") }
                }
            }
        }
    }
    moveIndex?.let { from ->
        var position by remember(from) { mutableStateOf((from + 1).toString()) }
        val to = position.toIntOrNull()?.minus(1)
        AlertDialog(onDismissRequest = { moveIndex = null }, title = { Text("Move song ${from + 1}") }, text = {
            Column {
                Row { TextButton(onClick = { position = from.coerceAtLeast(1).toString() }) { Text("Up one") }; TextButton(onClick = { position = (from + 2).coerceAtMost(p.tracks.size).toString() }) { Text("Down one") } }
                OutlinedTextField(position, { position = it }, label = { Text("Position (1–${p.tracks.size})") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.testTag("playlist-position"))
            }
        }, confirmButton = { TextButton(onClick = { vm.editPlaylist(p.id) { PlaylistEditing.move(it, from, to!!) }; moveIndex = null }, enabled = editable && to != null && to in p.tracks.indices && from in p.tracks.indices) { Text("Move song") } }, dismissButton = { TextButton(onClick = { moveIndex = null }) { Text("Cancel") } })
    }
    if (reviewSync) AlertDialog(onDismissRequest = { reviewSync = false }, title = { Text("Sync playlist to Plex?") }, text = {
        Text("Update ${p.serverName} to ${p.name} with ${p.tracks.size} songs in the displayed order? This changes playlist membership and its name. Music files, ratings, and deletion flags are unchanged.")
    }, confirmButton = { TextButton(onClick = { reviewSync = false; vm.syncPlaylist(p.id) }, enabled = !busy && !state.offline) { Text("Sync to Plex") } }, dismissButton = { TextButton(onClick = { reviewSync = false }) { Text("Cancel") } })
    if (reload) AlertDialog(onDismissRequest = { reload = false }, title = { Text("Reload playlist from Plex?") }, text = { Text("Replace this device's playlist edits with the current Plex playlist. Save a local copy first if you want to keep your edits.") },
        confirmButton = { TextButton(onClick = { reload = false; vm.reloadPlaylist(p.id) }, enabled = !busy && !state.offline) { Text("Reload playlist") } }, dismissButton = { TextButton(onClick = { reload = false }) { Text("Cancel") } })
    if (addSongs) AddPlaylistSongs(p, state.tracks, busy, { addSongs = false }) { ids -> vm.addPlaylistTracks(p.id, ids); addSongs = false }
}

@Composable private fun AddPlaylistSongs(p: Playlist, tracks: List<Track>, busy: Boolean, dismiss: () -> Unit, add: (List<String>) -> Unit) {
    var search by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    val matches by produceState<List<Track>>(emptyList(), search, tracks, p.plex) {
        value = withContext(Dispatchers.Default) { tracks.filter { (!p.plex || it.remoteKey.isNotBlank()) &&
            (search.isBlank() || "${it.title} ${it.artist} ${it.album}".contains(search, true)) } }
    }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Add songs") }, text = {
        Column {
            OutlinedTextField(search, { search = it }, placeholder = { Text("Search songs, artists, albums") }, singleLine = true)
            Text("${selected.size} selected • ${matches.size} matches")
            LazyColumn(Modifier.heightIn(max = 320.dp)) { items(matches, key = { it.id }) { t ->
                Row(Modifier.fillMaxWidth().clickable { selected = if (t.id in selected) selected - t.id else selected + t.id }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(t.id in selected, null)
                    Column { Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(t.artist, style = MaterialTheme.typography.bodySmall) }
                }
            } }
        }
    }, confirmButton = { TextButton(onClick = { add(selected.toList()) }, enabled = !busy && selected.isNotEmpty()) { Text("Add ${selected.size} songs") } }, dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}
