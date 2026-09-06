package com.m3.pocketmusic

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import javax.net.ssl.SSLException

/** Stored only in encrypted credentials, never in the library or an Android saved-state bundle. */
data class PlexLogin(val pin: PlexPin? = null, val expiresAt: Long = 0, val token: String = "") {
    val pending get() = pin != null || token.isNotBlank()
    fun encode(): String = JSONObject().put("id", pin?.id).put("code", pin?.code).put("seconds", pin?.expiresIn)
        .put("expiresAt", expiresAt).put("token", token).toString()
    companion object {
        fun decode(value: String): PlexLogin {
            if (value.isBlank()) return PlexLogin()
            val json = JSONObject(value)
            return PlexLogin(if (json.isNull("id")) null else PlexPin(json.getLong("id"), json.getString("code"), json.getInt("seconds")), json.optLong("expiresAt"), json.optString("token"))
        }
    }
}

/** Keeps the same PIN across network failures; saves the account token before discovering servers. */
class PlexLoginFlow(
    private val api: PlexAccountClient,
    private val read: () -> PlexLogin,
    private val save: (PlexLogin) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) }
) {
    suspend fun resume(openBrowser: (String) -> Unit, progress: (String) -> Unit): List<PlexServer> {
        var login = read()
        if (login.token.isBlank()) {
            if (login.pin != null && now() >= login.expiresAt) {
                save(PlexLogin())
                error("The saved Plex sign-in expired. Tap Sign in with Plex to start a new one.")
            }
            if (login.pin == null) {
                progress("Preparing Plex sign-in")
                val pin = retry(progress) { api.createPin() }
                login = PlexLogin(pin, now() + pin.expiresIn * 1000L)
                save(login) // Save before leaving for the browser, including if Android kills this process.
                openBrowser(api.authUrl(pin))
            }
            val pin = requireNotNull(login.pin)
            while (login.token.isBlank()) {
                if (now() >= login.expiresAt) {
                    save(PlexLogin())
                    error("Plex sign-in expired. Tap Sign in with Plex to try again.")
                }
                progress("Finish sign-in in your browser, then return here. Your pending sign-in is saved.")
                val token = try { retry(progress) { api.checkPin(pin) } }
                catch (e: PlexHttpException) {
                    if (e.status in listOf(400, 401, 404, 410)) { save(PlexLogin()); error("Plex sign-in expired or is no longer valid. Start a new sign-in.") }
                    throw e
                }
                if (token != null) {
                    login = PlexLogin(token = token)
                    save(login) // Do not lose successful authorization if resources/DNS fails next.
                } else sleep(2000)
            }
        }
        progress("Signed in. Finding your Plex servers")
        return try { retry(progress) { api.servers(login.token) } }
        catch (e: PlexHttpException) {
            if (e.status == 401) { save(PlexLogin()); error("Plex no longer accepts this login. Sign in again to renew it.") }
            throw e
        }
    }
    private suspend fun <T> retry(progress: (String) -> Unit, request: () -> T): T {
        repeat(3) { attempt ->
            try { return withContext(Dispatchers.IO) { request() } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                val transient = (e is IOException && e !is SSLException) || (e is PlexHttpException && (e.status in listOf(408, 429) || e.status >= 500))
                if (!transient) throw e
                if (attempt == 2) throw IOException("Cannot reach Plex right now. Your sign-in progress is saved. Try Wi-Fi or check this app's mobile-data/VPN settings, then tap Retry connection.", e)
                progress("Plex is temporarily unreachable. Retrying connection (${attempt + 1}/2)…")
                sleep((attempt + 1) * 2000L)
            }
        }
        error("Plex request did not complete")
    }
}
