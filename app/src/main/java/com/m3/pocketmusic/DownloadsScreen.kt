package com.m3.pocketmusic

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable fun DownloadsScreen(state: LibraryState, busy: Boolean, vm: MusicViewModel, smartDownload: () -> Unit, remove: (Set<String>) -> Unit) {
    var queueTab by rememberSaveable { mutableStateOf(state.downloads.isNotEmpty()) }
    var filter by remember { mutableStateOf(DownloadFilter.ALL) }
    var key by remember { mutableStateOf<String?>(null) }
    var below by rememberSaveable { mutableDoubleStateOf(3.0) }
    var includeUnrated by rememberSaveable { mutableStateOf(false) }
    var includeOwn by rememberSaveable { mutableStateOf(true) }
    var search by rememberSaveable { mutableStateOf("") }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    val offline = state.tracks.filter { it.downloaded }
    val optionSource = offline.filter { includeOwn || it.remoteKey.isNotBlank() }
    val options = DownloadFilters.options(optionSource, filter)
    val filtered = DownloadFilters.matching(offline, filter, key, below, includeUnrated, includeOwn).filter {
        search.isBlank() || "${it.title} ${it.artist} ${it.album}".contains(search, ignoreCase = true)
    }
    val selection = selected.intersect(filtered.map { it.id }.toSet())
    Column(Modifier.fillMaxSize()) {
        Button(onClick = smartDownload, modifier = Modifier.fillMaxWidth()) { Text("Smart download from Plex") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(!queueTab, { queueTab = false }, label = { Text("On device (${offline.size})") })
            FilterChip(queueTab, { queueTab = true }, label = { Text("Transfers (${state.downloads.size})") })
        }
        if (queueTab) {
            DownloadWifiSetting(state.wifiOnlyDownloads, vm::setDownloadWifiOnly)
            Text(if (state.downloadsPaused) "Queue paused. Tap Resume / retry to continue."
                else if (state.offline) "Queue waits until Offline only is turned off and you resume."
                else if (state.wifiOnlyDownloads) "Queued songs start when Wi-Fi is available."
                else "Queued songs start when a network is available. Mobile data may be used.", fontSize = 12.sp)
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                TextButton(onClick = vm::retryDownloads, enabled = !busy && state.downloads.isNotEmpty()) { Text("Resume / retry") }
                TextButton(onClick = vm::pauseDownloads, enabled = state.downloads.isNotEmpty()) { Text("Pause") }
                TextButton(onClick = vm::cancelDownloads, enabled = state.downloads.isNotEmpty()) { Text("Cancel queue") }
            }
            Text("Completed files stay on your device when you cancel a transfer.", fontSize = 12.sp)
            LazyColumn(Modifier.weight(1f)) { items(state.downloads, key = { it.id }) { job ->
                Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                    Text(state.tracks.find { it.id == job.id }?.title ?: "Removed track")
                    Text(job.state, color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
                    if (job.error.isNotBlank()) Text(job.error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
            } }
        } else {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DownloadFilter.entries.forEach { kind -> FilterChip(filter == kind,
                    { filter = kind; key = null; selected = emptySet() }, label = { Text(kind.label) }) }
            }
            if (filter in listOf(DownloadFilter.ARTIST, DownloadFilter.ALBUM, DownloadFilter.GENRE)) {
                var expanded by remember(filter) { mutableStateOf(false) }
                Box {
                    OutlinedButton(onClick = { expanded = true }, modifier = Modifier.testTag("download-filter-value")) {
                        Text(options.find { it.key == key }?.label ?: "Choose ${filter.label.lowercase()} ▾", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    DropdownMenu(expanded, { expanded = false }, modifier = Modifier.heightIn(max = 320.dp)) {
                        options.forEach { option -> DropdownMenuItem(text = { Text(option.label) }, onClick = { key = option.key; selected = emptySet(); expanded = false }) }
                    }
                }
            }
            if (filter == DownloadFilter.BELOW_RATING) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Below ${formatStars(below)}★", fontWeight = FontWeight.SemiBold)
                    Slider(below.toFloat(), { below = (it * 2).toInt() / 2.0; selected = emptySet() },
                        valueRange = 0.5f..5f, steps = 8, modifier = Modifier.weight(1f).testTag("rating-threshold"))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(includeUnrated, { includeUnrated = it; selected = emptySet() }, modifier = Modifier.testTag("include-unrated"))
                    Text("Include unrated tracks", fontSize = 13.sp)
                }
                Text("Strictly below the threshold. Uses queued ratings too.", fontSize = 12.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(includeOwn, { includeOwn = it; selected = emptySet() }, modifier = Modifier.testTag("include-own-files"))
                Text("Include music I added myself", fontSize = 13.sp)
            }
            OutlinedTextField(search, { search = it; selected = emptySet() }, placeholder = { Text("Search downloaded music") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(filtered.isNotEmpty() && filtered.all { it.id in selection },
                    { selected = if (it) filtered.map { t -> t.id }.toSet() else emptySet() }, modifier = Modifier.testTag("select-downloads"))
                Text("${filtered.size} matching tracks", fontSize = 12.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = vm::scan, enabled = !busy && state.folder.isNotBlank()) { Text("Scan folder") }
            }
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                FilledTonalButton(onClick = { remove(filtered.map { it.id }.toSet()) }, enabled = !busy && filtered.isNotEmpty()) {
                    Text(if (filter == DownloadFilter.ALL && search.isBlank()) "Remove all downloads" else "Remove matching downloads")
                }
                if (selection.isNotEmpty()) TextButton(onClick = { remove(selection); selected = emptySet() }, enabled = !busy) { Text("Remove selected (${selection.size})") }
            }
            if (filtered.isEmpty()) Text(if (key == null && options.isNotEmpty()) "Choose a ${filter.label.lowercase()} to see its downloads." else "No downloaded tracks match these filters.", modifier = Modifier.padding(16.dp), fontSize = 13.sp)
            LazyColumn(Modifier.weight(1f)) { items(filtered, key = { it.id }) { track ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(track.id in selection, { selected = if (it) selected + track.id else selected - track.id })
                    Column(Modifier.weight(1f)) {
                        Text(track.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${track.artist} • ${track.album}", fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${track.ratingText}★${if (track.pendingRating != null) " queued" else ""}${if (track.remoteKey.isBlank()) " • Your file" else ""}", fontSize = 11.sp)
                    }
                    TextButton(onClick = { remove(setOf(track.id)) }, enabled = !busy, modifier = Modifier.testTag("remove-download-${track.id}")) { Text("Remove") }
                }
            } }
        }
    }
}

