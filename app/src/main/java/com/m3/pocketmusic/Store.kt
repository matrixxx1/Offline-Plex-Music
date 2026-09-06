package com.m3.pocketmusic

import android.app.Application
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class MusicApp : Application() {
    val store by lazy { MusicStore(this) }
}
val Context.musicStore get() = (applicationContext as MusicApp).store

fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }

object LibraryJson {
    fun encode(s: LibraryState): String = JSONObject().apply {
        put("tracks", JSONArray(s.tracks.map { t -> JSONObject().apply {
            put("id", t.id); put("title", t.title); put("artist", t.artist); put("album", t.album)
            put("albumId", t.albumId); put("artistId", t.artistId); put("genres", JSONArray(t.genres))
            put("disc", t.disc); put("number", t.number); put("duration", t.duration)
            put("remoteKey", t.remoteKey); put("part", t.part); put("extension", t.extension)
            put("localUri", t.localUri); put("serverRating", t.serverRating); put("localRating", t.localRating)
            put("pendingRating", t.pendingRating ?: JSONObject.NULL); put("bytes", t.bytes)
            put("exactPlexRating", t.exactPlexRating ?: JSONObject.NULL)
        } }))
        put("playlists", JSONArray(s.playlists.map { p -> JSONObject().put("id", p.id).put("name", p.name)
            .put("tracks", JSONArray(p.tracks)).put("plex", p.plex) }))
        put("downloads", JSONArray(s.downloads.map { j -> JSONObject().put("id", j.id).put("state", j.state).put("error", j.error) }))
        put("folder", s.folder); put("offline", s.offline); put("mode", s.mode.name); put("twoTrack", s.twoTrack)
    }.toString()
    fun decode(json: String): LibraryState {
        val o = JSONObject(json)
        return LibraryState(
            tracks = o.optJSONArray("tracks")?.objects()?.map { t -> Track(
                id = t.getString("id"), title = t.getString("title"), artist = t.optString("artist"), album = t.optString("album"),
                albumId = t.optString("albumId"), artistId = t.optString("artistId"),
                genres = t.optJSONArray("genres")?.strings().orEmpty(), disc = t.optInt("disc", 1), number = t.optInt("number"),
                duration = t.optLong("duration"), remoteKey = t.optString("remoteKey"), part = t.optString("part"),
                extension = t.optString("extension", "mp3"), localUri = t.optString("localUri"),
                serverRating = t.optInt("serverRating"), localRating = t.optInt("localRating"),
                pendingRating = if (t.isNull("pendingRating")) null else t.getInt("pendingRating"), bytes = t.optLong("bytes"),
                exactPlexRating = if (t.isNull("exactPlexRating")) null else t.getDouble("exactPlexRating")
            ) }.orEmpty(),
            playlists = o.optJSONArray("playlists")?.objects()?.map { Playlist(it.getString("id"), it.getString("name"), it.getJSONArray("tracks").strings(), it.optBoolean("plex")) }.orEmpty(),
            downloads = o.optJSONArray("downloads")?.objects()?.map { DownloadJob(it.getString("id"), it.getString("state"), it.optString("error")) }.orEmpty(),
            folder = o.optString("folder"), offline = o.optBoolean("offline"),
            mode = runCatching { PlayMode.valueOf(o.optString("mode")) }.getOrDefault(PlayMode.RANDOM_TRACK), twoTrack = o.optBoolean("twoTrack")
        )
    }
}

class MusicStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "library.json"))
    private val mutable = MutableStateFlow(if (file.baseFile.exists()) LibraryJson.decode(file.openRead().bufferedReader().use { it.readText() }) else LibraryState())
    val state = mutable.asStateFlow()
    val credentials = Credentials(context)
    @Synchronized fun update(change: (LibraryState) -> LibraryState) {
        val next = change(mutable.value)
        val output = file.startWrite()
        try { output.write(LibraryJson.encode(next).toByteArray()); file.finishWrite(output) }
        catch (e: Exception) { file.failWrite(output); throw e }
        mutable.value = next
    }
    fun rate(ids: Set<String>, stars: Int) = update { it.copy(tracks = it.tracks.map { t -> if (t.id in ids) RatingRules.rate(t, stars) else t }) }
    fun patch(id: String, change: (Track) -> Track) = update { it.copy(tracks = it.tracks.map { t -> if (t.id == id) change(t) else t }) }
}

data class PlexConfig(val url: String = "", val token: String = "", val serverId: String = "")
/** Token is encrypted using a non-exportable Android Keystore key; backups are disabled. */
class Credentials(context: Context) {
    private val prefs = context.getSharedPreferences("connection", Context.MODE_PRIVATE)
    val clientId: String get() = prefs.getString("clientId", null) ?: java.util.UUID.randomUUID().toString().also {
        check(prefs.edit().putString("clientId", it).commit())
    }
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("plex-token", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("plex-token", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun read(): PlexConfig {
        val encrypted = prefs.getString("token", "").orEmpty()
        val token = if (encrypted.isBlank()) "" else {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(prefs.getString("iv", ""), Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)))
        }
        return PlexConfig(prefs.getString("url", "").orEmpty(), token, prefs.getString("server", "").orEmpty())
    }
    fun save(config: PlexConfig) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        check(prefs.edit().putString("url", config.url).putString("server", config.serverId)
            .putString("token", Base64.encodeToString(cipher.doFinal(config.token.toByteArray()), Base64.NO_WRAP))
            .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP)).commit())
    }
    fun readLogin(): PlexLogin {
        val encrypted = prefs.getString("login", "").orEmpty()
        if (encrypted.isBlank()) return PlexLogin()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(prefs.getString("login_iv", ""), Base64.NO_WRAP)))
        return PlexLogin.decode(String(cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)), Charsets.UTF_8))
    }
    fun saveLogin(login: PlexLogin) {
        if (!login.pending) { check(prefs.edit().remove("login").remove("login_iv").commit()); return }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        check(prefs.edit().putString("login", Base64.encodeToString(cipher.doFinal(login.encode().toByteArray(Charsets.UTF_8)), Base64.NO_WRAP))
            .putString("login_iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP)).commit())
    }
}
