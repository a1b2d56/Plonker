package com.music.bitchord.data.tidal

import com.music.bitchord.data.Http
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.TrackMatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Request
import java.util.Base64

/** Prefix for a Tidal playlist in the detail navigation stack. */
const val TIDAL_PAGE_PREFIX = "tidal:playlist:"
const val TIDAL_PENDING_PREFIX = "tidal_pending:"
const val TIDAL_MISSING_PREFIX = "tidal_missing:"

data class TidalPlaylist(
    val id: String,
    val name: String,
    val owner: String? = null,
    val imageUrl: String? = null,
    val trackCount: Int? = null,
    val isLiked: Boolean = false,
)

data class TidalTrack(
    val id: String,
    val title: String,
    val artist: String,
    val album: String?,
    val durationMs: Int,
    val imageUrl: String?,
)

data class TidalSession(
    val userId: String,
    val countryCode: String,
)

object TidalLibrary {

    private const val API = "https://api.tidal.com/v1"
    const val LIKED_ID = "tidal:favorites"
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Verifies a captured bearer token against Tidal's session endpoint or JWT claims. */
    suspend fun verifySession(token: String): TidalSession? = withContext(Dispatchers.IO) {
        val cleanToken = token.removePrefix("Bearer ").trim()
        if (cleanToken.length < 20) return@withContext null

        val request = Request.Builder()
            .url("$API/sessions")
            .header("Authorization", "Bearer $cleanToken")
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .build()

        runCatching {
            Http.client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body.string()
                    val obj = json.parseToJsonElement(body).jsonObject
                    val userId = obj["userId"]?.jsonPrimitive?.contentOrNull
                        ?: obj["userId"]?.jsonPrimitive?.longOrNull?.toString()
                    val country = obj["countryCode"]?.jsonPrimitive?.contentOrNull
                    if (userId != null && country != null) {
                        return@withContext TidalSession(userId, country)
                    }
                }
            }
        }

        // Fallback: parse JWT claims if /sessions was blocked or expired
        jwtUser(cleanToken)?.let { (userId, country) ->
            val probeReq = Request.Builder()
                .url("$API/users/$userId/playlists?limit=1&countryCode=$country")
                .header("Authorization", "Bearer $cleanToken")
                .header("User-Agent", USER_AGENT)
                .build()
            val probeOk = runCatching {
                Http.client.newCall(probeReq).execute().use { it.isSuccessful }
            }.getOrDefault(false)
            if (probeOk) {
                return@withContext TidalSession(userId, country)
            }
        }

        null
    }

    private fun jwtUser(token: String): Pair<String, String>? = runCatching {
        val payload = token.split('.').getOrNull(1) ?: return null
        val decoded = String(Base64.getUrlDecoder().decode(payload))
        val claims = json.parseToJsonElement(decoded).jsonObject
        val userId = claims["uid"]?.jsonPrimitive?.contentOrNull
            ?: claims["userId"]?.jsonPrimitive?.contentOrNull
            ?: claims["uid"]?.jsonPrimitive?.longOrNull?.toString()
            ?: return null
        val country = claims["cc"]?.jsonPrimitive?.contentOrNull
            ?: claims["countryCode"]?.jsonPrimitive?.contentOrNull
            ?: "US"
        userId to country
    }.getOrNull()

    /** Fetches all playlists (including favorite tracks) for the signed-in Tidal account. */
    suspend fun playlists(token: String, userId: String, countryCode: String): List<TidalPlaylist> = withContext(Dispatchers.IO) {
        val cleanToken = token.removePrefix("Bearer ").trim()
        val authHeader = "Bearer $cleanToken"

        // 1. Liked songs total
        val likedTotal = runCatching {
            val req = Request.Builder()
                .url("$API/users/$userId/favorites/tracks?limit=1&countryCode=$countryCode")
                .header("Authorization", authHeader)
                .header("User-Agent", USER_AGENT)
                .build()
            Http.client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) null
                else json.parseToJsonElement(resp.body.string()).jsonObject["totalNumberOfItems"]?.jsonPrimitive?.longOrNull?.toInt()
            }
        }.getOrNull()

        val result = mutableListOf(
            TidalPlaylist(
                id = LIKED_ID,
                name = "My Tracks",
                owner = "Tidal Favorites",
                imageUrl = null, // Will use first liked track's cover
                trackCount = likedTotal,
                isLiked = true,
            )
        )

        val seen = HashSet<String>()

        // 2. User created playlists
        forEachPage("$API/users/$userId/playlists?countryCode=$countryCode", authHeader, pageSize = 50) { item ->
            item.toPlaylist()?.takeIf { seen.add(it.id) }?.let { result += it }
        }

        // 3. Saved / Favorited playlists
        forEachPage("$API/users/$userId/favorites/playlists?countryCode=$countryCode", authHeader, pageSize = 50) { entry ->
            entry["item"]?.let { if (it is JsonObject) it else null }?.toPlaylist()
                ?.takeIf { seen.add(it.id) }
                ?.let { result += it }
        }

        result
    }

    /** Fetches all tracks of a Tidal playlist or favorites collection with progressive paging. */
    suspend fun tracks(
        token: String,
        playlistId: String,
        userId: String,
        countryCode: String,
        onPage: (List<TidalTrack>) -> Unit = {},
    ): List<TidalTrack> = withContext(Dispatchers.IO) {
        val cleanToken = token.removePrefix("Bearer ").trim()
        val authHeader = "Bearer $cleanToken"
        val isLiked = playlistId == LIKED_ID

        val url = if (isLiked) {
            "$API/users/$userId/favorites/tracks?countryCode=$countryCode&order=DATE&orderDirection=DESC"
        } else {
            "$API/playlists/$playlistId/items?countryCode=$countryCode"
        }

        val collected = mutableListOf<TidalTrack>()

        forEachPage(url, authHeader, pageSize = 100) { entry ->
            val type = entry["type"]?.jsonPrimitive?.contentOrNull
            if (type != null && type != "track") return@forEachPage

            val track = (entry["item"] as? JsonObject) ?: entry
            val id = track["id"]?.jsonPrimitive?.contentOrNull
                ?: track["id"]?.jsonPrimitive?.longOrNull?.toString()
                ?: return@forEachPage
            val title = track["title"]?.jsonPrimitive?.contentOrNull ?: return@forEachPage
            val version = track["version"]?.jsonPrimitive?.contentOrNull
            val fullTitle = if (version != null && !title.contains(version, ignoreCase = true)) {
                "$title ($version)"
            } else {
                title
            }

            val artists = (track["artists"] as? JsonArray)?.mapNotNull {
                (it as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull
            } ?: listOfNotNull(
                (track["artist"] as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull
            )
            val artistStr = artists.joinToString(", ").ifBlank { "Unknown Artist" }

            val albumObj = track["album"] as? JsonObject
            val albumTitle = albumObj?.get("title")?.jsonPrimitive?.contentOrNull
            val durationSec = track["duration"]?.jsonPrimitive?.longOrNull ?: 0L
            val durationMs = (durationSec * 1000).toInt()

            val coverId = albumObj?.get("cover")?.jsonPrimitive?.contentOrNull
            val coverUrl = coverId?.let { "https://resources.tidal.com/images/${it.replace('-', '/')}/640x640.jpg" }

            val t = TidalTrack(
                id = id,
                title = fullTitle,
                artist = artistStr,
                album = albumTitle,
                durationMs = durationMs,
                imageUrl = coverUrl,
            )
            collected += t
            if (collected.size % 25 == 0) {
                onPage(collected.toList())
            }
        }
        onPage(collected.toList())
        collected
    }

    /** Resolves a playlist's full cover image. */
    suspend fun cover(playlistId: String, token: String, countryCode: String): String? = withContext(Dispatchers.IO) {
        if (playlistId == LIKED_ID) return@withContext null
        val cleanToken = token.removePrefix("Bearer ").trim()
        val req = Request.Builder()
            .url("$API/playlists/$playlistId?countryCode=$countryCode")
            .header("Authorization", "Bearer $cleanToken")
            .header("User-Agent", USER_AGENT)
            .build()
        runCatching {
            Http.client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val obj = json.parseToJsonElement(resp.body.string()).jsonObject
                val square = obj["squareImage"]?.jsonPrimitive?.contentOrNull
                if (square != null) return@withContext "https://resources.tidal.com/images/${square.replace('-', '/')}/640x640.jpg"
                val image = obj["image"]?.jsonPrimitive?.contentOrNull
                if (image != null) return@withContext "https://resources.tidal.com/images/${image.replace('-', '/')}/750x500.jpg"
                null
            }
        }.getOrNull()
    }

    /** Matches a Tidal track to an InnerTube/YouTube Music song for native playback. */
    suspend fun matchTrack(track: TidalTrack): Song? {
        val query = listOf(track.title, track.artist).filter { it.isNotBlank() }.joinToString(" ")
        val candidates = YtMusicRepository.search(query, SearchFilter.SONGS).getOrNull()
            ?.filterIsInstance<SearchResult.Track>()
            ?.map { it.song }
            .orEmpty()
        return TrackMatcher.best(
            candidates,
            TrackMatcher.Target(
                title = track.title,
                artist = track.artist,
                durationSec = track.durationMs.takeIf { it > 0 }?.div(1000),
                album = track.album,
            ),
        ) ?: candidates.firstOrNull()
    }

    private fun JsonObject.toPlaylist(): TidalPlaylist? {
        val id = this["uuid"]?.jsonPrimitive?.contentOrNull ?: return null
        val title = this["title"]?.jsonPrimitive?.contentOrNull ?: "Untitled Playlist"
        val count = this["numberOfTracks"]?.jsonPrimitive?.longOrNull?.toInt()

        val square = this["squareImage"]?.jsonPrimitive?.contentOrNull
        val image = this["image"]?.jsonPrimitive?.contentOrNull
        val cover = square?.let { "https://resources.tidal.com/images/${it.replace('-', '/')}/640x640.jpg" }
            ?: image?.let { "https://resources.tidal.com/images/${it.replace('-', '/')}/750x500.jpg" }

        val creator = (this["creator"] as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull

        return TidalPlaylist(
            id = id,
            name = title,
            owner = creator,
            imageUrl = cover,
            trackCount = count,
            isLiked = false,
        )
    }

    private suspend fun forEachPage(
        baseUrl: String,
        authHeader: String,
        pageSize: Int,
        onItem: (JsonObject) -> Unit,
    ) {
        var offset = 0
        var pages = 0
        val delimiter = if (baseUrl.contains("?")) "&" else "?"
        while (pages++ < 400) {
            val url = "$baseUrl${delimiter}limit=$pageSize&offset=$offset"
            val req = Request.Builder()
                .url(url)
                .header("Authorization", authHeader)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .build()

            val body = runCatching {
                Http.client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) resp.body.string() else null
                }
            }.getOrNull() ?: break

            val page = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: break
            val items = page["items"] as? JsonArray ?: break
            items.forEach { (it as? JsonObject)?.let(onItem) }
            offset += items.size
            val total = page["totalNumberOfItems"]?.jsonPrimitive?.longOrNull
            if (items.isEmpty() || (total != null && offset >= total)) break
        }
    }
}
