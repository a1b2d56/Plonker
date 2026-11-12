package com.music.bitchord.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.model.Song
import java.io.File
import java.util.Locale

/**
 * Music on USB drives and other mounted storage, which MediaStore on a TV box
 * often does not index. Plugged into [LocalMediaRepository.extraLocalMusic], so
 * the phone's MediaStore scan stays the single source for everything else.
 */
object TvUsbMusic {
    private const val TAG = "BitChord"
    private const val MAX_DEPTH = 4

    private val audioExtensions = setOf(
        "mp3", "m4a", "flac", "wav", "ogg", "opus", "aac", "webm", "3gp",
    )

    fun install() {
        LocalMediaRepository.extraLocalMusic = ::withUsbDrives
    }

    /** [found] (what MediaStore knows) plus any audio files on mounted storage it missed. */
    private fun withUsbDrives(context: Context, found: List<Song>): List<Song> {
        val songs = found.toMutableList()
        val knownPaths = found.mapNotNullTo(mutableSetOf()) { it.localPath }

        runCatching {
            File("/storage").takeIf { it.isDirectory }?.listFiles()?.forEach { volume ->
                if (volume.isDirectory && volume.canRead()) {
                    scan(context, volume, songs, knownPaths, MAX_DEPTH)
                }
            }
        }.onFailure { Log.w(TAG, "Failed scanning /storage/ for USB drives: ${it.message}") }

        // Drives the app can reach through its own per-volume directories.
        // The built-in volume is left to MediaStore, which already listed it.
        runCatching {
            val primary = context.getExternalFilesDir(null)
            context.getExternalFilesDirs(null)?.forEach { externalDir ->
                if (externalDir == null || externalDir == primary) return@forEach
                val root = volumeRootOf(externalDir) ?: return@forEach
                if (root.canRead()) scan(context, root, songs, knownPaths, MAX_DEPTH)
            }
        }

        return songs.distinctBy { it.localPath ?: it.localUri ?: it.videoId }
    }

    /**
     * The volume a per-app directory sits on: the folder that holds its
     * `Android` directory. For `/storage/1A2B-3C4D/Android/data/<app>/files`
     * that is `/storage/1A2B-3C4D`. Stopping one level short, at
     * `Android/data`, rescanned the app's own folders and none of the music.
     */
    internal fun volumeRootOf(dir: File): File? {
        var current: File? = dir
        while (current != null) {
            if (current.name == "Android") return current.parentFile
            current = current.parentFile
        }
        return null
    }

    private fun scan(
        context: Context,
        dir: File,
        results: MutableList<Song>,
        knownPaths: MutableSet<String>,
        maxDepth: Int,
    ) {
        if (maxDepth <= 0 || !dir.canRead() || dir.name.startsWith(".")) return

        dir.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                scan(context, file, results, knownPaths, maxDepth - 1)
            } else if (file.isFile && file.extension.lowercase(Locale.ROOT) in audioExtensions) {
                // The same file can be reached under more than one name
                // (`/storage/self/primary` is `/storage/emulated/0`), so it is
                // known by where it really is as well as how it was reached.
                val absolute = file.absolutePath
                val real = runCatching { file.canonicalPath }.getOrDefault(absolute)
                val newReal = knownPaths.add(real)
                val newAbsolute = real == absolute || knownPaths.add(absolute)
                if (newReal && newAbsolute) {
                    results.add(songFromFile(context, file))
                }
            }
        }
    }

    private fun songFromFile(context: Context, file: File): Song {
        val uri = Uri.fromFile(file).toString()
        var title = file.nameWithoutExtension
        var artist = "Unknown Artist"
        var albumName: String? = null
        var durationText: String? = null

        runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, Uri.parse(uri))
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                    ?.takeIf { it.isNotBlank() }?.let { title = it }
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    ?.takeIf { it.isNotBlank() }?.let { artist = it }
                albumName = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                    ?.takeUnless { it.isBlank() || it == "<unknown>" }
                durationText = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()?.takeIf { it > 0 }?.let(::formatDuration)
            } finally {
                retriever.release()
            }
        }

        return Song(
            videoId = uri,
            title = title,
            thumbnailUrl = null,
            artist = artist,
            durationText = durationText,
            albumName = albumName,
            localUri = uri,
            localPath = file.absolutePath,
            fromAutoplay = false,
        )
    }

    private fun formatDuration(ms: Long): String {
        val totalSecs = ms / 1000
        return String.format(Locale.ROOT, "%d:%02d", totalSecs / 60, totalSecs % 60)
    }
}
