package com.m3.pocketmusic

import com.sun.net.httpserver.HttpServer
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress

class PlexAccountApiTest {
    @Test fun dnsFailureUsesAlternateHostAndRetainsItForLaterRequests() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var failedHostCalls = 0
        server.createContext("/") { x ->
            val body = """{"authToken":"test-token"}""".toByteArray()
            x.sendResponseHeaders(200, body.size.toLong()); x.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val fallback = "http://127.0.0.1:${server.address.port}"
            val api = PlexAccountApi("client", "https://plex.tv", fallback) { address ->
                if (address.startsWith("https://plex.tv/")) { failedHostCalls++; throw java.net.UnknownHostException("plex.tv") }
                java.net.URL(address).openConnection() as java.net.HttpURLConnection
            }
            repeat(2) { assertEquals("test-token", api.checkPin(PlexPin(42, "code", 1800))) }
            assertEquals(1, failedHostCalls)
        } finally { server.stop(0) }
    }
    @Test fun pinFlowUsesStrongPinConsistentClientAndHeaderToken() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val requests = mutableListOf<String>()
        var polled = false
        server.createContext("/") { x ->
            requests += x.requestURI.toString()
            assertEquals("device-one", x.requestHeaders.getFirst("X-Plex-Client-Identifier"))
            assertEquals(APP_NAME, x.requestHeaders.getFirst("X-Plex-Product"))
            val response = when (x.requestURI.path) {
                "/api/v2/pins" -> {
                    assertEquals("POST", x.requestMethod)
                    assertEquals("strong=true", x.requestBody.bufferedReader().readText())
                    """{"id":42,"code":"strong&code","expiresIn":180}"""
                }
                "/api/v2/pins/42" -> {
                    assertTrue(x.requestURI.rawQuery.contains("code=strong%26code"))
                    if (polled) """{"authToken":"account-token"}""" else { polled = true; """{"authToken":null}""" }
                }
                else -> { assertEquals("account-token", x.requestHeaders.getFirst("X-Plex-Token")); "[]" }
            }
            x.sendResponseHeaders(200, response.toByteArray().size.toLong())
            x.responseBody.use { it.write(response.toByteArray()) }
        }
        server.start()
        try {
            val api = PlexAccountApi("device-one", "http://127.0.0.1:${server.address.port}")
            val pin = api.createPin()
            assertEquals(180, pin.expiresIn)
            assertTrue(api.authUrl(pin).startsWith("https://app.plex.tv/auth#?clientID=device-one&code=strong%26code"))
            assertFalse(api.authUrl(pin).contains("account-token"))
            assertNull(api.checkPin(pin)); assertEquals("account-token", api.checkPin(pin))
            assertTrue(api.servers("account-token").isEmpty())
            assertTrue(requests.none { "account-token" in it })
        } finally { server.stop(0) }
    }
    @Test fun discoveryKeepsServerTokenAndPrefersSecureConnections() {
        val resources = JSONArray("""[
          {"name":"Home","provides":"server","clientIdentifier":"home","accessToken":"server-token","connections":[
            {"uri":"http://192.168.1.2:32400","local":true},
            {"uri":"https://remote.plex.direct:32400","local":false},
            {"uri":"https://local.plex.direct:32400","local":true}]},
          {"name":"Player","provides":"player","clientIdentifier":"player","accessToken":"other","connections":[{"uri":"https://player"}]},
          {"name":"Unavailable","provides":"server","clientIdentifier":"missing","connections":[]}
        ]""")
        val servers = PlexAccountApi.parseServers(resources)
        assertEquals(1, servers.size)
        assertEquals("server-token", servers.single().token)
        assertEquals(listOf("https://local.plex.direct:32400", "https://remote.plex.direct:32400", "http://192.168.1.2:32400"), servers.single().connections)
    }
    @Test fun discoveryRejectsMalformedOrCredentialBearingAddresses() {
        val resources = JSONArray("""[{"name":"Home","provides":"server","clientIdentifier":"home","accessToken":"token","connections":[
          {"uri":"https://user:password@host"},{"uri":"https://host?token=bad"},{"uri":"file:///secret"},{"uri":"https://host/#fragment"},{"uri":"nonsense"}]}]""")
        assertTrue(PlexAccountApi.parseServers(resources).isEmpty())
    }
    @Test fun failedAuthenticationProducesActionableErrorWithoutToken() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { x -> x.sendResponseHeaders(401, -1); x.close() }
        server.start()
        try {
            val api = PlexAccountApi("device", "http://127.0.0.1:${server.address.port}")
            val error = assertThrows(IllegalStateException::class.java) { api.servers("secret") }
            assertTrue(error.message!!.contains("401")); assertFalse(error.message!!.contains("secret"))
        } finally { server.stop(0) }
    }
}
