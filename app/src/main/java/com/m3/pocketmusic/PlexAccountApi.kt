package com.m3.pocketmusic

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder

const val APP_NAME = "Offline Plex music"
data class PlexPin(val id: Long, val code: String, val expiresIn: Int)
data class PlexServer(val name: String, val id: String, val token: String, val connections: List<String>)

/** Plex's browser PIN flow. Account tokens remain in memory; only a selected server token is saved. */
class PlexAccountApi(private val clientId: String, private val baseUrl: String = "https://plex.tv") {
    private fun request(path: String, method: String = "GET", token: String = ""): String {
        val connection = URL(baseUrl + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 15_000; connection.readTimeout = 20_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("X-Plex-Product", APP_NAME)
            connection.setRequestProperty("X-Plex-Client-Identifier", clientId)
            connection.setRequestProperty("X-Plex-Platform", "Android")
            if (token.isNotBlank()) connection.setRequestProperty("X-Plex-Token", token)
            if (method == "POST") {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                connection.outputStream.use { it.write("strong=true".toByteArray()) }
            }
            check(connection.responseCode in 200..299) { "Plex sign-in returned HTTP ${connection.responseCode}. Try signing in again." }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally { connection.disconnect() }
    }
    fun createPin(): PlexPin = JSONObject(request("/api/v2/pins", "POST")).let {
        PlexPin(it.getLong("id"), it.getString("code"), it.optInt("expiresIn", 300).coerceIn(1, 900))
    }
    fun authUrl(pin: PlexPin): String = "https://app.plex.tv/auth#?clientID=${encode(clientId)}&code=${encode(pin.code)}&context%5Bdevice%5D%5Bproduct%5D=${encode(APP_NAME)}"
    fun checkPin(pin: PlexPin): String? = JSONObject(request("/api/v2/pins/${pin.id}?code=${encode(pin.code)}")).let {
        if (it.isNull("authToken")) null else it.optString("authToken").takeIf(String::isNotBlank)
    }
    fun servers(token: String): List<PlexServer> = parseServers(JSONArray(request("/api/v2/resources?includeHttps=1&includeRelay=1", token = token)))
    companion object {
        private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
        internal fun parseServers(resources: JSONArray): List<PlexServer> = (0 until resources.length()).mapNotNull { index ->
            val item = resources.getJSONObject(index)
            if ("server" !in item.optString("provides").split(',').map(String::trim)) return@mapNotNull null
            val token = item.optString("accessToken").takeUnless { it == "null" }.orEmpty()
            val id = item.optString("clientIdentifier")
            if (token.isBlank() || id.isBlank()) return@mapNotNull null
            val connections = item.optJSONArray("connections") ?: JSONArray()
            val addresses = (0 until connections.length()).map { connections.getJSONObject(it) }.filter {
                runCatching { URI(it.getString("uri")) }.getOrNull()?.let { uri ->
                    uri.scheme in listOf("https", "http") && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null
                } == true
            }.sortedWith(compareBy<JSONObject> { !it.getString("uri").startsWith("https://") }.thenBy { !it.optBoolean("local") }.thenBy { it.optBoolean("relay") })
                .map { it.getString("uri").trimEnd('/') }.distinct()
            if (addresses.isEmpty()) null else PlexServer(item.optString("name", "Plex server"), id, token, addresses)
        }
    }
}
