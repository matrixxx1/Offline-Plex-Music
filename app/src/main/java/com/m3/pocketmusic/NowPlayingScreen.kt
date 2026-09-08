package com.m3.pocketmusic

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.session.MediaController
import kotlinx.coroutines.delay

@Composable fun NowPlayingScreen(track: Track?, playing: Playing, controller: MediaController?, busy: Boolean,
    rate: () -> Unit, removeLocal: () -> Unit, flag: () -> Unit, sync: () -> Unit, browse: () -> Unit) {
    if (track == null) {
        Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Nothing playing yet", style = MaterialTheme.typography.headlineSmall)
            Text("Choose a playlist or song to start listening.")
            Button(onClick = browse) { Text("Choose a playlist") }
            if (playing.error.isNotBlank()) Text(playing.error, color = MaterialTheme.colorScheme.error)
        }
        return
    }
    var position by remember { mutableLongStateOf(0) }
    var duration by remember(track.id) { mutableLongStateOf(track.duration) }
    var queue by remember { mutableStateOf(emptyList<Pair<String, String>>()) }
    var queueIndex by remember { mutableIntStateOf(0) }
    var previous by remember { mutableStateOf(false) }
    var next by remember { mutableStateOf(false) }
    LaunchedEffect(controller, track.id) {
        while (true) {
            position = controller?.currentPosition ?: 0
            duration = controller?.duration?.takeIf { it > 0 } ?: track.duration.coerceAtLeast(0)
            queue = controller?.let { c -> (0 until c.mediaItemCount).map { i ->
                val metadata = c.getMediaItemAt(i).mediaMetadata
                metadata.title.toString() to metadata.artist.toString()
            } }.orEmpty()
            queueIndex = controller?.currentMediaItemIndex ?: 0
            previous = controller?.hasPreviousMediaItem() == true
            next = controller?.hasNextMediaItem() == true
            delay(500)
        }
    }
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                AlbumArt(track, Modifier.padding(top = 12.dp).size(190.dp))
                Text(track.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
                Text(track.artist, style = MaterialTheme.typography.titleMedium)
                Text(track.album, color = MaterialTheme.colorScheme.secondary)
                Text("${if (playing.radio) "Radio" else "Playlist playback"} • ${if (track.downloaded) "On this device" else "Plex stream"}", style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            Slider(position.coerceIn(0, duration.coerceAtLeast(1)).toFloat(), { controller?.seekTo(it.toLong()); position = it.toLong() },
                valueRange = 0f..duration.coerceAtLeast(1).toFloat(), enabled = controller != null)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(playTime(position)); Text(playTime(duration))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { controller?.seekToPreviousMediaItem() }, enabled = previous) { Icon(painterResource(R.drawable.ic_previous), "Previous track") }
                FilledIconButton(onClick = { controller?.let { if (it.isPlaying) it.pause() else { it.prepare(); it.play() } } }, modifier = Modifier.size(64.dp), enabled = controller != null) {
                    if (playing.playing) Icon(painterResource(R.drawable.ic_pause), "Pause") else Icon(Icons.Default.PlayArrow, "Play")
                }
                IconButton(onClick = { controller?.seekToNextMediaItem() }, enabled = next) { Icon(painterResource(R.drawable.ic_next), "Next track") }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                OutlinedButton(onClick = { PlaybackService.instance?.shufflePlaylist() }, enabled = controller != null) { Text("Shuffle") }
                OutlinedButton(onClick = { PlaybackService.instance?.nextArtist() }, enabled = controller != null) { Text("Next artist") }
                TextButton(onClick = { PlaybackService.instance?.stopPlayback() }) { Text("Stop") }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = rate) { Text("Rate ${track.ratingText}★") }
                Text(if (track.pendingRating != null) "Rating waiting for sync" else "", style = MaterialTheme.typography.bodySmall)
            }
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                if (track.downloaded) TextButton(onClick = removeLocal, enabled = !busy) { Text("Delete local copy") }
                if (track.remoteKey.isNotBlank()) TextButton(onClick = flag, enabled = !busy) { Text(if (track.pendingDeletion) "Unflag Plex deletion" else "Flag Plex deletion") }
                TextButton(onClick = sync, enabled = !busy) { Text("Review sync") }
            }
            if (track.pendingDeletion) Text("Flagged for Plex deletion at next confirmed sync", color = MaterialTheme.colorScheme.error)
            if (playing.error.isNotBlank()) Text(playing.error, color = MaterialTheme.colorScheme.error)
        }
        item { HorizontalDivider(); Text("Playback queue", style = MaterialTheme.typography.titleMedium) }
        items(queue.size) { index ->
            val song = queue[index]
            ListItem(headlineContent = { Text("${if (index == queueIndex) "▶ " else ""}${song.first}", maxLines = 2, overflow = TextOverflow.Ellipsis) },
                supportingContent = { Text(song.second) }, modifier = Modifier.clickable { controller?.seekTo(index, 0); controller?.play() })
        }
    }
}
private fun playTime(ms: Long) = "%d:%02d".format(ms.coerceAtLeast(0) / 60_000, ms.coerceAtLeast(0) / 1000 % 60)
