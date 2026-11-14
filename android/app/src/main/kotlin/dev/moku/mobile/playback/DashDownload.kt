package dev.moku.mobile.playback

import okhttp3.OkHttpClient
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.File
import java.io.IOException
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.ceil

// Saves a DASH episode as one MP4: the best video and the first audio track are each joined from their segments, then combined.
internal object DashDownload {
    private val TEMPLATE_VAR = Regex("""\$(RepresentationID|Bandwidth|Number|Time)(?:%0(\d+)d)?\$""")
    private val DURATION = Regex("""P(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:([\d.]+)S)?)?""")

    fun save(client: OkHttpClient, url: String, headers: Map<String, String>, dir: File): File {
        val plan = plan(String(Segments.get(client, url, headers, null)), url)
        val video = saveTrack(client, plan.video, headers, File(dir, "video.part.mp4"))
        val out = File(dir, "video.mp4")
        val audioPlan = plan.audio
        if (audioPlan == null) {
            if (!video.renameTo(out)) throw IOException("Couldn't save the episode")
            return out
        }
        val audio = saveTrack(client, audioPlan, headers, File(dir, "audio.part.mp4"))
        try {
            TrackMuxer.mux(video, audio, out)
        } finally {
            video.delete()
            audio.delete()
        }
        return out
    }

    internal data class Track(val init: String?, val media: List<String>)
    internal data class Plan(val video: Track, val audio: Track?)

    /** Picks the tracks to save from a manifest and lists their segment URLs. */
    internal fun plan(xml: String, manifestUrl: String): Plan {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false }
        val mpd = factory.newDocumentBuilder().parse(InputSource(StringReader(xml))).documentElement
        if (mpd.getAttribute("type") == "dynamic") throw IOException("Live streams can't be downloaded")
        val period = mpd.children("Period").firstOrNull() ?: throw IOException("The manifest has no period")
        val seconds = seconds(period.attr("duration") ?: mpd.attr("mediaPresentationDuration"))
        val base = resolve(resolve(manifestUrl, mpd.baseUrl()), period.baseUrl())
        val sets = period.children("AdaptationSet")
        fun kind(set: Element) = set.attr("contentType")
            ?: (set.attr("mimeType") ?: set.children("Representation").firstOrNull()?.attr("mimeType"))?.substringBefore('/')
        val videoSet = sets.firstOrNull { kind(it) == "video" } ?: throw IOException("The manifest has no video")
        val audioSet = sets.firstOrNull { kind(it) == "audio" }
        if (listOfNotNull(videoSet, audioSet).any { set -> set.children("ContentProtection").isNotEmpty() || set.children("Representation").any { it.children("ContentProtection").isNotEmpty() } }) {
            throw IOException("This episode is DRM protected and can't be downloaded")
        }
        return Plan(track(base, videoSet, seconds), audioSet?.let { track(base, it, seconds) })
    }

    private fun track(base: String, set: Element, seconds: Double): Track {
        val rep = set.children("Representation").maxByOrNull { it.attr("bandwidth")?.toLongOrNull() ?: 0 }
            ?: throw IOException("The manifest lists no streams")
        val repBase = resolve(resolve(base, set.baseUrl()), rep.baseUrl())
        val repTemplate = rep.children("SegmentTemplate").firstOrNull()
        val setTemplate = set.children("SegmentTemplate").firstOrNull()
        val list = rep.children("SegmentList").firstOrNull() ?: set.children("SegmentList").firstOrNull()
        return when {
            repTemplate != null || setTemplate != null -> {
                fun attr(name: String) = repTemplate?.attr(name) ?: setTemplate?.attr(name)
                val timeline = (repTemplate?.children("SegmentTimeline") ?: emptyList()).ifEmpty { setTemplate?.children("SegmentTimeline").orEmpty() }.firstOrNull()
                val timescale = attr("timescale")?.toLongOrNull() ?: 1
                val startNumber = attr("startNumber")?.toLongOrNull() ?: 1
                val times = timeline?.let { segmentTimes(it, (seconds * timescale).toLong()) } ?: run {
                    val duration = attr("duration")?.toLongOrNull() ?: throw IOException("The manifest has no segment durations")
                    val count = ceil(seconds * timescale / duration).toInt()
                    (0 until count).map { it * duration }
                }
                fun fill(template: String, number: Long, time: Long) = TEMPLATE_VAR.replace(template) { m ->
                    val value = when (m.groupValues[1]) {
                        "RepresentationID" -> return@replace rep.getAttribute("id")
                        "Bandwidth" -> rep.attr("bandwidth")?.toLongOrNull() ?: 0
                        "Number" -> number
                        else -> time
                    }
                    m.groupValues[2].toIntOrNull()?.let { "%0${it}d".format(value) } ?: value.toString()
                }.replace("$$", "$")
                Track(
                    init = attr("initialization")?.let { resolve(repBase, fill(it, startNumber, 0)) },
                    media = times.mapIndexed { i, t -> resolve(repBase, fill(attr("media") ?: throw IOException("The manifest names no segments"), startNumber + i, t)) },
                )
            }
            list != null -> Track(
                init = list.children("Initialization").firstOrNull()?.attr("sourceURL")?.let { resolve(repBase, it) },
                media = list.children("SegmentURL").mapNotNull { it.attr("media") }.map { resolve(repBase, it) },
            )
            else -> Track(init = null, media = listOf(repBase))
        }
    }

    private fun segmentTimes(timeline: Element, total: Long): List<Long> {
        val times = mutableListOf<Long>()
        var time = 0L
        val entries = timeline.children("S")
        for ((i, s) in entries.withIndex()) {
            s.attr("t")?.toLongOrNull()?.let { time = it }
            val d = s.attr("d")?.toLongOrNull() ?: continue
            val r = s.attr("r")?.toLongOrNull() ?: 0
            // r = -1 repeats until the next entry, or the end of the period.
            val until = if (r < 0) (entries.getOrNull(i + 1)?.attr("t")?.toLongOrNull() ?: total) else Long.MAX_VALUE
            var n = 0L
            while (if (r < 0) time < until else n <= r) {
                times += time
                time += d
                n++
            }
        }
        return times
    }

    private fun saveTrack(client: OkHttpClient, track: Track, headers: Map<String, String>, file: File): File {
        file.outputStream().buffered().use { out ->
            track.init?.let { out.write(Segments.retry { Segments.get(client, it, headers, null) }) }
            for (segment in track.media) out.write(Segments.retry { Segments.get(client, segment, headers, null) })
        }
        return file
    }

    private fun seconds(iso: String?): Double {
        val m = iso?.let { DURATION.matchEntire(it.trim()) } ?: throw IOException("The manifest has no duration")
        val (d, h, min, s) = m.destructured
        return (d.toDoubleOrNull() ?: 0.0) * 86400 + (h.toDoubleOrNull() ?: 0.0) * 3600 + (min.toDoubleOrNull() ?: 0.0) * 60 + (s.toDoubleOrNull() ?: 0.0)
    }

    private fun resolve(base: String, ref: String?): String = if (ref.isNullOrBlank()) base else HlsPlaylists.resolve(base, ref)

    private fun Element.children(name: String): List<Element> =
        (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>().filter { it.tagName.substringAfter(':') == name }

    private fun Element.attr(name: String): String? = getAttribute(name).takeIf { it.isNotEmpty() }

    private fun Element.baseUrl(): String? = children("BaseURL").firstOrNull()?.textContent?.trim()
}
