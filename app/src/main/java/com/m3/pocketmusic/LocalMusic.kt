package com.m3.pocketmusic

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object LocalMusic {
    private val extensions = setOf("mp3", "flac", "m4a", "aac", "ogg", "opus", "wav", "wma", "aiff", "alac")
    suspend fun scan(context: Context, progress: (String) -> Unit) = withContext(Dispatchers.IO) {
        val store = context.musicStore
        val folder = store.state.value.folder
        require(folder.isNotBlank()) { "Choose a music folder first" }
        val root = DocumentFile.fromTreeUri(context, Uri.parse(folder)) ?: error("Music folder is unavailable")
        check(root.canRead()) { "Folder access was lost. Choose the folder again." }
        val found = mutableListOf<Track>()
        val known = store.state.value.tracks.associateBy { it.localUri }
        fun visit(dir: DocumentFile) {
            dir.listFiles().forEach { file ->
                if (file.isDirectory) visit(file)
                else if (file.name.orEmpty().substringAfterLast('.').lowercase() in extensions) {
                    val uri = file.uri.toString()
                    progress("Scanning ${file.name}")
                    val existing = known[uri]
                    if (existing != null) found += existing
                    else {
                        val reader = MediaMetadataRetriever()
                        try {
                            reader.setDataSource(context, file.uri)
                            fun tag(key: Int) = reader.extractMetadata(key).orEmpty()
                            found += Track(id = "local:$uri", title = tag(MediaMetadataRetriever.METADATA_KEY_TITLE).ifBlank { file.name.orEmpty().substringBeforeLast('.') },
                                artist = tag(MediaMetadataRetriever.METADATA_KEY_ARTIST).ifBlank { "Unknown artist" },
                                album = tag(MediaMetadataRetriever.METADATA_KEY_ALBUM).ifBlank { "Unknown album" },
                                genres = tag(MediaMetadataRetriever.METADATA_KEY_GENRE).split(';').map { it.trim() }.filter { it.isNotBlank() },
                                number = tag(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER).substringBefore('/').toIntOrNull() ?: 0,
                                disc = tag(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER).substringBefore('/').toIntOrNull() ?: 1,
                                duration = tag(MediaMetadataRetriever.METADATA_KEY_DURATION).toLongOrNull() ?: 0, localUri = uri, bytes = file.length())
                        } catch (_: Exception) {
                            found += Track(id = "local:$uri", title = file.name.orEmpty(), localUri = uri, bytes = file.length())
                        } finally { reader.release() }
                    }
                }
            }
        }
        visit(root)
        val uris = found.map { it.localUri }.toSet()
        store.update { s ->
            val currentIds = s.tracks.map { it.id }.toSet()
            s.copy(tracks = s.tracks.map { t -> if (t.downloaded && t.localUri !in uris &&
                DocumentFile.fromSingleUri(context, Uri.parse(t.localUri))?.exists() != true) t.copy(localUri = "") else t }
                .filter { it.remoteKey.isNotBlank() || it.downloaded } + found.filter { it.id !in currentIds })
        }
        found.size
    }
    fun delete(context: Context, track: Track) {
        if (track.localUri.isBlank()) return
        val doc = DocumentFile.fromSingleUri(context, Uri.parse(track.localUri)) ?: error("Local file unavailable")
        check(doc.delete()) { "Could not delete ${track.title}; check folder access" }
    }
}
