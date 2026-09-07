package com.m3.pocketmusic

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable fun PlaylistDownloadScreen(state: LibraryState, busy: Boolean, connected: Boolean,
    back: () -> Unit, refresh: () -> Unit, folder: () -> Unit, wifi: (Boolean) -> Unit, download: (Set<String>) -> Unit) {
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var search by remember { mutableStateOf("") }
    val playlists = remember(state.playlists, search) { state.playlists.filter { it.plex && it.name.contains(search, true) }.sortedBy { it.name.lowercase() } }
    val pending = remember(state, selected) { PlaylistDownloads.pending(state, selected) }
    val tracks = remember(state.tracks) { state.tracks.associateBy { it.id } }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        TextButton(onClick = back) { Text("← Downloads") }
        Text("Download Plex playlists", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("Select playlists made in Plex. Shared songs download once; existing downloads and queued songs are skipped.", fontSize = 13.sp)
        TextButton(onClick = refresh, enabled = connected && !busy && !state.offline) { Text("Refresh Plex playlists") }
        if (!connected) Text("Connect your server in the Plex tab first.", fontSize = 13.sp)
        DownloadWifiSetting(state.wifiOnlyDownloads, wifi)
        if (state.folder.isBlank()) OutlinedButton(onClick = folder, enabled = !busy) { Text("Choose download folder") }
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
                    Column(Modifier.weight(1f)) {
                        Text(playlist.name, fontWeight = FontWeight.SemiBold)
                        Text("${playlist.tracks.size} songs • ${playlist.tracks.count { tracks[it]?.downloaded == true }} on device", fontSize = 12.sp)
                    }
                }
            }
        }
        Button(onClick = { download(pending.map { it.id }.toSet()); back() },
            enabled = pending.isNotEmpty() && connected && state.folder.isNotBlank() && !state.offline && !busy,
            modifier = Modifier.fillMaxWidth().testTag("playlist-download-confirm")) { Text("Queue ${pending.size} songs") }
        Text("This copies music to your phone. Ratings and Plex deletion only happen when you explicitly choose them.", fontSize = 11.sp)
    }
}
