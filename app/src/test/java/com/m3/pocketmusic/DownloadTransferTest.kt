package com.m3.pocketmusic

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

class DownloadTransferTest {
    @Test fun responseLengthValidatesCompleteBytes() {
        val audio = ByteArray(130_000) { (it % 127).toByte() }
        val output = ByteArrayOutputStream()
        assertEquals(audio.size.toLong(), DownloadTransfer.copy(ByteArrayInputStream(audio), output, audio.size.toLong()) { })
        assertArrayEquals(audio, output.toByteArray())
    }
    @Test fun truncatedAndEmptyResponsesAreRejected() {
        for (length in listOf(0, 100)) {
            assertThrows(IllegalStateException::class.java) { DownloadTransfer.copy(ByteArrayInputStream(ByteArray(length)), ByteArrayOutputStream(), 200) { } }
        }
    }
    @Test fun chunkedResponseCanFinishWithoutKnownLength() {
        assertEquals(100L, DownloadTransfer.copy(ByteArrayInputStream(ByteArray(100)), ByteArrayOutputStream(), -1) { })
    }
    @Test fun networkInterruptionCannotBeReportedAsSuccess() {
        val input = object : InputStream() { override fun read(): Int = throw IOException("Connection dropped") }
        assertThrows(IOException::class.java) { DownloadTransfer.copy(input, ByteArrayOutputStream(), -1) { } }
    }
}
