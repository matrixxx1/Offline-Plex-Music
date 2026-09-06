package com.m3.pocketmusic

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val store = applicationContext.musicStore
        setForeground(notification("Preparing downloads"))
        val api = try { PlexApi(store.credentials.read()).also { it.verifyServer() } }
        catch (_: Exception) { return@withContext Result.retry() }
        // A process interruption keeps the durable job and restarts that file safely.
        store.update { s -> s.copy(downloads = s.downloads.map { if (it.state == "Downloading") it.copy(state = "Queued") else it }) }
        while (true) {
            currentCoroutineContext().ensureActive()
            val job = store.state.value.downloads.firstOrNull { it.state == "Queued" } ?: break
            val track = store.state.value.tracks.find { it.id == job.id }
            if (track == null || track.downloaded) {
                store.update { it.copy(downloads = it.downloads.filterNot { j -> j.id == job.id }) }; continue
            }
            var doc: DocumentFile? = null
            try {
                val folder = store.state.value.folder
                val root = DocumentFile.fromTreeUri(applicationContext, Uri.parse(folder)) ?: error("Choose a download folder")
                check(root.canWrite()) { "Music folder is not writable" }
                change(job.id, "Downloading")
                setForeground(notification(track.title))
                val safeName = (track.artist + " - " + track.title).replace(Regex("[^\\p{L}\\p{N} ._-]"), "_").take(100)
                val ext = track.extension.lowercase().takeIf { it.matches(Regex("[a-z0-9]{2,5}")) } ?: "mp3"
                val prefix = "PM_${track.remoteKey}_"
                // Only remove our exact incomplete download from an interrupted attempt.
                root.listFiles().filter { it.name == "$prefix.partial" }.forEach { check(it.delete()) { "Could not clean incomplete download" } }
                val target = root.createFile("application/octet-stream", "$prefix.partial") ?: error("Could not create download")
                doc = target
                var copied = 0L
                api.download(track) { input, expected ->
                    applicationContext.contentResolver.openOutputStream(target.uri, "wt")!!.use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            if (isStopped) throw CancellationException()
                            val n = input.read(buffer); if (n < 0) break
                            output.write(buffer, 0, n); copied += n
                        }
                    }
                    check(copied > 0 && (expected < 0 || expected == copied)) { "Incomplete download; retry required" }
                    check(track.bytes == 0L || copied == track.bytes) { "Downloaded size differs from Plex; retry required" }
                }
                synchronized(store) {
                    if (isStopped || store.state.value.downloads.none { it.id == job.id }) throw CancellationException()
                    check(target.renameTo("$prefix$safeName.$ext")) { "Could not finalize download" }
                    store.patch(track.id) { it.copy(localUri = target.uri.toString()) }
                    store.update { it.copy(downloads = it.downloads.filterNot { j -> j.id == job.id }) }
                }
                doc = null
            } catch (cancelled: CancellationException) {
                doc?.delete(); change(job.id, "Queued"); throw cancelled
            } catch (e: Exception) {
                doc?.delete(); change(job.id, "Failed", e.message ?: "Download failed")
            }
        }
        Result.success()
    }
    private fun change(id: String, state: String, error: String = "") = applicationContext.musicStore.update { s ->
        s.copy(downloads = s.downloads.map { if (it.id == id) it.copy(state = state, error = error) else it })
    }
    private fun notification(title: String): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("downloads", "Music downloads", NotificationManager.IMPORTANCE_LOW))
        val notification = NotificationCompat.Builder(applicationContext, "downloads").setSmallIcon(R.drawable.ic_music)
            .setContentTitle("Pocket Music downloads").setContentText(title).setOngoing(true)
            .addAction(0, "Cancel", androidx.work.WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)).build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(42, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else ForegroundInfo(42, notification)
    }
}
