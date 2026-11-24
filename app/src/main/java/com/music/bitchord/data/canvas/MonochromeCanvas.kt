package com.music.bitchord.data.canvas

import com.music.bitchord.data.DebugLog as Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder

/**
 * Animated video cover canvas provider via Monochrome's distributed API instances.
 * Requires zero accounts, tokens, or subscriptions.
 */
object MonochromeCanvas {

    private const val TAG = "MonochromeCanvas"

    private val INSTANCES = listOf(
        "https://eu-central.monochrome.tf",
        "https://us-west.monochrome.tf",
        "https://arran.monochrome.tf",
        "https://api.monochrome.tf",
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun search(title: String, artist: String, album: String?): CanvasArtwork? {
        val query = if (album.isNullOrBlank()) "$artist - $title" else "$artist - $title - $album"
        val encoded = runCatching { URLEncoder.encode(query, "UTF-8") }.getOrDefault("")
        if (encoded.isBlank()) return null

        for (base in INSTANCES) {
            val url = "$base/search/?s=$encoded"
            val body = canvasGet(url, mapOf("User-Agent" to CANVAS_UA, "Accept" to "application/json"))
                ?: continue
            val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
                ?: continue

            val tracksSection = root["tracks"]?.jsonObject ?: continue
            val items = tracksSection["items"]?.jsonArray ?: continue

            for (item in items) {
                val track = item as? JsonObject ?: continue
                val trackTitle = track["title"]?.jsonPrimitive?.contentOrNull ?: continue

                val artists = track["artists"]?.jsonArray
                    ?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull }
                    .orEmpty().ifEmpty {
                        listOfNotNull(track["artist"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull)
                    }

                if (!isMatch(trackTitle, artists, title, artist)) continue

                val albumObj = track["album"]?.jsonObject
                val videoCover = track["videoCover"]?.jsonPrimitive?.contentOrNull
                    ?: albumObj?.get("videoCover")?.jsonPrimitive?.contentOrNull
                if (videoCover.isNullOrBlank()) continue
                val videoUrl = coverUrl(videoCover) ?: continue

                Log.d(TAG, "video cover from Monochrome for '$trackTitle' by ${artists.joinToString()}")
                return CanvasArtwork(
                    url = videoUrl,
                    title = trackTitle,
                    artist = artists.joinToString(", ").ifBlank { null },
                    album = albumObj?.get("title")?.jsonPrimitive?.contentOrNull ?: album,
                )
            }
        }
        return null
    }

    fun searchAlbum(album: String, artist: String): CanvasArtwork? {
        val query = "$artist - $album"
        val encoded = runCatching { URLEncoder.encode(query, "UTF-8") }.getOrDefault("")
        if (encoded.isBlank()) return null

        for (base in INSTANCES) {
            val url = "$base/search/?s=$encoded"
            val body = canvasGet(url, mapOf("User-Agent" to CANVAS_UA, "Accept" to "application/json"))
                ?: continue
            val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
                ?: continue

            val albumsSection = root["albums"]?.jsonObject ?: continue
            val items = albumsSection["items"]?.jsonArray ?: continue

            for (item in items) {
                val record = item as? JsonObject ?: continue
                val recordTitle = record["title"]?.jsonPrimitive?.contentOrNull ?: continue
                val artists = record["artists"]?.jsonArray
                    ?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull }
                    .orEmpty()

                if (!isMatch(recordTitle, artists, album, artist)) continue

                val videoCover = record["videoCover"]?.jsonPrimitive?.contentOrNull
                if (videoCover.isNullOrBlank()) continue
                val videoUrl = coverUrl(videoCover) ?: continue

                Log.d(TAG, "album video cover from Monochrome for '$recordTitle' by ${artists.joinToString()}")
                return CanvasArtwork(
                    url = videoUrl,
                    title = recordTitle,
                    artist = artists.joinToString(", ").ifBlank { null },
                    album = recordTitle,
                )
            }
        }
        return null
    }

    private fun isMatch(
        gotName: String,
        gotArtists: List<String>,
        wantName: String,
        wantArtist: String,
    ): Boolean {
        if (gotName.normalizeForMatch() != wantName.normalizeForMatch()) return false
        val wanted = splitArtists(wantArtist)
        val credited = gotArtists.map { it.normalizeForMatch() }.filter { it.isNotBlank() }
        if (wanted.isEmpty() || credited.isEmpty()) return false
        return wanted.all { want -> credited.any { it == want } }
    }

    internal fun coverUrl(id: String): String? {
        if (id.startsWith("http://") || id.startsWith("https://")) return id
        val parts = id.split("-")
        if (parts.size != 5) return null
        return "https://resources.tidal.com/videos/${parts.joinToString("/")}/1280x1280.mp4"
    }
}
