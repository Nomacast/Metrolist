/**
 * Metrolist N (Nomacast) — export finished downloads as regular audio files.
 * Licensed under GPL-3.0, like the rest of Metrolist.
 *
 * Every song Metrolist finishes downloading is copied from the download cache to
 * Music/Metrolist/"Artist - Title" through MediaStore (no permission needed, API 29+):
 *  - AAC streams as .m4a, copied as-is;
 *  - Opus streams (WebM, which MediaStore rejects) remuxed losslessly to Ogg as .opus.
 * Never throws; failures are logged under the "DownloadExporter" tag.
 */

package com.metrolist.music.playback

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.Song
import kotlinx.coroutines.flow.first
import java.io.File
import java.nio.ByteBuffer

object DownloadExporter {
    private const val TAG = "DownloadExporter"
    private const val FOLDER = "Music/Metrolist"

    suspend fun export(context: Context, cache: Cache, database: MusicDatabase, songId: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val raw = File(context.cacheDir, "export_$songId.raw")
        val ogg = File(context.cacheDir, "export_$songId.ogg")
        runCatching {
            val song = database.getSongByIdBlocking(songId) ?: error("song not in database")
            val isOpus = database.format(songId).first()?.mimeType?.contains("webm") == true
            val (ext, mime) = if (isOpus) "opus" to "audio/ogg" else "m4a" to "audio/mp4"
            val artist = song.artists.joinToString(", ") { it.name }.ifBlank { null }
            if (isExported(context, song)) return // keep the existing file
            val name = baseName(song) + ".$ext"

            val resolver = context.contentResolver
            val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

            copyFromCache(cache, songId, raw)
            val file = if (isOpus) raw.also { webmToOgg(it, ogg) }.let { ogg } else raw

            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$FOLDER/")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
                put(MediaStore.Audio.Media.TITLE, song.song.title)
                artist?.let { put(MediaStore.Audio.Media.ARTIST, it) }
                song.album?.title?.let { put(MediaStore.Audio.Media.ALBUM, it) }
            }
            val uri = resolver.insert(collection, values) ?: error("MediaStore refused $name")
            try {
                resolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                Log.i(TAG, "Exported $name")
            } catch (t: Throwable) {
                resolver.delete(uri, null, null) // never leave a half-written file behind
                throw t
            }
        }.onFailure { Log.w(TAG, "Export failed for $songId", it) }
        raw.delete()
        ogg.delete()
    }

    /**
     * True if Music/Metrolist already holds this song (.m4a or .opus). Files left by a previous
     * install are only visible with the audio read permission.
     */
    fun isExported(context: Context, song: Song): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val base = baseName(song)
        return runCatching {
            context.contentResolver.query(
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} IN (?, ?)",
                arrayOf("$FOLDER%", "$base.m4a", "$base.opus"),
                null,
            )?.use { it.count > 0 } ?: false
        }.getOrDefault(false)
    }

    private fun baseName(song: Song) =
        sanitize(listOfNotNull(song.artists.joinToString(", ") { it.name }.ifBlank { null }, song.song.title).joinToString(" - "))

    /** Reads the fully downloaded stream for [songId] out of the download cache. */
    private fun copyFromCache(cache: Cache, songId: String, dest: File) {
        val source = CacheDataSource.Factory().setCache(cache).createDataSourceForDownloading()
        source.open(DataSpec.Builder().setUri(songId.toUri()).setKey(songId).build())
        try {
            dest.outputStream().use { out ->
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
    }

    /** Lossless WebM → Ogg remux of the Opus audio track (no re-encoding). */
    private fun webmToOgg(src: File, dest: File) {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            extractor.setDataSource(src.path)
            val track = (0 until extractor.trackCount).first {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            }
            extractor.selectTrack(track)
            muxer = MediaMuxer(dest.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG)
            val outTrack = muxer.addTrack(extractor.getTrackFormat(track))
            muxer.start()
            val buffer = ByteBuffer.allocate(1 shl 20)
            val info = MediaCodec.BufferInfo()
            while (true) {
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                info.set(0, size, extractor.sampleTime, 0)
                muxer.writeSampleData(outTrack, buffer, info)
                extractor.advance()
            }
            muxer.stop()
        } finally {
            runCatching { muxer?.release() }
            extractor.release()
        }
    }

    private fun sanitize(name: String) =
        name.replace(Regex("""[\\/:*?"<>|\p{Cntrl}]"""), "_").trim().take(150).ifBlank { "track" }
}
