package com.trovic.grab

import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Wraps yt-dlp (which supports Instagram, TikTok, X, Facebook, YouTube, Reddit, Pinterest and 1000+ sites). */
object Downloader {
    private val lock = Mutex()
    @Volatile private var ready = false
    @Volatile private var updatedThisRun = false
    private const val AUTO_UPDATE_EVERY_MS = 2L * 24 * 60 * 60 * 1000 // 2 days

    fun cookiesFile(ctx: Context) = File(ctx.filesDir, "cookies.txt")

    private fun initIfNeeded(ctx: Context) {
        if (!ready) {
            YoutubeDL.getInstance().init(ctx)
            FFmpeg.getInstance().init(ctx)
            ready = true
        }
    }

    suspend fun version(ctx: Context): String? = withContext(Dispatchers.IO) {
        lock.withLock { runCatching { initIfNeeded(ctx); YoutubeDL.getInstance().version(ctx) }.getOrNull() }
    }

    /** Sites change often; a fresh yt-dlp fixes most "it stopped working" problems. */
    suspend fun update(ctx: Context): String = withContext(Dispatchers.IO) {
        lock.withLock {
            initIfNeeded(ctx)
            val status = YoutubeDL.getInstance().updateYoutubeDL(ctx, YoutubeDL.UpdateChannel.STABLE)
            Prefs.setLastEngineUpdate(ctx, System.currentTimeMillis())
            updatedThisRun = true
            val v = YoutubeDL.getInstance().version(ctx) ?: "?"
            if (status == YoutubeDL.UpdateStatus.DONE) "Updated to $v" else "Already up to date ($v)"
        }
    }

    suspend fun download(ctx: Context, url: String, onProgress: (Int?) -> Unit): List<Saved> =
        withContext(Dispatchers.IO) {
            lock.withLock { initIfNeeded(ctx) }
            if (System.currentTimeMillis() - Prefs.lastEngineUpdate(ctx) > AUTO_UPDATE_EVERY_MS) {
                runCatching { update(ctx) }
            }

            val tmp = File(ctx.cacheDir, "dl_" + System.nanoTime()).apply { mkdirs() }
            try {
                var firstError: Throwable? = null
                var files = runCatching { ytdlp(ctx, url, tmp, onProgress) }
                    .onFailure { firstError = it }.getOrDefault(emptyList())

                if (files.isEmpty()) {
                    onProgress(null)
                    files = runCatching { Fallback.fetch(url, tmp) }.getOrDefault(emptyList())
                }
                if (files.isEmpty() && !updatedThisRun) {
                    // Maybe the site changed: update yt-dlp once and try again.
                    runCatching { update(ctx) }
                    files = runCatching { ytdlp(ctx, url, tmp, onProgress) }
                        .onFailure { firstError = it }.getOrDefault(emptyList())
                }
                if (files.isEmpty()) throw firstError ?: IllegalStateException("No video or picture found at this link")

                files.sortedBy { it.name }.map { Gallery.save(ctx, it) }
            } finally {
                tmp.deleteRecursively()
            }
        }

    private fun ytdlp(ctx: Context, url: String, dir: File, onProgress: (Int?) -> Unit): List<File> {
        dir.listFiles()?.forEach { it.deleteRecursively() }
        val request = YoutubeDLRequest(url).apply {
            addOption("-o", dir.absolutePath + "/%(extractor_key)s_%(id)s.%(ext)s")
            addOption("--restrict-filenames")
            addOption("--no-mtime")
            addOption("--no-playlist")
            // Prefer H.264 + AAC in MP4 so every gallery/player can open it.
            addOption("-f", "bv*[vcodec^=avc]+ba[ext=m4a]/b[ext=mp4]/bv*+ba/b")
            addOption("--merge-output-format", "mp4")
            addOption("--retries", "3")
            val cookies = cookiesFile(ctx)
            if (cookies.exists()) addOption("--cookies", cookies.absolutePath)
        }
        var last = -1
        YoutubeDL.getInstance().execute(request, null) { progress, _, _ ->
            val pct = progress.toInt()
            if (pct in 0..100 && pct != last) {
                last = pct
                onProgress(pct)
            }
        }
        return dir.listFiles()
            ?.filter { it.isFile && it.length() > 0 && it.extension !in setOf("part", "ytdl", "json", "txt") }
            .orEmpty()
    }

    /** Turns yt-dlp's long error output into one readable line. */
    fun friendly(e: Throwable, hasCookies: Boolean): String {
        val raw = e.message.orEmpty()
        val line = raw.lines().lastOrNull { it.contains("ERROR") }?.substringAfter("ERROR:")?.trim()
            ?: raw.lines().firstOrNull { it.isNotBlank() }?.trim()
            ?: e.javaClass.simpleName
        val lower = raw.lowercase()
        val hint = when {
            !hasCookies && ("login" in lower || "cookies" in lower || "rate-limit" in lower || "private" in lower) ->
                " — this post may need you logged in. Import cookies.txt in Grab settings."
            "unsupported url" in lower -> " — this site isn't supported."
            else -> ""
        }
        return (line.take(220) + hint)
    }
}
