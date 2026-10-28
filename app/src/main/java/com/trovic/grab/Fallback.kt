package com.trovic.grab

import android.text.Html
import android.webkit.MimeTypeMap
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Backup for posts yt-dlp can't handle (mostly picture-only posts):
 *  - X/Twitter photos through the public fxtwitter API
 *  - direct image/video links
 *  - the page's og:video / og:image preview tags (Instagram, Pinterest, Facebook, Threads, Reddit…)
 */
object Fallback {
    private const val UA_PREVIEW = "facebookexternalhit/1.1 (+http://www.facebook.com/externalhit_uatext.php)"
    private const val UA_MOBILE =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0 Mobile Safari/537.36"

    private val TWEET_RE = Regex("""(?:twitter\.com|x\.com|fxtwitter\.com|vxtwitter\.com|fixupx\.com)/[^/]+/status/(\d+)""", RegexOption.IGNORE_CASE)
    private val META_RE = Regex("""<meta\b[^>]*>""", RegexOption.IGNORE_CASE)

    fun fetch(url: String, dir: File): List<File> {
        twitter(url, dir).takeIf { it.isNotEmpty() }?.let { return it }

        val conn = open(url, UA_PREVIEW)
        val type = conn.contentType.orEmpty()
        if (type.startsWith("image/") || type.startsWith("video/")) {
            return listOf(write(conn, dir, 0, type))
        }
        val html = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()

        val metas = META_RE.findAll(html).mapNotNull { m ->
            val tag = m.value
            val key = attr(tag, "property") ?: attr(tag, "name") ?: return@mapNotNull null
            val content = attr(tag, "content") ?: return@mapNotNull null
            key.lowercase() to Html.fromHtml(content, Html.FROM_HTML_MODE_LEGACY).toString()
        }.toList()

        fun values(vararg keys: String) = metas.filter { it.first in keys }.map { it.second }
            .filter { it.startsWith("http") }.distinct()

        val videos = values("og:video:secure_url", "og:video:url", "og:video", "twitter:player:stream")
        val images = values("og:image:secure_url", "og:image", "og:image:url", "twitter:image", "twitter:image:src")
        val picks = if (videos.isNotEmpty()) videos.take(1) else images.take(10)

        return picks.mapIndexedNotNull { i, media ->
            runCatching { write(open(media, UA_MOBILE), dir, i, null) }.getOrNull()
        }
    }

    private fun twitter(url: String, dir: File): List<File> {
        val id = TWEET_RE.find(url)?.groupValues?.get(1) ?: return emptyList()
        val json = runCatching {
            open("https://api.fxtwitter.com/status/$id", UA_MOBILE).inputStream.bufferedReader().use { it.readText() }
        }.getOrNull() ?: return emptyList()
        val all = JSONObject(json).optJSONObject("tweet")?.optJSONObject("media")?.optJSONArray("all")
            ?: return emptyList()
        return (0 until all.length()).mapNotNull { i ->
            val item = all.getJSONObject(i)
            var media = item.optString("url").ifEmpty { return@mapNotNull null }
            if (item.optString("type") == "photo" && "pbs.twimg.com" in media && "?" !in media) media += "?name=orig"
            runCatching { write(open(media, UA_MOBILE), dir, i, null) }.getOrNull()
        }
    }

    private fun attr(tag: String, name: String): String? =
        Regex("""\b$name\s*=\s*(["'])(.*?)\1""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(tag)?.groupValues?.get(2)

    private fun open(url: String, ua: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("User-Agent", ua)
        conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
        val code = conn.responseCode
        if (code !in 200..299) {
            conn.disconnect()
            error("Site answered HTTP $code")
        }
        return conn
    }

    private fun write(conn: HttpURLConnection, dir: File, index: Int, knownType: String?): File {
        val type = (knownType ?: conn.contentType.orEmpty()).substringBefore(';').trim()
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(type)
            ?: conn.url.path.substringAfterLast('.', "").takeIf { it.length in 2..4 }
            ?: "jpg"
        if (!type.startsWith("image/") && !type.startsWith("video/")) {
            conn.disconnect()
            error("Not a picture or video ($type)")
        }
        val out = File(dir, "media_$index.$ext")
        conn.inputStream.use { input -> out.outputStream().use { input.copyTo(it) } }
        conn.disconnect()
        return out
    }
}
