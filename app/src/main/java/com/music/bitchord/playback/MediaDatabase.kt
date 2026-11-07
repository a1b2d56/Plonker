package com.music.bitchord.playback

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.DatabaseProvider
import androidx.media3.database.StandaloneDatabaseProvider

/**
 * The process's one Media3 [DatabaseProvider], for every cache that keeps its
 * index in SQLite: the song cache ([AudioCache]) and the Canvas clip cache.
 *
 * Every [StandaloneDatabaseProvider] opens the same database file
 * (`exoplayer_internal.db`) through its own `SQLiteOpenHelper`, so two of them
 * are two connections writing one file. Each cache keeps its own tables in
 * it, but SQLite locks the file, not the table: a song's index committing on
 * a streaming thread while a Canvas clip's commits on another can be refused
 * with "database is locked", which reaches the cache as a failed write in the
 * middle of a stream. Media3 asks for a single instance per app for this
 * reason. One helper serialises those writes on one connection instead.
 */
@UnstableApi
object MediaDatabase {
    @Volatile
    private var instance: DatabaseProvider? = null

    fun provider(context: Context): DatabaseProvider =
        instance ?: synchronized(this) {
            instance ?: StandaloneDatabaseProvider(context.applicationContext).also { instance = it }
        }
}
