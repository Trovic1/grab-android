package com.trovic.grab

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException

class DownloadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    companion object {
        const val KEY_URL = "url"
        const val KEY_PROGRESS = "progress"
        const val TAG = "grab"
    }

    private val url get() = inputData.getString(KEY_URL).orEmpty()
    private val notifId get() = id.hashCode()

    private fun foreground(percent: Int?): ForegroundInfo {
        val n = Notifs.progress(applicationContext, Links.short(url), percent)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notifId, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else ForegroundInfo(notifId, n)
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foreground(null)

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        if (url.isEmpty()) return Result.failure()
        val historyId = id.toString()

        // Keeps the download alive if you leave the app. Allowed to fail (e.g. started from background).
        runCatching { setForeground(foreground(null)) }
        History.update(ctx, historyId) { it.copy(state = "running", message = "Downloading…") }

        return try {
            val saved = Downloader.download(ctx, url) { pct ->
                setProgressAsync(workDataOf(KEY_PROGRESS to (pct ?: -1)))
                Notifs.post(ctx, notifId, Notifs.progress(ctx, Links.short(url), pct))
            }
            val first = saved.first()
            val msg = if (saved.size == 1) "Saved to Gallery" else "Saved ${saved.size} files to Gallery"
            History.update(ctx, historyId) {
                it.copy(state = "done", message = msg, uri = first.uri.toString(), mime = first.mime)
            }
            Notifs.done(ctx, notifId + 1, saved)
            Result.success()
        } catch (e: CancellationException) {
            History.update(ctx, historyId) { it.copy(state = "failed", message = "Stopped") }
            throw e
        } catch (e: Throwable) {
            val msg = Downloader.friendly(e, Downloader.cookiesFile(ctx).exists())
            History.update(ctx, historyId) { it.copy(state = "failed", message = msg) }
            Notifs.failed(ctx, notifId + 1, url, msg)
            Result.failure()
        }
    }
}
