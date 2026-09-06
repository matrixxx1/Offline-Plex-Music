package com.m3.pocketmusic

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

class PlexLoginFlowTest {
    private class FakeApi : PlexAccountClient {
        var creates = 0; var polls = 0; var discoveries = 0
        var pollError: Exception? = null; var resourceError: Exception? = null
        var token: String? = "test-account-token"
        override fun createPin(): PlexPin { creates++; return PlexPin(42, "test-pin-code", 1800) }
        override fun authUrl(pin: PlexPin) = "https://app.plex.tv/auth#?code=${pin.code}"
        override fun checkPin(pin: PlexPin): String? { polls++; pollError?.let { throw it }; return token }
        override fun servers(token: String): List<PlexServer> {
            discoveries++; resourceError?.let { throw it }
            assertEquals("test-account-token", token)
            return listOf(PlexServer("Test server", "server-id", "test-server-token", listOf("https://server.plex.direct")))
        }
    }
    private var saved = PlexLogin()
    private var opened = 0
    private fun flow(api: FakeApi, sleep: suspend (Long) -> Unit = {}, now: () -> Long = { 1000 }) =
        PlexLoginFlow(api, { saved }, { saved = PlexLogin.decode(it.encode()) }, now, sleep)
    private val browser: (String) -> Unit = { assertNotNull(saved.pin); opened++ }

    @Test fun dnsFailureDuringPollingResumesSamePinWithoutOpeningBrowserAgain() = runBlocking {
        val api = FakeApi().apply { pollError = UnknownHostException("plex.tv") }
        try { flow(api).resume(browser) {}; fail("Expected a connection failure") } catch (_: IOException) { }
        assertEquals(1, api.creates); assertEquals(3, api.polls); assertEquals(1, opened)
        assertNotNull(saved.pin)
        api.pollError = null
        val results = flow(api).resume(browser) {} // A new coordinator simulates process restart.
        assertEquals(1, results.size); assertEquals(1, api.creates); assertEquals(1, opened)
        assertEquals("test-account-token", saved.token); assertNull(saved.pin)
    }
    @Test fun successfulTokenIsDurableBeforeFailingServerDiscovery() = runBlocking {
        val api = FakeApi().apply { resourceError = UnknownHostException("plex.tv") }
        try { flow(api).resume(browser) {}; fail("Expected DNS failure") } catch (_: IOException) { }
        assertEquals("test-account-token", saved.token); assertEquals(3, api.discoveries)
        api.resourceError = null
        flow(api).resume(browser) {}
        assertEquals(1, api.creates); assertEquals(1, api.polls); assertEquals(1, opened)
    }
    @Test fun temporaryFailureRetriesAutomaticallyWithoutNewAuthorization() = runBlocking {
        val api = FakeApi().apply { pollError = IOException("Temporary mobile connection loss") }
        var sleeps = 0
        flow(api, sleep = { sleeps++; api.pollError = null }).resume(browser) {}
        assertEquals(1, sleeps); assertEquals(2, api.polls); assertEquals(1, api.creates)
    }
    @Test fun serviceErrorsPreserveAuthorizationAndDoNotAskForNewLogin() = runBlocking {
        val api = FakeApi().apply { resourceError = PlexHttpException(503) }
        try { flow(api).resume(browser) {}; fail() } catch (e: IOException) { assertTrue(e.message!!.contains("Retry connection")) }
        assertEquals("test-account-token", saved.token); assertEquals(3, api.discoveries)
    }
    @Test fun revokedAccountClearsOnlyLoginAndDoesNotAutomaticallyOpenBrowser() = runBlocking {
        saved = PlexLogin(token = "test-account-token")
        val api = FakeApi().apply { resourceError = PlexHttpException(401) }
        try { flow(api).resume(browser) {}; fail() } catch (e: IllegalStateException) { assertTrue(e.message!!.contains("Sign in again")) }
        assertFalse(saved.pending); assertEquals(0, api.creates); assertEquals(0, opened); assertEquals(1, api.discoveries)
    }
    @Test fun expiredPinRequiresExplicitNewSignInAndUnexpiredPinUsesFullLifetime() = runBlocking {
        saved = PlexLogin(PlexPin(42, "test-pin-code", 1800), 100)
        val api = FakeApi()
        try { flow(api).resume(browser) {}; fail() } catch (e: IllegalStateException) { assertTrue(e.message!!.contains("expired")) }
        assertEquals(0, api.creates); assertFalse(saved.pending)
        saved = PlexLogin(PlexPin(42, "test-pin-code", 1800), 1_800_000)
        flow(api, now = { 1_000_000 }).resume(browser) {}
        assertEquals(0, opened); assertEquals(0, api.creates)
    }
    @Test fun cancellationKeepsPendingPinForRetry() = runBlocking {
        val api = FakeApi().apply { token = null }
        try { flow(api, sleep = { throw CancellationException("Paused") }).resume(browser) {}; fail() } catch (_: CancellationException) { }
        assertNotNull(saved.pin)
        api.token = "test-account-token"
        flow(api).resume(browser) {}
        assertEquals(1, opened); assertEquals(1, api.creates)
    }
    @Test fun tlsErrorsAreNotRetriedOrTreatedAsInvalidCredentials() = runBlocking {
        saved = PlexLogin(token = "test-account-token")
        val api = FakeApi().apply { resourceError = SSLHandshakeException("Certificate verification failed") }
        try { flow(api).resume(browser) {}; fail() } catch (_: SSLHandshakeException) { }
        assertEquals(1, api.discoveries); assertEquals("test-account-token", saved.token)
    }
}
