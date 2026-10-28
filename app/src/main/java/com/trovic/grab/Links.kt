package com.trovic.grab

import android.content.ClipboardManager
import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf

object Links {
    private val URL_RE = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)

    /** First http(s) link inside a piece of text (shared text often has a caption around it). */
    fun find(text: CharSequence?): String? =
        text?.let { URL_RE.find(it)?.value?.trimEnd('.', ',', ')', ']', '!', '?', ';', ':') }

    fun readClipboard(ctx: Context): CharSequence? = try {
        val cm = ctx.getSystemService(ClipboardManager::class.java)
        val clip = cm?.primaryClip
        if (clip == null || clip.itemCount == 0) null else clip.getItemAt(0).coerceToText(ctx)
    } catch (e: SecurityException) {
        null
    }

    /** Queue a download in the background. Same link twice at once is ignored. */
    fun enqueue(ctx: Context, url: String) {
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(workDataOf(DownloadWorker.KEY_URL to url))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag(DownloadWorker.TAG)
            .build()
        History.put(
            ctx,
            History.Entry(request.id.toString(), url, "queued", "Waiting…", System.currentTimeMillis(), null, null),
        )
        WorkManager.getInstance(ctx).enqueueUniqueWork("dl:$url", ExistingWorkPolicy.KEEP, request)
        Prefs.markHandled(ctx, url)
    }

    fun short(url: String): String =
        url.removePrefix("https://").removePrefix("http://").removePrefix("www.").take(70)
}
