package com.m3.pocketmusic

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DownloadPolicyTest {
    @Test fun wifiOnlyRejectsCellularAndUnknownRoutes() {
        assertTrue(DownloadPolicy.allows(true, true, true, false))
        assertFalse(DownloadPolicy.allows(true, true, false, true))
        assertFalse(DownloadPolicy.allows(true, true, false, false))
        assertFalse(DownloadPolicy.allows(true, true, true, true))
        assertFalse(DownloadPolicy.allows(true, false, true, false))
        // Meteredness is deliberately irrelevant: Wi-Fi is required, not just free data.
    }
    @Test fun optingOutAllowsCellularButStillRequiresConnection() {
        assertTrue(DownloadPolicy.allows(false, true, false, true))
        assertTrue(DownloadPolicy.allows(false, true, true, false))
        assertFalse(DownloadPolicy.allows(false, false, false, false))
    }
    @Test fun newAndUpgradedLibrariesDefaultToWifiWithoutLosingQueue() {
        assertTrue(LibraryState().wifiOnlyDownloads)
        val state = LibraryState(tracks = listOf(Track("1", "Song", pendingRating = 1)), downloads = listOf(DownloadJob("1")))
        val old = JSONObject(LibraryJson.encode(state)).apply { remove("wifiOnlyDownloads"); remove("downloadsPaused") }
        assertEquals(state, LibraryJson.decode(old.toString()))
        val customized = state.copy(wifiOnlyDownloads = false, downloadsPaused = true)
        assertEquals(customized, LibraryJson.decode(LibraryJson.encode(customized)))
    }
}
