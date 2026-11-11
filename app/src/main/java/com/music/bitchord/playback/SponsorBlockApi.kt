package com.music.bitchord.playback

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class SponsorSegment(
    val category: String,
    val actionType: String,
    val startMs: Long,
    val endMs: Long,
    val uuid: String,
)

object SponsorBlockApi {
    private const val DEFAULT_SERVER_URL = "https://sponsor.ajay.app"

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    private val segmentCache = ConcurrentHashMap<String, List<SponsorSegment>>()

    /** Past this many answers the cache starts over; one listening session never gets near it. */
    private const val MAX_CACHED = 256

    private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")

    /**
     * Whether [id] has the shape of a YouTube video id. Only those are sent to
     * the server: a local file's media id is its path or content URI, which is
     * nobody else's business and matches nothing there anyway.
     */
    fun isYouTubeVideoId(id: String?): Boolean = id != null && VIDEO_ID.matches(id)

    /** An answer depends on which categories were asked for as much as on the video. */
    internal fun cacheKey(videoId: String, categories: List<String>): String =
        videoId + "|" + categories.sorted().joinToString(",")

    /** The skippable segments in a `skipSegments` answer, earliest first. */
    internal fun parseSegments(body: String): List<SponsorSegment> {
        val jsonArray = JSONArray(body)
        val segments = mutableListOf<SponsorSegment>()
        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.optJSONObject(i) ?: continue
            val segmentArray = obj.optJSONArray("segment") ?: continue
            if (segmentArray.length() < 2) continue
            val startMs = (segmentArray.optDouble(0, Double.NaN) * 1000)
            val endMs = (segmentArray.optDouble(1, Double.NaN) * 1000)
            if (startMs.isNaN() || endMs.isNaN() || endMs.toLong() <= startMs.toLong()) continue
            segments.add(
                SponsorSegment(
                    category = obj.optString("category", ""),
                    actionType = obj.optString("actionType", "skip"),
                    startMs = startMs.toLong(),
                    endMs = endMs.toLong(),
                    uuid = obj.optString("UUID", ""),
                ),
            )
        }
        return segments.sortedBy { it.startMs }
    }

    private fun remember(key: String, segments: List<SponsorSegment>) {
        if (segmentCache.size >= MAX_CACHED) segmentCache.clear()
        segmentCache[key] = segments
    }

    suspend fun getSkipSegments(
        videoId: String,
        categories: List<String>,
        serverUrl: String = DEFAULT_SERVER_URL,
    ): Result<List<SponsorSegment>> = withContext(Dispatchers.IO) {
        if (!isYouTubeVideoId(videoId) || categories.isEmpty()) return@withContext Result.success(emptyList())

        val key = cacheKey(videoId, categories)
        segmentCache[key]?.let { return@withContext Result.success(it) }

        val baseUrl = if (serverUrl.isBlank()) DEFAULT_SERVER_URL else serverUrl.trimEnd('/')
        val httpUrl = ("$baseUrl/api/skipSegments")
            .toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("videoID", videoId)
            ?.addQueryParameter("categories", JSONArray(categories).toString())
            ?.build()
            ?: return@withContext Result.failure(Exception("Invalid SponsorBlock API URL"))

        try {
            val request = Request.Builder()
                .url(httpUrl)
                .addHeader("User-Agent", "Plonker/1.0")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                // 404 is the server's "nothing submitted for this video".
                if (response.code == 404) {
                    remember(key, emptyList())
                    return@withContext Result.success(emptyList())
                }
                val body = response.body.string()
                if (!response.isSuccessful || body.isBlank()) {
                    return@withContext Result.failure(Exception("HTTP ${response.code}: ${response.message}"))
                }
                val segments = parseSegments(body)
                remember(key, segments)
                Result.success(segments)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun clearCache() {
        segmentCache.clear()
    }
}