@Composable fun DownloadWifiSetting(checked: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, change, modifier = Modifier.testTag("download-wifi-only"))
        Text("Only download on Wi-Fi", modifier = Modifier.clickable { change(!checked) }, fontSize = 14.sp)
    }
}

private fun formatStars(stars: Double) = if (stars % 1 == 0.0) stars.toInt().toString() else stars.toString()

@Composable fun RemoveDownloadsDialog(tracks: List<Track>, busy: Boolean, dismiss: () -> Unit, confirm: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text("Remove ${tracks.size} local files?") }, text = {
        Column {
            Text("Plex music and queued ratings are kept. You can download Plex tracks again later.", fontSize = 13.sp)
            val own = tracks.count { it.remoteKey.isBlank() }
            if (own > 0) Text("Includes $own files you added yourself. These files will be deleted from your music folder.", color = MaterialTheme.colorScheme.error, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            LazyColumn(Modifier.heightIn(max = 250.dp)) { items(tracks, key = { it.id }) {
                Text("${it.artist} — ${it.title}", fontSize = 13.sp, modifier = Modifier.padding(vertical = 6.dp))
            } }
        }
    }, confirmButton = { TextButton(onClick = confirm, enabled = !busy && tracks.isNotEmpty()) { Text("Remove from device") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}
