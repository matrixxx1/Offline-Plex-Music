package com.m3.pocketmusic

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable fun UpdatePrompt(message: (String) -> Unit) {
    val context = LocalContext.current
    val activity = context as ComponentActivity
    val lifecycle = LocalLifecycleOwner.current
    var release by remember { mutableStateOf<AppRelease?>(null) }
    var downloadId by remember { mutableLongStateOf(-1L) }
    var pendingInstall by remember { mutableStateOf<Uri?>(null) }

    LaunchedEffect(Unit) {
        val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
        release = runCatching { AppUpdater.available(version) }.getOrNull()
    }

    DisposableEffect(downloadId) {
        if (downloadId < 0) return@DisposableEffect onDispose { }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) != downloadId) return
                val uri = AppUpdater.downloadedUri(receiverContext, downloadId)
                if (uri == null) message("The app update download failed. You can retry from Settings.")
                else if (!AppUpdater.install(activity, uri)) {
                    pendingInstall = uri
                    message("Allow installs from Offline Plex music, then return to continue the update.")
                }
                downloadId = -1L
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }

    DisposableEffect(lifecycle, pendingInstall) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) pendingInstall?.let { uri ->
                if (AppUpdater.install(activity, uri)) pendingInstall = null
            }
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }

    release?.let { update ->
        AlertDialog(onDismissRequest = { release = null }, title = { Text("Update available") },
            text = { Text("Offline Plex music ${update.version} is available on GitHub. Download it now and open Android's installer?") },
            confirmButton = { TextButton(onClick = {
                downloadId = runCatching { AppUpdater.download(context, update) }.getOrElse {
                    message("Could not start the update download: ${it.message}"); -1L
                }
                if (downloadId >= 0) message("Downloading Offline Plex music ${update.version}…")
                release = null
            }) { Text("Download & install") } },
            dismissButton = { TextButton(onClick = { release = null }) { Text("Not now") } })
    }
}
