package me.masonasons.fastplay.files

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.masonasons.fastplay.engine.Engine
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.nio.charset.Charset

/** Reading playlist files (M3U, M3U8, PLS) as text. */
object PlaylistText {
    fun isPlaylistName(name: String) = name.substringAfterLast('.', "").lowercase() in setOf("m3u", "m3u8", "pls")

    /** An M3U8 that is a stream in pieces (HLS), which FFmpeg plays as one thing. */
    fun isHls(text: String) = text.contains("#EXT-X-")

    /**
     * The entries written in a playlist: an M3U's lines that are not comments, or a
     * PLS's File1=, File2=... in order.
     */
    fun entries(text: String, isPls: Boolean): List<String> {
        val lines = text.lines().map { it.trim() }
        if (isPls) {
            val numbered = mutableListOf<Pair<Int, String>>()
            for (line in lines) {
                val equals = line.indexOf('=')
                if (!line.lowercase().startsWith("file") || equals < 0) continue
                val number = line.substring(4, equals).toIntOrNull() ?: 0
                val value = line.substring(equals + 1).trim()
                if (value.isNotEmpty()) numbered.add(number to value)
            }
            return numbered.sortedBy { it.first }.map { it.second }
        }
        return lines.filter { it.isNotEmpty() && !it.startsWith("#") }.map { it.removePrefix("﻿") }
    }

    /**
     * Fetches a playlist at an address and gives its entries as full addresses, in
     * order; null if it is not a playlist (an HLS stream, or not a playlist's name),
     * or could not be read, in which case the address is for playing as it is.
     */
    suspend fun entries(atAddress: String): List<String>? = withContext(Dispatchers.IO) {
        val url = try {
            URL(atAddress)
        } catch (e: Exception) {
            return@withContext null
        }
        val last = url.path.substringAfterLast('/')
        if (!isPlaylistName(last)) return@withContext null
        val text = try {
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 20_000
            connection.readTimeout = 20_000
            val bytes = connection.inputStream.use { it.readBytes() }
            connection.disconnect()
            decode(bytes)
        } catch (e: Exception) {
            return@withContext null
        }
        if (isHls(text)) return@withContext null
        val items = entries(text, last.lowercase().endsWith(".pls"))
        val full = items.mapNotNull { item ->
            if (item.contains("://")) item
            else try {
                // Relative to the playlist, as a web page's links are
                URI(url.toString()).resolve(URI(null, null, item, null)).toString()
            } catch (e: Exception) {
                null
            }
        }
        full.ifEmpty { null }
    }

    /** Text as UTF-8, or Latin-1 where it is not. */
    fun decode(bytes: ByteArray): String {
        val decoder = Charsets.UTF_8.newDecoder()
        return try {
            decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        } catch (e: Exception) {
            String(bytes, Charset.forName("ISO-8859-1"))
        }
    }

    /** Plays an address: a playlist's entries as the playlist, anything else as it is. */
    suspend fun play(address: String) {
        val items = entries(atAddress = address)
        if (items != null) {
            val names = items.map { item ->
                try {
                    URLDecoder.decode(URL(item).path.substringAfterLast('/'), "UTF-8").ifEmpty { item }
                } catch (e: Exception) {
                    item
                }
            }
            Engine.playUrls(items, names, 0)
        } else {
            Engine.playUrl(address)
        }
    }
}
