package com.music.bitchord.data.stats

import android.content.Context
import android.net.Uri
import com.music.bitchord.BuildConfig
import com.music.bitchord.data.OfflinePlaylists
import com.music.bitchord.data.spotify.LocalPlaylistStore
import com.music.bitchord.recognition.RecognitionHistory
import com.music.bitchord.recognition.RecognitionHistoryEntry
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.SearchHistory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The whole of what this app knows about you, as one JSON file you own.
 *
 * Two errands, and they are the same errand: moving to a new phone, and being
 * able to see what is being kept. Everything Replay counts lives only on this
 * device, which is the point of it — and the flip side of that is that a factory
 * reset takes it with no way back, so there has to be a way out.
 *
 * ## What is in it
 *
 * Every preference except credentials (see [AppSettings.exportPrefs]), and every
 * month of listening. Not the audio cache, the downloads or the artwork: those
 * are megabytes that re-fetch themselves, and a backup that is mostly cache is
 * one nobody keeps.
 *
 * ## Typed values rather than a JSON object
 *
 * Preferences come off Android as `Map<String, Any?>` and go back the same way,
 * where the *type* decides which `put` is called. Written as plain JSON, `0.5`
 * comes back a Double and lands in a Float preference as a class-cast crash the
 * first time it is read — weeks later, in a settings screen, with nothing
 * pointing at the import. So each value carries its type and is parsed back into
 * exactly the type it left as, and anything unrecognised is skipped rather than
 * guessed at.
 */
