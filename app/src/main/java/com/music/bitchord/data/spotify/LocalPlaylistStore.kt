package com.music.bitchord.data.spotify

import android.content.Context
import android.content.SharedPreferences
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

data class LocalPlaylist(
    val id: String,
    val title: String,
    val songs: List<Song>,
) {
    val browseId: String get() = "local:playlist:$id"
}

object LocalPlaylistStore {
    private const val PREF_NAME = "bitchord_local_playlists"
    private const val KEY_PLAYLISTS = "playlists_json"
    private const val KEY_UNREADABLE = "playlists_json_unreadable"
    private val lock = Any()

    private lateinit var prefs: SharedPreferences

    private val _playlists = MutableStateFlow<List<LocalPlaylist>>(emptyList())
    val playlists = _playlists.asStateFlow()

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        load()
    }

    private fun load() {
        if (!this::prefs.isInitialized) return
        val raw = prefs.getString(KEY_PLAYLISTS, null) ?: return
        val parsed = parse(raw)
        if (parsed == null) {
            // The next save writes over this document, so what could not be
            // read is set aside first rather than lost to it.
            prefs.edit().putString(KEY_UNREADABLE, raw).apply()
            return
        }
        _playlists.value = parsed
    }

    /**
     * The playlists in [raw], or null when it is not a list at all. One entry
     * that can't be read is left out and the others kept.
     */
    internal fun parse(raw: String): List<LocalPlaylist>? {
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return null
        return (0 until array.length()).mapNotNull { i ->
            runCatching {
                val obj = array.getJSONObject(i)
                val songsArray = obj.optJSONArray("songs") ?: JSONArray()
                val songs = (0 until songsArray.length()).mapNotNull { j ->
                    val sObj = songsArray.optJSONObject(j) ?: return@mapNotNull null
                    Song(
                        videoId = sObj.optString("videoId", ""),
                        title = sObj.optString("title", ""),
                        artist = sObj.optString("artist", ""),
                        thumbnailUrl = sObj.text("thumbnailUrl"),
                        durationText = sObj.text("durationText"),
                        artistId = sObj.text("artistId"),
                        albumId = sObj.text("albumId"),
                        albumName = sObj.text("albumName"),
                        isVideo = sObj.optBoolean("isVideo", false),
                    )
                }
                LocalPlaylist(obj.getString("id"), obj.getString("title"), songs)
            }.getOrNull()
        }
    }

    /** An optional field: absent, JSON null and the text "null" an old build wrote are all absent. */
    private fun JSONObject.text(name: String): String? =
        if (isNull(name)) null else optString(name, "").takeIf { it.isNotBlank() && it != "null" }

    /**
     * Every change reads the list, derives the next one and writes it; done
     * under one lock so two imports finishing together both land.
     */
    private inline fun mutate(change: (List<LocalPlaylist>) -> List<LocalPlaylist>) {
        synchronized(lock) {
            val updated = change(_playlists.value)
            _playlists.value = updated
            persist(updated)
        }
    }

    /** Every playlist as one document, for a backup. */
    fun exportJson(): String = encode(_playlists.value)

    /** Replaces every playlist with [playlists], for a restore. */
    fun replaceAll(playlists: List<LocalPlaylist>) = mutate { playlists }

    fun savePlaylist(title: String, songs: List<Song>): LocalPlaylist {
        lateinit var playlist: LocalPlaylist
        mutate { current ->
            // The id is the clock; two saves in one millisecond must not share it.
            var stamp = System.currentTimeMillis()
            while (current.any { it.id == "sp_local_$stamp" }) stamp++
            playlist = LocalPlaylist(id = "sp_local_$stamp", title = title, songs = songs)
            listOf(playlist) + current
        }
        return playlist
    }

    fun deletePlaylist(id: String) = mutate { current ->
        current.filterNot { it.id == id || it.browseId == id || it.id == id.removePrefix("local:playlist:") }
    }

    fun renamePlaylist(id: String, newTitle: String) {
        val cleanId = id.removePrefix("local:playlist:").removePrefix("VL")
        mutate { current ->
            current.map {
                if (it.id == cleanId || it.id == id || it.browseId == id) it.copy(title = newTitle) else it
            }
        }
    }

    fun getPlaylist(id: String): LocalPlaylist? {
        val cleanId = id.removePrefix("local:playlist:").removePrefix("VL")
        return _playlists.value.firstOrNull { it.id == cleanId || it.id == id || it.browseId == id }
    }

    private fun persist(list: List<LocalPlaylist>) {
        if (!this::prefs.isInitialized) return
        runCatching { prefs.edit().putString(KEY_PLAYLISTS, encode(list)).apply() }
    }

    /**
     * Exports a local playlist in standard Extended M3U (#EXTM3U) format.
     */
    fun exportM3u(id: String): String? {
        val playlist = getPlaylist(id) ?: return null
        val sb = StringBuilder()
        sb.appendLine("#EXTM3U")
        sb.appendLine("#PLAYLIST:${playlist.title}")
        for (song in playlist.songs) {
            val durationSec = song.durationText?.let { parseDurationSeconds(it) } ?: -1
            val trackInfo = if (song.artist.isNotBlank()) "${song.artist} - ${song.title}" else song.title
            sb.appendLine("#EXTINF:$durationSec,$trackInfo")
            val targetUri = when {
                song.videoId.startsWith("webdav:") || song.videoId.startsWith("http") ||
                    song.videoId.startsWith("content://") || song.videoId.startsWith("file://") -> song.videoId
                else -> "https://music.youtube.com/watch?v=${song.videoId}"
            }
            sb.appendLine(targetUri)
        }
        return sb.toString()
    }

    /**
     * Parses and imports an Extended M3U / M3U8 string into a new local playlist.
     */
    fun importM3u(title: String, m3uContent: String): LocalPlaylist {
        val songs = parseM3uSongs(m3uContent)
        return savePlaylist(title, songs)
    }

    internal fun parseM3uSongs(m3uContent: String): List<Song> {
        val lines = m3uContent.lines()
        val songs = mutableListOf<Song>()
        var currentArtist = ""
        var currentTitle = ""
        var currentDurationText: String? = null

        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#PLAYLIST:") || line.startsWith("#EXT-X-")) continue
            if (line.startsWith("#EXTINF:")) {
                val info = line.removePrefix("#EXTINF:").trim()
                val commaIndex = info.indexOf(',')
                val secStr = if (commaIndex != -1) info.substring(0, commaIndex).trim() else ""
                val meta = if (commaIndex != -1) info.substring(commaIndex + 1).trim() else info
                val sec = secStr.toIntOrNull() ?: -1
                currentDurationText = if (sec > 0) formatDurationSeconds(sec) else null

                if (meta.contains(" - ")) {
                    currentArtist = meta.substringBefore(" - ").trim()
                    currentTitle = meta.substringAfter(" - ").trim()
                } else {
                    currentArtist = ""
                    currentTitle = meta
                }
            } else if (!line.startsWith("#")) {
                val videoId = parseTrackIdentifier(line)
                val title = currentTitle.ifBlank { line.substringAfterLast('/').substringBeforeLast('.') }
                songs.add(
                    Song(
                        videoId = videoId,
                        title = title,
                        artist = currentArtist,
                        thumbnailUrl = null,
                        durationText = currentDurationText,
                    ),
                )
                currentArtist = ""
                currentTitle = ""
                currentDurationText = null
            }
        }
        return songs
    }

    private fun parseTrackIdentifier(uri: String): String {
        val trimmed = uri.trim()
        if (trimmed.startsWith("https://music.youtube.com/watch?v=")) {
            return trimmed.substringAfter("watch?v=").substringBefore('&')
        }
        if (trimmed.startsWith("https://www.youtube.com/watch?v=") || trimmed.startsWith("https://youtube.com/watch?v=")) {
            return trimmed.substringAfter("watch?v=").substringBefore('&')
        }
        if (trimmed.startsWith("https://youtu.be/")) {
            return trimmed.substringAfter("youtu.be/").substringBefore('?')
        }
        return trimmed
    }

    private fun parseDurationSeconds(text: String): Int {
        val parts = text.split(':')
        return when (parts.size) {
            2 -> (parts[0].toIntOrNull() ?: 0) * 60 + (parts[1].toIntOrNull() ?: 0)
            3 -> (parts[0].toIntOrNull() ?: 0) * 3600 + (parts[1].toIntOrNull() ?: 0) * 60 + (parts[2].toIntOrNull() ?: 0)
            else -> text.toIntOrNull() ?: -1
        }
    }

    private fun formatDurationSeconds(sec: Int): String {
        val m = sec / 60
        val s = sec % 60
        return "%d:%02d".format(Locale.ROOT, m, s)
    }

    internal fun encode(list: List<LocalPlaylist>): String {
        val jsonArray = JSONArray()
        for (playlist in list) {
            val pObj = JSONObject()
            pObj.put("id", playlist.id)
            pObj.put("title", playlist.title)
            val songsArray = JSONArray()
            for (song in playlist.songs) {
                val sObj = JSONObject()
                sObj.put("videoId", song.videoId)
                sObj.put("title", song.title)
                sObj.put("artist", song.artist)
                sObj.put("thumbnailUrl", song.thumbnailUrl)
                sObj.put("durationText", song.durationText)
                sObj.put("artistId", song.artistId)
                sObj.put("albumId", song.albumId)
                sObj.put("albumName", song.albumName)
                sObj.put("isVideo", song.isVideo)
                songsArray.put(sObj)
            }
            pObj.put("songs", songsArray)
            jsonArray.put(pObj)
        }
        return jsonArray.toString()
    }
}
