package com.m3.pocketmusic

import org.junit.Assert.*
import org.junit.Test

class AppUpdaterTest {
    @Test fun versionsCompareNumerically() {
        assertTrue(AppUpdater.compareVersions("0.12.0", "0.11.9") > 0)
        assertEquals(0, AppUpdater.compareVersions("1.2", "1.2.0"))
        assertTrue(AppUpdater.compareVersions("2.0.0", "10.0.0") < 0)
    }

    @Test fun releaseRequiresANewerVersionAndApkAsset() {
        val json = """{"tag_name":"v0.12.0","html_url":"https://example/release","assets":[{"name":"notes.txt","browser_download_url":"https://example/notes"},{"name":"Pocket.apk","browser_download_url":"https://example/app.apk"}]}"""
        assertEquals("https://example/app.apk", AppUpdater.parseRelease(json, "0.11.1")!!.apkUrl)
        assertNull(AppUpdater.parseRelease(json, "0.12.0"))
        assertNull(AppUpdater.parseRelease(json, "1.0.0"))
    }
}
