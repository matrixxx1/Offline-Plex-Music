package com.m3.pocketmusic

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.security.MessageDigest

/** Shared album cache, bounded to 32 MiB. Tokens are never stored in URLs or filenames. */
object ArtworkCache {
    private val lock = Mutex()
    suspend fun load(context: Context, track: Track): Bitmap? = withContext(Dispatchers.IO) {
        lock.withLock {
            runCatching {
                val store = context.musicStore
                val config = store.credentials.read()
                val artwork = track.artwork.ifBlank { if (track.remoteKey.isNotBlank() && track.albumId.isNotBlank()) "/library/metadata/${PlexApi.encode(track.albumId)}/thumb" else "" }
                val key = if (artwork.isNotBlank()) "${config.serverId}:$artwork" else track.localUri
                if (key.isBlank()) return@withLock null
                val hash = MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
                val dir = File(context.cacheDir, "album-art").apply { mkdirs() }
                val file = File(dir, "$hash.jpg")
                if (file.exists()) {
                    BitmapFactory.decodeFile(file.path)?.let { file.setLastModified(System.currentTimeMillis()); return@withLock it }
                }
                val bytes = if (artwork.isNotBlank() && !store.state.value.offline && config.token.isNotBlank()) {
                    runCatching { PlexApi(config, readTimeoutMs = 10_000).artwork(artwork) }.getOrNull()
                } else null
                val imageBytes = bytes ?: if (track.downloaded) {
                    val reader = MediaMetadataRetriever()
                    try { reader.setDataSource(context, Uri.parse(track.localUri)); reader.embeddedPicture }
                    finally { reader.release() }
                } else null
                if (imageBytes == null) return@withLock null
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withLock null
                val options = BitmapFactory.Options().apply { inSampleSize = 1 }
                while (maxOf(bounds.outWidth, bounds.outHeight) / options.inSampleSize > 512) options.inSampleSize *= 2
                val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, options) ?: return@withLock null
                val atomic = android.util.AtomicFile(file)
                val output = atomic.startWrite()
                try { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 88, output)); atomic.finishWrite(output) }
                catch (e: Exception) { atomic.failWrite(output); throw e }
                val files = dir.listFiles().orEmpty().sortedBy { it.lastModified() }
                var size = files.sumOf { it.length() }
                for (old in files) { if (size <= 32L * 1024 * 1024) break; val length = old.length(); if (old.delete()) size -= length }
                bitmap
            }.getOrNull()
        }
    }
}

@Composable fun AlbumArt(track: Track, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val state by context.musicStore.state.collectAsState()
    val bitmap by produceState<Bitmap?>(null, track.id, track.artwork, track.localUri, state.offline) {
        value = ArtworkCache.load(context, track)
    }
    if (bitmap != null) Image(bitmap!!.asImageBitmap(), "Album art for ${track.album}", modifier, contentScale = ContentScale.Crop)
    else Icon(painterResource(R.drawable.ic_note), "No album art", modifier)
}
