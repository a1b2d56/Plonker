package com.music.bitchord.data

import android.content.Context
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.ShelfLayout
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * The home page as it last stood, kept on disk between launches.
 *
 * A cold start used to draw a skeleton until the network answered: about a
 * second for YouTube Music's feed. The page the listener saw last time is a
 * better first frame than a skeleton, so it is drawn from here while the fresh
 * feed is fetched, and the fresh feed replaces it when it lands — the same
 * in-place swap a pull to refresh makes.
 *
 * Kept per catalogue (one file each) and per listener: a page read for one
 * account is never drawn for another. The listener is whatever the caller
 * names as the page's owner and is stored only as a hash. A copy older than
 * [MAX_AGE_MS] is not used, because a home page that old is not "last time"
 * any more.
 */
object HomeFeedCache {

    /** What a cached page holds: the shelves in feed order. */
    data class Snapshot(
        val shelves: List<HomeShelf>,
    )

    /** How old a kept page may be and still be drawn. */
    const val MAX_AGE_MS = 24 * 60 * 60 * 1000L

    /**
     * How many shelves are kept. The first screens' worth and then some: the
     * rest arrives with the fresh feed, and the file stays a few tens of
     * kilobytes however far the last session scrolled.
     */
    private const val MAX_SHELVES = 16

    private fun file(baseDir: File, catalogue: String) =
        File(baseDir, "bitchord_home_${catalogue.lowercase()}.json")

    /**
     * The page kept for [catalogue] and [listener], or null when there is none
     * that may be drawn. Reads a file: call it off the main thread.
     */
    fun load(
        context: Context,
        catalogue: String,
        listener: String?,
        now: Long = System.currentTimeMillis(),
    ): Snapshot? = load(context.filesDir, catalogue, listener, now)

    fun load(
        baseDir: File,
        catalogue: String,
        listener: String?,
        now: Long = System.currentTimeMillis(),
    ): Snapshot? {
        val file = file(baseDir, catalogue)
        if (!file.isFile) return null
        return runCatching { decode(file.readText(), listener, now) }.getOrNull()
    }

    /** Keeps [snapshot] for the next launch. Writes a file: call it off the main thread. */
    fun save(
        context: Context,
        catalogue: String,
        listener: String?,
        snapshot: Snapshot,
        now: Long = System.currentTimeMillis(),
    ) = save(context.filesDir, catalogue, listener, snapshot, now)

    fun save(
        baseDir: File,
        catalogue: String,
        listener: String?,
        snapshot: Snapshot,
        now: Long = System.currentTimeMillis(),
    ) {
        if (snapshot.shelves.isEmpty()) return
        // One writer at a time: a load and an overlapping refresh can both
        // save, and they share the temporary file.
        synchronized(this) { write(baseDir, catalogue, listener, snapshot, now) }
    }

    private fun write(baseDir: File, catalogue: String, listener: String?, snapshot: Snapshot, now: Long) {
        runCatching {
            val target = file(baseDir, catalogue)
            // Written aside and moved into place, so a process that dies
            // halfway through cannot leave a half page for the next launch.
            val temp = File(target.parentFile, "${target.name}.tmp")
            temp.writeText(encode(snapshot, listener, now))
            if (!temp.renameTo(target)) {
                target.writeText(temp.readText())
                temp.delete()
            }
        }
    }

    internal fun encode(snapshot: Snapshot, listener: String?, now: Long): String =
        JSONObject()
            .put("savedAt", now)
            .put("listener", listenerKey(listener))
            .put("shelves", JSONArray().apply { snapshot.shelves.take(MAX_SHELVES).forEach { put(it.toJson()) } })
            .toString()

    internal fun decode(text: String, listener: String?, now: Long): Snapshot? {
        val root = JSONObject(text)
        val savedAt = root.optLong("savedAt", 0L)
        if (now - savedAt !in 0 until MAX_AGE_MS) return null
        if (root.optString("listener") != listenerKey(listener)) return null
        val array = root.optJSONArray("shelves") ?: return null
        val shelves = (0 until array.length()).mapNotNull { array.optJSONObject(it)?.toShelf() }
        if (shelves.isEmpty()) return null
        return Snapshot(shelves)
    }

    /** The listener, as something that can be compared but not read back. */
    private fun listenerKey(listener: String?): String =
        MessageDigest.getInstance("SHA-256")
            .digest((listener ?: "guest").toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun HomeShelf.toJson(): JSONObject = JSONObject()
        .put("title", title)
        .put("subtitle", subtitle)
        .putOpt("moreBrowseId", moreBrowseId)
        .putOpt("moreParams", moreParams)
        .put("layout", layout.name)
        .put("items", items.toJson())

    private fun JSONObject.toShelf(): HomeShelf? {
        val title = optString("title").takeIf { it.isNotBlank() } ?: return null
        val items = optJSONArray("items")?.toItems().orEmpty()
        if (items.isEmpty()) return null
        val layout = optString("layout").let { name ->
            runCatching { ShelfLayout.valueOf(name) }.getOrDefault(ShelfLayout.CARDS)
        }
        return HomeShelf(
            title = title,
            items = items,
            subtitle = optString("subtitle"),
            moreBrowseId = optStringOrNull("moreBrowseId"),
            moreParams = optStringOrNull("moreParams"),
            layout = layout,
        )
    }

    private fun List<ShelfItem>.toJson(): JSONArray = JSONArray().also { array ->
        forEach { item ->
            array.put(
                JSONObject()
                    .put("title", item.title)
                    .put("subtitle", item.subtitle)
                    .putOpt("thumbnailUrl", item.thumbnailUrl)
                    .putOpt("videoId", item.videoId)
                    .putOpt("browseId", item.browseId)
                    .put("isVideo", item.isVideo)
            )
        }
    }

    private fun JSONArray.toItems(): List<ShelfItem> = (0 until length()).mapNotNull { index ->
        val item = optJSONObject(index) ?: return@mapNotNull null
        ShelfItem(
            title = item.optString("title"),
            subtitle = item.optString("subtitle"),
            thumbnailUrl = item.optStringOrNull("thumbnailUrl"),
            videoId = item.optStringOrNull("videoId"),
            browseId = item.optStringOrNull("browseId"),
            isVideo = item.optBoolean("isVideo", false),
        )
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }
}
