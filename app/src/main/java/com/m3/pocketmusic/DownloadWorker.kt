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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    companion object { private val transferLock = Mutex() }
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        transferLock.withLock {
            DownloadNetwork(applicationContext, { applicationContext.musicStore.state.value }, { isStopped }).use { guard ->
                transfer(guard)
            }
        }
    }
    private suspend fun transfer(guard: DownloadNetwork): Result {
        val store = applicationContext.musicStore
        if (store.state.value.offline || store.state.value.downloadsPaused) return Result.success()
        if (!guard.allowed()) return Result.retry()
        setForeground(notification("Preparing downloads"))
        // Some Android URLConnection implementations do not promptly unblock read() on disconnect.
        val api = try { PlexApi(store.credentials.read(), guard::open, readTimeoutMs = 10_000).also { it.verifyServer() } }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { return Result.retry() }
        // A process interruption keeps the durable job and restarts that file safely.
        store.update { s -> s.copy(downloads = s.downloads.map { if (it.state == "Downloading") it.copy(state = "Queued") else it }) }
        while (true) {
            currentCoroutineContext().ensureActive()
            if (!guard.allowed()) return Result.retry()
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
                        copied = DownloadTransfer.copy(input, output, expected) {
                            if (isStopped) throw CancellationException()
                            guard.check()
                        }
                    }
                }
                synchronized(store) {
                    if (isStopped || store.state.value.downloads.none { it.id == job.id }) throw CancellationException()
                    check(target.renameTo("$prefix$safeName.$ext")) { "Could not finalize download" }
                    store.patch(track.id) { it.copy(localUri = target.uri.toString(), bytes = copied) }
                    store.update { it.copy(downloads = it.downloads.filterNot { j -> j.id == job.id }) }
                }
                doc = null
            } catch (cancelled: CancellationException) {
                doc?.delete(); change(job.id, "Queued"); throw cancelled
            } catch (e: Exception) {
                if (isStopped) { doc?.delete(); change(job.id, "Queued"); throw CancellationException() }
                // Socket failure may arrive before Android publishes the changed network capabilities.
                if (e is DownloadDeferred || e is java.net.SocketTimeoutException || e is java.net.SocketException || !guard.allowed()) {
                    doc?.delete(); change(job.id, "Queued"); return Result.retry()
                }
                doc?.delete(); change(job.id, "Failed", e.message ?: "Download failed")
            }
        }
        return Result.success()
    }
    private fun change(id: String, state: String, error: String = "") = applicationContext.musicStore.update { s ->
        s.copy(downloads = s.downloads.map { if (it.id == id) it.copy(state = state, error = error) else it })
    }
    private fun notification(title: String): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("downloads", "Music downloads", NotificationManager.IMPORTANCE_LOW))
        val notification = NotificationCompat.Builder(applicationContext, "downloads").setSmallIcon(R.drawable.ic_music)
            .setContentTitle("Offline Plex music downloads").setContentText(title).setOngoing(true)
            .addAction(0, "Cancel", androidx.work.WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)).build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(42, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else ForegroundInfo(42, notification)
    }
}
