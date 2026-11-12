package com.music.bitchord.recognition

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * One song the listener identified, as it is kept.
 *
 * [trackKey] is what makes two identifications the same song: Shazam's own
 * track id where it gave one, and the lowercased title and artist where it
 * did not. It is unique, so identifying a song again replaces its row and
 * brings it back to the top instead of listing it twice.
 *
 * [videoId] is the YouTube Music video Shazam linked, when it linked one;
 * a row with it plays directly, a row without it is found by search.
 * [browseId] is kept beside it for the album or artist page, and is null
 * until something supplies one: Shazam's answer carries no browse id.
 */
@Entity(
    tableName = "recognition_history",
    indices = [Index(value = ["track_key"], unique = true)],
)
data class RecognitionHistoryEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "track_key") val trackKey: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    @ColumnInfo(name = "artwork_url") val artworkUrl: String? = null,
    @ColumnInfo(name = "video_id") val videoId: String? = null,
    @ColumnInfo(name = "browse_id") val browseId: String? = null,
    @ColumnInfo(name = "recognized_at") val recognizedAt: Long,
) {
    companion object {
        /** The row a successful identification is saved as. */
        fun from(result: RecognitionResult, nowMs: Long): RecognitionHistoryEntry =
            RecognitionHistoryEntry(
                trackKey = trackKeyOf(result.trackId, result.title, result.artist),
                title = result.title,
                artist = result.artist,
                album = result.album?.takeIf { it.isNotBlank() },
                artworkUrl = (result.coverArtHqUrl ?: result.coverArtUrl)?.takeIf { it.isNotBlank() },
                videoId = result.youtubeVideoId?.takeIf { it.isNotBlank() },
                recognizedAt = nowMs,
            )

        internal fun trackKeyOf(trackId: String, title: String, artist: String): String {
            val id = trackId.trim()
            if (id.isNotEmpty()) return "shazam:$id"
            return "name:" + title.trim().lowercase() + "|" + artist.trim().lowercase()
        }
    }
}

@Dao
interface RecognitionHistoryDao {
    /** Newest first. */
    @Query("SELECT * FROM recognition_history ORDER BY recognized_at DESC, id DESC")
    fun observeAll(): Flow<List<RecognitionHistoryEntry>>

    /** Replaces the row with the same [RecognitionHistoryEntry.trackKey]. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: RecognitionHistoryEntry): Long

    /** Newest first, once. */
    @Query("SELECT * FROM recognition_history ORDER BY recognized_at DESC, id DESC")
    suspend fun all(): List<RecognitionHistoryEntry>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<RecognitionHistoryEntry>)

    @Query("DELETE FROM recognition_history WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM recognition_history")
    suspend fun clear()

    /** Keeps the newest [keep] rows and drops the rest. */
    @Query(
        "DELETE FROM recognition_history WHERE id NOT IN " +
            "(SELECT id FROM recognition_history ORDER BY recognized_at DESC, id DESC LIMIT :keep)",
    )
    suspend fun trimTo(keep: Int)
}

@Database(entities = [RecognitionHistoryEntry::class], version = 1, exportSchema = false)
abstract class RecognitionDatabase : RoomDatabase() {
    abstract fun history(): RecognitionHistoryDao
}

/**
 * The identified-songs list: one small database, opened the first time it is
 * asked for and kept for the life of the process.
 */
object RecognitionHistory {
    /** More than anyone scrolls back through, and small enough to load whole. */
    const val MAX_ENTRIES = 200

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var database: RecognitionDatabase? = null

    private fun database(context: Context): RecognitionDatabase =
        database ?: synchronized(this) {
            database ?: Room.databaseBuilder(
                context.applicationContext,
                RecognitionDatabase::class.java,
                "recognition_history.db",
            ).build().also { database = it }
        }

    private fun dao(context: Context): RecognitionHistoryDao = database(context).history()

    /** Every entry, newest first, for a backup. */
    suspend fun exportAll(context: Context): List<RecognitionHistoryEntry> =
        runCatching { dao(context).all() }.getOrDefault(emptyList())

    /** Replaces the whole list with [entries], for a restore: all of it or none of it. */
    suspend fun replaceAll(context: Context, entries: List<RecognitionHistoryEntry>) {
        val db = database(context)
        db.withTransaction {
            db.history().clear()
            // Row ids belong to the device that wrote them; the track key is
            // what identifies a song.
            db.history().insertAll(
                entries.sortedByDescending { it.recognizedAt }.take(MAX_ENTRIES).map { it.copy(id = 0) },
            )
        }
    }

    fun entries(context: Context): Flow<List<RecognitionHistoryEntry>> = dao(context).observeAll()

    /** Saves [result] off the caller's thread; a failure to save is not the listener's problem. */
    fun save(context: Context, result: RecognitionResult, nowMs: Long = System.currentTimeMillis()) {
        val appContext = context.applicationContext
        scope.launch {
            runCatching {
                val dao = dao(appContext)
                dao.insert(RecognitionHistoryEntry.from(result, nowMs))
                dao.trimTo(MAX_ENTRIES)
            }
        }
    }

    fun remove(context: Context, id: Long) {
        val appContext = context.applicationContext
        scope.launch { runCatching { dao(appContext).delete(id) } }
    }

    fun clear(context: Context) {
        val appContext = context.applicationContext
        scope.launch { runCatching { dao(appContext).clear() } }
    }
}
