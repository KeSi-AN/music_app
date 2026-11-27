package com.riyaz.app.youtube

import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.GeographicRestrictionException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.io.File
import java.io.IOException

data class YtResult(
    val videoId: String,
    val url: String,
    val title: String,
    val channel: String?,
    val durationSec: Long,
    val thumbnailUrl: String?,
)

data class YtDownload(
    val file: File,
    val videoId: String,
    val url: String,
    val title: String,
    val channel: String?,
    val thumbnailUrl: String?,
)

/** Thrown with a message that can be shown to the user as-is. */
class YouTubeException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Search, link parsing and audio download, via NewPipeExtractor (the library behind the NewPipe app).
 * All calls block: run them on Dispatchers.IO.
 */
object YouTube {
    enum class Source(val filter: String, val label: String) {
        YOUTUBE("videos", "YouTube"),
        MUSIC("music_songs", "YouTube Music"),
    }

    private val service get() = ServiceList.YouTube
    private val downloader by lazy { OkHttpDownloader.create().also { NewPipe.init(it) } }

    private const val CHUNK = 2 shl 20  // ranged 2 MB requests: YouTube throttles single huge requests

    fun search(query: String, source: Source): List<YtResult> = guard {
        downloader
        val handler = service.searchQHFactory.fromQuery(query, listOf(source.filter), "")
        SearchInfo.getInfo(service, handler).relatedItems
            .filterIsInstance<StreamInfoItem>()
            .mapNotNull { item ->
                val id = runCatching { service.streamLHFactory.getId(item.url) }.getOrNull() ?: return@mapNotNull null
                YtResult(
                    videoId = id,
                    url = item.url,
                    title = item.name,
                    channel = cleanChannel(item.uploaderName),
                    durationSec = item.duration,
                    thumbnailUrl = item.thumbnails.maxByOrNull { it.width }?.url,
                )
            }
    }

    /** Finds a YouTube / YouTube Music / Shorts link anywhere in [text] (e.g. a shared message). */
    fun findLink(text: String): String? =
        Regex("""https?://\S+""").findAll(text).map { it.value.trimEnd('.', ',', ')', '"') }
            .firstOrNull { runCatching { service.streamLHFactory.acceptUrl(it) }.getOrDefault(false) }

    /** YouTube Music artist channels are named "Artist - Topic". */
    fun cleanChannel(name: String?): String? = name?.replace(Regex("""\s*-\s*Topic$""", RegexOption.IGNORE_CASE), "")?.trim()?.ifBlank { null }

    fun videoId(url: String): String? = runCatching { service.streamLHFactory.getId(url) }.getOrNull()

    /** Downloads the best audio-only stream of [url] into [dir]. */
    fun downloadAudio(url: String, dir: File, onProgress: (Float) -> Unit): YtDownload = guard {
        downloader
        val t0 = System.nanoTime()
        val info = StreamInfo.getInfo(service, url)
        val stream = pickAudio(info.audioStreams)
            ?: throw YouTubeException("This video has no audio we can download")
        val ext = stream.format?.suffix ?: "audio"
        val file = File(dir, "source.$ext")
        val t1 = System.nanoTime()
        download(stream.content, file, onProgress)
        android.util.Log.i(
            "Riyaz", "yt ${info.id}: info ${(t1 - t0) / 1_000_000} ms, itag ${stream.itag} " +
                "${file.length() / 1024} KiB in ${(System.nanoTime() - t1) / 1_000_000} ms",
        )
        YtDownload(
            file = file,
            videoId = info.id,
            url = info.url,
            title = info.name,
            channel = cleanChannel(info.uploaderName),
            thumbnailUrl = info.thumbnails.maxByOrNull { it.width }?.url,
        )
    }

    /**
     * Highest-bitrate plain HTTP audio stream in the original language. That is usually Opus 160 kbps
     * (itag 251) and otherwise AAC 128 kbps (itag 140); Android decodes both.
     */
    private fun pickAudio(streams: List<AudioStream>): AudioStream? {
        val direct = streams.filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.isUrl }
        val original = direct.filter { it.audioTrackType?.name.let { t -> t == null || t == "ORIGINAL" } }
        return (original.ifEmpty { direct }).maxByOrNull { it.averageBitrate }
    }

    private fun download(url: String, out: File, onProgress: (Float) -> Unit) {
        val client = downloader.client
        var total = 0L
        var size = -1L
        out.outputStream().buffered(1 shl 16).use { os ->
            while (size < 0 || total < size) {
                val req = okhttp3.Request.Builder().url(url)
                    .header("User-Agent", OkHttpDownloader.USER_AGENT)
                    .header("Range", "bytes=$total-${total + CHUNK - 1}")
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (resp.code !in 200..299) throw IOException("Download failed (HTTP ${resp.code})")
                    val range = resp.header("Content-Range")
                    size = range?.substringAfter('/')?.toLongOrNull()
                        ?: resp.body.contentLength().takeIf { it > 0 && resp.code == 200 }
                        ?: throw IOException("Download failed (unknown size)")
                    val n = resp.body.byteStream().use { it.copyTo(os) }
                    if (n == 0L) throw IOException("Download stalled")
                    total += n
                    onProgress((total.toFloat() / size).coerceIn(0f, 1f))
                    if (resp.code == 200) total = size  // server ignored Range and sent everything
                }
            }
        }
    }

    private inline fun <T> guard(block: () -> T): T = try {
        block()
    } catch (e: YouTubeException) {
        throw e
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e   // the user cancelled
    } catch (e: AgeRestrictedContentException) {
        throw YouTubeException("This video is age-restricted, so it can't be downloaded without signing in.", e)
    } catch (e: GeographicRestrictionException) {
        throw YouTubeException("This video isn't available in your country.", e)
    } catch (e: ReCaptchaException) {
        throw YouTubeException("YouTube is asking for a captcha. Try again in a while.", e)
    } catch (e: ContentNotAvailableException) {
        throw YouTubeException("This video isn't available.", e)
    } catch (e: IOException) {
        throw YouTubeException("Couldn't reach YouTube. Check your internet connection.", e)
    } catch (e: Exception) {
        throw YouTubeException("YouTube didn't work this time (${e.javaClass.simpleName}). The YouTube library may need an update.", e)
    }
}
