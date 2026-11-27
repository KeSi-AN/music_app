package com.riyaz.app.net

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** A model file the app downloads once, checked against its known size and SHA-256. */
data class ModelFile(val name: String, val url: String, val bytes: Long, val sha256: String) {
    fun file(dir: File) = File(dir, name)
    fun ready(dir: File) = file(dir).length() == bytes

    /** Downloads it into [dir] if it isn't there yet. [onBytes] gets the bytes done so far. */
    fun ensure(dir: File, onBytes: (Long) -> Unit, isCancelled: () -> Boolean) {
        if (ready(dir)) return
        dir.mkdirs()
        val part = File(dir, "$name.part")
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        try {
            if (conn.responseCode != 200) throw IOException("Download failed (HTTP ${conn.responseCode})")
            val md = MessageDigest.getInstance("SHA-256")
            var done = 0L
            conn.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        if (isCancelled()) throw kotlinx.coroutines.CancellationException("cancelled")
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        md.update(buf, 0, n)
                        done += n
                        onBytes(done)
                    }
                }
            }
            val sha = md.digest().joinToString("") { "%02x".format(it) }
            if (sha != sha256) throw IOException("The downloaded file is damaged (checksum mismatch)")
            if (!part.renameTo(file(dir))) throw IOException("Couldn't save the download")
        } finally {
            conn.disconnect()
            part.delete()
        }
    }
}
