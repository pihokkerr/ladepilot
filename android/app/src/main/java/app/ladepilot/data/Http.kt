package app.ladepilot.data

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

object Http {
    private const val UA = "Ladepilot/1.0 (personal Android app)"

    private fun open(url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 60_000
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Accept", "application/json")
        val code = c.responseCode
        if (code !in 200..299) {
            c.disconnect()
            throw IOException("HTTP $code")
        }
        return c
    }

    fun get(url: String): String {
        val c = open(url)
        try {
            return c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    /** Downloads to a temp file first so a failed download never destroys the cache. */
    fun download(url: String, target: File) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        val c = open(url)
        try {
            c.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
        } finally {
            c.disconnect()
        }
        if (tmp.length() < 20) throw IOException("empty response")
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }
}
