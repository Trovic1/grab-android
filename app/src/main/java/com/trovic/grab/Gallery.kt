package com.trovic.grab

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import java.io.File

data class Saved(val uri: Uri, val mime: String, val name: String)

/** Copies a finished file into the phone's shared Gallery folders (Movies/Grab, Pictures/Grab). */
object Gallery {
    private val VIDEO_EXT = setOf("mp4", "webm", "mkv", "mov", "m4v", "3gp")
    private val AUDIO_EXT = setOf("m4a", "mp3", "opus", "ogg", "aac", "wav")

    fun mimeFor(file: File): String {
        val ext = file.extension.lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: when (ext) {
                in VIDEO_EXT -> "video/$ext"
                in AUDIO_EXT -> "audio/$ext"
                "webp" -> "image/webp"
                "heic" -> "image/heic"
                else -> "image/jpeg"
            }
    }

    fun save(ctx: Context, file: File): Saved {
        val mime = mimeFor(file)
        val kind = when {
            mime.startsWith("video") -> Environment.DIRECTORY_MOVIES
            mime.startsWith("audio") -> Environment.DIRECTORY_MUSIC
            else -> Environment.DIRECTORY_PICTURES
        }
        // Songs keep their title as the file name; videos/pictures get a unique Grab_ name.
        val name = if (kind == Environment.DIRECTORY_MUSIC) file.name.replace('_', ' ')
        else "Grab_" + System.currentTimeMillis() + "_" + file.name.takeLast(80)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val collection = when (kind) {
                Environment.DIRECTORY_MOVIES -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                Environment.DIRECTORY_MUSIC -> MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                else -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$kind/Grab")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = ctx.contentResolver
            val uri = resolver.insert(collection, values) ?: error("Couldn't create file in Gallery")
            try {
                resolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
            return Saved(uri, mime, name)
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(kind), "Grab").apply { mkdirs() }
            val out = File(dir, name)
            file.copyTo(out, overwrite = true)
            MediaScannerConnection.scanFile(ctx, arrayOf(out.path), arrayOf(mime), null)
            return Saved(Uri.fromFile(out), mime, name)
        }
    }
}
