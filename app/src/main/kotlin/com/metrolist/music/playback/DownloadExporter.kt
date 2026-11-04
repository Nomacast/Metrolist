/**
 * Metrolist N (Nomacast) — export finished downloads as regular audio files.
 * Licensed under GPL-3.0, like the rest of Metrolist.
 *
 * Every song Metrolist finishes downloading is copied, as-is (no re-encoding), from the
 * download cache to Music/Metrolist/"Artist - Title.m4a|.webm" through MediaStore, so any
 * player or file manager can use it. Needs no permission (API 29+). Never throws.
 */

package com.metrolist.music.playback

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import com.metrolist.music.db.MusicDatabase
import kotlinx.coroutines.flow.first
import timber.log.Timber

object DownloadExporter {
    private const val FOLDER = "Music/Metrolist"

    suspend fun export(context: Context, cache: Cache, database: MusicDatabase, songId: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        runCatching {
            val song = database.getSongByIdBlocking(songId) ?: return
            val mime = database.format(songId).first()?.mimeType ?: "audio/mp4"
            val ext = if (mime.contains("webm")) "webm" else "m4a"
            val artist = song.artists.joinToString(", ") { it.name }.ifBlank { null }
            val name = sanitize(listOfNotNull(artist, song.song.title).joinToString(" - ")) + ".$ext"

            val resolver = context.contentResolver
            val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

            // Already exported (e.g. song downloaded again): keep the existing file.
            resolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                arrayOf("$FOLDER%", name),
                null,
            )?.use { if (it.count > 0) return }

            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$FOLDER/")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
                put(MediaStore.Audio.Media.TITLE, song.song.title)
                artist?.let { put(MediaStore.Audio.Media.ARTIST, it) }
                song.album?.title?.let { put(MediaStore.Audio.Media.ALBUM, it) }
            }
            val uri = resolver.insert(collection, values) ?: return

            try {
                val source = CacheDataSource.Factory().setCache(cache).createDataSourceForDownloading()
                source.open(DataSpec.Builder().setUri(songId.toUri()).setKey(songId).build())
                try {
                    resolver.openOutputStream(uri)!!.use { out ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = source.read(buffer, 0, buffer.size)
                            if (read == C.RESULT_END_OF_INPUT) break
                            out.write(buffer, 0, read)
                        }
                    }
                } finally {
                    source.close()
                }
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            } catch (t: Throwable) {
                resolver.delete(uri, null, null) // never leave a half-written file behind
                throw t
            }
        }.onFailure { Timber.tag("DownloadExporter").w(it, "Export failed for $songId") }
    }

    private fun sanitize(name: String) =
        name.replace(Regex("""[\\/:*?"<>|\p{Cntrl}]"""), "_").trim().take(150).ifBlank { "track" }
}
