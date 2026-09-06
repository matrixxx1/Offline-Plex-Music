package com.m3.pocketmusic

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable fun SmartDownloadScreen(state: LibraryState, busy: Boolean, connected: Boolean,
    back: () -> Unit, refresh: () -> Unit, chooseFolder: () -> Unit, download: (Set<String>) -> Unit) {
    var category by rememberSaveable { mutableStateOf(DownloadCategory.ALL) }
    var amount by rememberSaveable { mutableStateOf(DownloadAmount.ALL) }
    var count by rememberSaveable { mutableStateOf("10") }
    var allGroups by rememberSaveable { mutableStateOf(true) }
    var selectedGroups by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var showGroups by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<SmartDownloadPlan?>(null) }
    var excluded by remember { mutableStateOf(emptySet<String>()) }
    val focus = LocalFocusManager.current
    val available = remember(state.tracks, state.downloads) { SmartDownloads.available(state) }
    val groups = remember(available, category) { SmartDownloads.groups(available, category) }
    val chosenGroups = selectedGroups.toSet()
    val countValid = amount == DownloadAmount.ALL || count.toIntOrNull()?.let { it in 1..100_000 } == true
    fun makePreview() {
        focus.clearFocus()
        preview = SmartDownloads.plan(state, SmartDownloadRequest(category,
            if (allGroups) null else chosenGroups, amount, count.toIntOrNull() ?: 10))
        excluded = emptySet()
    }
    fun goBack() { if (preview != null) preview = null else back() }
    BackHandler(onBack = ::goBack)
    Column(Modifier.fillMaxSize().imePadding()) {
        TextButton(onClick = ::goBack) { Text(if (preview == null) "← Downloads" else "← Edit selection") }
        Text(if (preview == null) "Smart download" else "Review downloads", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        val plan = preview
        if (plan == null) {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
                item { Text("Build an offline mix from your Plex library. Already downloaded or queued songs are skipped.", fontSize = 13.sp) }
                item {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DownloadCategory.entries.forEach { kind -> FilterChip(category == kind, {
                            category = kind; allGroups = true; selectedGroups = emptyList()
                            if (kind == DownloadCategory.ALL && amount == DownloadAmount.PER_GROUP) amount = DownloadAmount.TOTAL
                        }, label = { Text(kind.label) }, modifier = Modifier.testTag("smart-category-${kind.name}")) }
                    }
                }
                if (category != DownloadCategory.ALL) item {
                    OutlinedButton(onClick = { showGroups = true }, enabled = groups.isNotEmpty(), modifier = Modifier.fillMaxWidth().testTag("smart-choose-groups")) {
                        Text(if (allGroups) "All ${groups.size} ${category.label.lowercase()} ▾"
                            else "${groups.count { it.key in chosenGroups }} ${category.label.lowercase()} selected ▾")
                    }
                    if (!allGroups) Text(groups.filter { it.key in chosenGroups }.joinToString { it.label }.ifBlank { "No groups selected" },
                        maxLines = 3, overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
                }
                if (category in listOf(DownloadCategory.MOOD, DownloadCategory.STYLE)) item {
                    Text("Uses existing Plex tags on songs, albums and artists. Refresh music after upgrading to load these tags. Nothing is guessed or written back to Plex.", fontSize = 12.sp)
                    if (groups.isEmpty()) Text("No ${category.label.lowercase()} found on songs available to download. Refresh your library; if none appear, Plex has not supplied these tags.", fontSize = 13.sp)
                }
                item { Text("How many?", fontWeight = FontWeight.Bold) }
                item { Column {
                    AmountChoice("All matching songs", amount == DownloadAmount.ALL, "smart-amount-all") { amount = DownloadAmount.ALL }
                    AmountChoice("Random songs, total", amount == DownloadAmount.TOTAL, "smart-amount-total") { amount = DownloadAmount.TOTAL }
                    if (category != DownloadCategory.ALL) AmountChoice("Random songs per ${category.singular}", amount == DownloadAmount.PER_GROUP, "smart-amount-group") { amount = DownloadAmount.PER_GROUP }
                } }
                if (amount != DownloadAmount.ALL) item {
                    OutlinedTextField(count, { count = it }, singleLine = true,
                        label = { Text(if (amount == DownloadAmount.PER_GROUP) "Songs per ${category.singular}" else "Songs total") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }), isError = !countValid,
                        supportingText = { Text(if (!countValid) "Enter a whole number from 1 to 100000." else "Up to this many new songs, chosen randomly.") },
                        modifier = Modifier.fillMaxWidth().testTag("smart-count"))
                    if (amount == DownloadAmount.PER_GROUP) Text("Each group is sampled independently. Shared songs download once; a song can belong to several groups.", fontSize = 12.sp)
                }
                item {
                    Text("${available.size} new Plex songs available", fontSize = 13.sp)
                    TextButton(onClick = refresh, enabled = !busy && !state.offline && connected) { Text("Refresh Plex music & tags") }
                    if (!connected) Text("Connect to your Plex server from the Plex tab first.", fontSize = 13.sp)
                }
            }
            Button(onClick = ::makePreview, enabled = countValid && groups.isNotEmpty() && (allGroups || groups.any { it.key in chosenGroups }),
                modifier = Modifier.fillMaxWidth().testTag("smart-preview")) { Text("Preview downloads") }
        } else {
            // Keep the reviewed random sample fixed. Only remove tracks which are no longer eligible.
            val availableIds = available.map { it.id }.toHashSet()
            val reviewed = plan.tracks.filter { it.id in availableIds }
            val selected = reviewed.filter { it.id !in excluded }
            val size = android.text.format.Formatter.formatShortFileSize(androidx.compose.ui.platform.LocalContext.current, selected.sumOf { it.bytes.coerceAtLeast(0) })
            Text("${selected.size} songs • $size${if (selected.any { it.bytes <= 0 }) " + unknown sizes" else " estimated"}", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(vertical = 8.dp))
            if (amount != DownloadAmount.ALL) TextButton(onClick = ::makePreview) { Text("Reshuffle preview") }
            if (plan.tracks.isEmpty()) Text("No new songs match. Choose other groups or refresh Plex music.")
            if (reviewed.size < plan.tracks.size) Text("Some previewed songs are already downloaded, queued, or no longer available. They will be skipped.", fontSize = 12.sp)
            Text("Uncheck any song to leave it out. This adds downloads without changing ratings or deleting files.", fontSize = 12.sp)
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(vertical = 8.dp)) {
                items(reviewed, key = { it.id }) { track ->
                    Row(Modifier.fillMaxWidth().clickable { excluded = if (track.id in excluded) excluded - track.id else excluded + track.id }
                        .padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(track.id !in excluded, { excluded = if (it) excluded - track.id else excluded + track.id }, modifier = Modifier.testTag("smart-track-${track.id}"))
                        Column(Modifier.weight(1f)) {
                            Text(track.title, fontWeight = FontWeight.SemiBold)
                            Text("${track.artist} • ${track.album}", fontSize = 12.sp)
                            if (category == DownloadCategory.MOOD) Text(track.moods.joinToString(), fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                            if (category == DownloadCategory.STYLE) Text(track.styles.joinToString(), fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
            if (state.folder.isBlank()) OutlinedButton(onClick = chooseFolder, enabled = !busy && state.downloads.isEmpty(), modifier = Modifier.fillMaxWidth()) { Text("Choose download folder") }
            if (state.offline) Text("Enable online access to start downloading.", fontSize = 12.sp)
            Button(onClick = { download(selected.map { it.id }.toSet()); preview = null; back() },
                enabled = !busy && !state.offline && connected && state.folder.isNotBlank() && selected.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().testTag("smart-download-confirm")) { Text("Download ${selected.size} songs") }
        }
    }
    if (showGroups) {
        var search by remember { mutableStateOf("") }
        val visible = groups.filter { it.label.contains(search, ignoreCase = true) }
        AlertDialog(onDismissRequest = { showGroups = false }, title = { Text("Choose ${category.label.lowercase()}") },
            text = { Column {
                OutlinedTextField(search, { search = it }, singleLine = true, label = { Text("Search ${category.label.lowercase()}") }, modifier = Modifier.testTag("smart-group-search"))
                Row {
                    TextButton(onClick = { allGroups = true; selectedGroups = emptyList() }) { Text("Select all") }
                    TextButton(onClick = { allGroups = false; selectedGroups = emptyList() }) { Text("Clear") }
                }
                LazyColumn(Modifier.heightIn(max = 320.dp)) { items(visible, key = { it.key }) { group ->
                    Row(Modifier.fillMaxWidth().clickable {
                        val current = if (allGroups) groups.map { it.key }.toSet() else chosenGroups
                        selectedGroups = (if (group.key in current) current - group.key else current + group.key).toList(); allGroups = false
                    }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(allGroups || group.key in chosenGroups, onCheckedChange = null)
                        Column(Modifier.weight(1f)) { Text(group.label); Text("${group.tracks.size} new songs", fontSize = 12.sp) }
                    }
                } }
                if (visible.isEmpty()) Text("No matching ${category.label.lowercase()}")
            } }, confirmButton = { TextButton(onClick = { showGroups = false }) { Text("Done") } })
    }
}

@Composable private fun AmountChoice(label: String, selected: Boolean, tag: String, choose: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = choose).testTag(tag), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick = null); Text(label, fontSize = 14.sp)
    }
}
