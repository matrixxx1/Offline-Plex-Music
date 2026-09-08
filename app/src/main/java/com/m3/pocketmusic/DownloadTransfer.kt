package com.m3.pocketmusic

import java.io.InputStream
import java.io.OutputStream

object DownloadTransfer {
    /** Content-Length belongs to this response; cached library sizes are download-planning estimates. */
    fun copy(input: InputStream, output: OutputStream, responseLength: Long, checkAllowed: () -> Unit): Long {
        var copied = 0L
        val buffer = ByteArray(64 * 1024)
        while (true) {
            checkAllowed()
            val n = input.read(buffer)
            if (n < 0) break
            output.write(buffer, 0, n); copied += n
        }
        check(copied > 0 && (responseLength < 0 || responseLength == copied)) { "Incomplete download; retry required" }
        return copied
    }
}
