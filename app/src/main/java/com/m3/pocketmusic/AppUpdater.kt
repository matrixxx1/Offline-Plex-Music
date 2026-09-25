package com.m3.pocketmusic

import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class AppRelease(val version: String, val pageUrl: String, val apkUrl: String, val apkName: String)

object AppUpdater {
    const val RELEASES_URL = "https://github.com/matrixxx1/Offline-Plex-Music/releases/latest"
    private const val API_URL = "https://api.github.com/repos/matrixxx1/Offline-Plex-Music/releases/latest"

    suspend fun available(currentVersion: String): AppRelease? = withContext(Dispatchers.IO) {
        val connection = URL(API_URL).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "Offline-Plex-Music/$currentVersion")
            check(connection.responseCode in 200..299) { "GitHub returned ${connection.responseCode}" }
            parseRelease(connection.inputStream.bufferedReader().use { it.readText() }, currentVersion)
        } finally { connection.disconnect() }
    }

    internal fun parseRelease(json: String, currentVersion: String): AppRelease? {
        val release = JSONObject(json)
        val version = release.getString("tag_name").removePrefix("v")
        if (compareVersions(version, currentVersion) <= 0) return null
        val assets = release.getJSONArray("assets")
        val apk = (0 until assets.length()).asSequence().map { assets.getJSONObject(it) }
            .firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) } ?: return null
        return AppRelease(version, release.optString("html_url", RELEASES_URL), apk.getString("browser_download_url"), apk.getString("name"))
    }

    internal fun compareVersions(left: String, right: String): Int {
        fun parts(value: String) = value.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val a = parts(left); val b = parts(right)
        repeat(maxOf(a.size, b.size)) { index ->
            val comparison = (a.getOrNull(index) ?: 0).compareTo(b.getOrNull(index) ?: 0)
            if (comparison != 0) return comparison
        }
        return 0
    }

    fun download(context: Context, release: AppRelease): Long {
        val request = DownloadManager.Request(Uri.parse(release.apkUrl))
            .setTitle("Offline Plex music ${release.version}")
            .setDescription("Downloading app update")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, android.os.Environment.DIRECTORY_DOWNLOADS, release.apkName)
        return context.getSystemService(DownloadManager::class.java).enqueue(request)
    }

    fun downloadedUri(context: Context, id: Long): Uri? {
        val manager = context.getSystemService(DownloadManager::class.java)
        manager.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
            if (!cursor.moveToFirst()) return null
            val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            if (status != DownloadManager.STATUS_SUCCESSFUL) return null
        }
        return manager.getUriForDownloadedFile(id)
    }

    /** Returns false when Android first needs the user to allow installs from this app. */
    fun install(activity: Activity, apk: Uri): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.packageManager.canRequestPackageInstalls()) {
            activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
            return false
        }
        activity.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(apk, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }
}