object Backup {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    /** A suggested filename, dated so successive exports don't collide. */
    fun suggestedName(): String =
        "bitchord-backup-${DateTimeFormatter.ofPattern("yyyy-MM-dd").format(
            Instant.now().atZone(ZoneId.systemDefault()),
        )}.json"

    /**
     * Writes a backup to [target], a document the user picked.
     *
     * Through the content resolver rather than a [java.io.File] because the
     * destination is wherever they chose — Drive, a USB stick, a folder this app
     * has no path to and no permission for. The picker grants access to that one
     * document and nothing else, which is the correct amount.
     */
    suspend fun exportTo(context: Context, target: Uri): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val buckets = ListeningStats.exportAll()
            val file = BackupFile(
                versionName = BuildConfig.VERSION_NAME,
                exportedAt = Instant.now().toString(),
                settings = AppSettings.exportPrefs().mapNotNull { (key, value) ->
                    PrefValue.of(value)?.let { key to it }
                }.toMap(),
                listening = buckets,
                localPlaylists = LocalPlaylistStore.exportJson(),
                offlinePlaylists = OfflinePlaylists.exportJson(),
                recognised = RecognitionHistory.exportAll(context).map(Recognised::of),
            )
            val text = json.encodeToString(BackupFile.serializer(), file)
            context.contentResolver.openOutputStream(target, "wt")
                ?.use { it.write(text.toByteArray()) }
                ?: error("Couldn't open that file for writing")
            buckets.size
        }
    }

    /**
     * Reads [source] and replaces this device's settings and listening with it.
     *
     * Validated before anything is written: a file that isn't one of ours, or is
     * from a schema this build can't read, is refused whole. A half-applied
     * import is worse than a refused one — it leaves settings from two devices
     * mixed together with nothing to say which came from where.
     */
    suspend fun importFrom(context: Context, source: Uri): Result<Summary> = withContext(Dispatchers.IO) {
        runCatching {
            val text = context.contentResolver.openInputStream(source)
                ?.use(::readCapped)
                ?: error("Couldn't open that file")
            val file = runCatching { json.decodeFromString(BackupFile.serializer(), text) }
                .getOrElse { error("That doesn't look like a BitChord backup") }
            require(file.app == APP_TAG) { "That backup is from another app" }
            require(file.version <= SCHEMA_VERSION) {
                "That backup was written by a newer version of BitChord"
            }

            // Read before anything is replaced, for the same reason: a
            // playlist document that can't be read refuses the whole file. A
            // backup from before these were carried has none, and leaves what
            // this device holds alone.
            val localPlaylists = file.localPlaylists?.let {
                LocalPlaylistStore.parse(it) ?: error("That backup's playlists can't be read")
            }
            val offlinePlaylists = file.offlinePlaylists?.let {
                OfflinePlaylists.parse(it) ?: error("That backup's playlists can't be read")
            }

            check(ListeningStats.importAll(file.listening)) { "Couldn't write the listening history" }
            AppSettings.importPrefs(file.settings.mapValues { it.value.decoded() })
            // Shares AppSettings' preference file, so it has already been
            // overwritten by the line above — it just doesn't know yet.
            SearchHistory.reload()
            localPlaylists?.let(LocalPlaylistStore::replaceAll)
            offlinePlaylists?.let(OfflinePlaylists::replaceAll)
            file.recognised?.let { songs ->
                RecognitionHistory.replaceAll(context, songs.map(Recognised::entry))
            }
            Summary(
                months = file.listening.size,
                settings = file.settings.size,
                from = file.versionName,
                at = file.exportedAt,
            )
        }
    }

    /**
     * The whole of [input] as text, refusing one too large to be a backup: the
     * file is whatever the picker was pointed at, and reading a video into
     * memory to find that out is a crash rather than an error message.
     */
    private fun readCapped(input: java.io.InputStream): String {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            require(total <= MAX_BACKUP_BYTES) { "That file is too large to be a BitChord backup" }
            out.write(buffer, 0, read)
        }
        return out.toString("UTF-8")
    }

    private const val MAX_BACKUP_BYTES = 64L * 1024 * 1024

    /** What an import turned out to contain, for the line shown afterwards. */
    data class Summary(
        val months: Int,
        val settings: Int,
        val from: String,
        val at: String,
    )

    private const val APP_TAG = "bitchord"

    /**
     * Bump when the shape below stops being readable by an older build. A file
     * from a *newer* schema is refused rather than partially read; one from an
     * older schema is read as-is, since every field has a default.
     */
    private const val SCHEMA_VERSION = 1

    @Serializable
    data class BackupFile(
        val app: String = APP_TAG,
        val version: Int = SCHEMA_VERSION,
        /** Which build wrote it — for the reader, not for any logic here. */
        val versionName: String = "",
        val exportedAt: String = "",
        val settings: Map<String, PrefValue> = emptyMap(),
        val listening: List<StoredBucket> = emptyList(),
        /**
         * The playlists imported from Spotify and the ones made of files on
         * the device, each as the document its own store keeps, and the songs
         * identified. Null in a backup written before these were carried,
         * which an import reads as "nothing to say" rather than "none".
         */
        val localPlaylists: String? = null,
        val offlinePlaylists: String? = null,
        val recognised: List<Recognised>? = null,
    )

    /** One identified song, as [RecognitionHistoryEntry] keeps it. */
    @Serializable
    data class Recognised(
        val trackKey: String,
        val title: String,
        val artist: String,
        val album: String? = null,
        val artworkUrl: String? = null,
        val videoId: String? = null,
        val browseId: String? = null,
        val recognizedAt: Long = 0,
    ) {
        fun entry() = RecognitionHistoryEntry(
            trackKey = trackKey,
            title = title,
            artist = artist,
            album = album,
            artworkUrl = artworkUrl,
            videoId = videoId,
            browseId = browseId,
            recognizedAt = recognizedAt,
        )

        companion object {
            fun of(entry: RecognitionHistoryEntry) = Recognised(
                trackKey = entry.trackKey,
                title = entry.title,
                artist = entry.artist,
                album = entry.album,
                artworkUrl = entry.artworkUrl,
                videoId = entry.videoId,
                browseId = entry.browseId,
                recognizedAt = entry.recognizedAt,
            )
        }
    }

    /** One preference, with the type it has to be restored as. */
    @Serializable
    data class PrefValue(
        val type: String,
        val value: String? = null,
        val values: List<String> = emptyList(),
    ) {
        fun decoded(): Any? = when (type) {
            BOOLEAN -> value?.toBooleanStrictOrNull()
            INT -> value?.toIntOrNull()
            LONG -> value?.toLongOrNull()
            FLOAT -> value?.toFloatOrNull()
            STRING -> value
            STRING_SET -> values.toSet()
            else -> null
        }

        companion object {
            /**
             * Numbers go out as strings on purpose. JSON has one number type and
             * every parser picks its own Kotlin type back off it, which is
             * exactly the ambiguity this whole class exists to remove.
             */
            fun of(value: Any?): PrefValue? = when (value) {
                is Boolean -> PrefValue(BOOLEAN, value.toString())
                is Int -> PrefValue(INT, value.toString())
                is Long -> PrefValue(LONG, value.toString())
                is Float -> PrefValue(FLOAT, value.toString())
                is String -> PrefValue(STRING, value)
                is Set<*> -> PrefValue(STRING_SET, values = value.filterIsInstance<String>())
                else -> null
            }

            private const val BOOLEAN = "bool"
            private const val INT = "int"
            private const val LONG = "long"
            private const val FLOAT = "float"
            private const val STRING = "string"
            private const val STRING_SET = "stringSet"
        }
    }
}
