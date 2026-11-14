package dev.moku.mobile.playback

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.MimeTypes
import dev.moku.mobile.backend.SubtitleTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

internal data class LoadedSubtitle(val label: String, val file: File, val mime: String)

// Fetches and identifies subtitles the way Tsunagu does, so the player gets a local file with the right format.
internal object SubtitleLoader {
    private const val TIMEOUT_MS = 15_000L
    private const val MAX_AGE_MS = 24L * 3600 * 1000
    private const val SAVED = "subtitles.json"
    private val SRT_CUE = Regex("""^\d+\s*\r?\n\d{1,2}:\d{2}:\d{2}[,.]\d{1,3}\s*-->""")

    suspend fun load(context: Context, tracks: List<SubtitleTrack>, client: OkHttpClient, sourceClient: OkHttpClient?): List<LoadedSubtitle> {
        val dir = File(context.cacheDir, "subtitles").apply { mkdirs() }
        dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > MAX_AGE_MS }?.forEach { it.delete() }
        return coroutineScope {
            tracks.map { track ->
                async(Dispatchers.IO) {
                    withTimeoutOrNull(TIMEOUT_MS) {
                        runCatching { loadOne(dir, track, client, sourceClient) }
                            .onFailure { Log.w("SubtitleLoader", "couldn't load ${track.label}: ${it.message}") }
                            .getOrNull()
                    }
                }
            }.awaitAll().filterNotNull()
        }
    }

    /** Copies loaded subtitles next to a downloaded video. */
    fun saveTo(dir: File, subtitles: List<LoadedSubtitle>) {
        val list = JSONArray()
        subtitles.forEachIndexed { i, s ->
            val file = File(dir, "subtitle-$i.${s.file.extension}")
            s.file.copyTo(file, overwrite = true)
            list.put(JSONObject().put("file", file.name).put("label", s.label))
        }
        if (subtitles.isNotEmpty()) File(dir, SAVED).writeText(list.toString())
    }

    fun savedIn(dir: File): List<SubtitleTrack> {
        val saved = File(dir, SAVED)
        if (!saved.exists()) {
            return File(dir, "subtitle.vtt").takeIf { it.exists() }?.let { listOf(SubtitleTrack("Subtitles", Uri.fromFile(it).toString())) }.orEmpty()
        }
        val list = JSONArray(saved.readText())
        return (0 until list.length()).map { list.getJSONObject(it) }.mapNotNull { o ->
            File(dir, o.getString("file")).takeIf { it.exists() }?.let { SubtitleTrack(o.optString("label"), Uri.fromFile(it).toString()) }
        }
    }

    private fun loadOne(dir: File, track: SubtitleTrack, client: OkHttpClient, sourceClient: OkHttpClient?): LoadedSubtitle? {
        val key = sha1(track.url)
        dir.listFiles { f -> f.nameWithoutExtension == key }?.firstOrNull()?.let { return LoadedSubtitle(track.label, it, mimeOf(it.extension)) }
        val body = fetch(track, client, sourceClient) ?: return null
        val (bytes, ext) = normalize(body, track.url)
        val file = File(dir, "$key.$ext").apply { writeBytes(bytes) }
        return LoadedSubtitle(track.label.ifBlank { "Subtitles" }, file, mimeOf(ext))
    }

    // The video's headers first, then Tsunagu's fallbacks, then the source's own client for hosts behind its cookies.
    private fun fetch(track: SubtitleTrack, client: OkHttpClient, sourceClient: OkHttpClient?): ByteArray? {
        val uri = Uri.parse(track.url)
        if (uri.scheme == "file") return uri.path?.let(::File)?.takeIf { it.exists() }?.readBytes()
        val origin = "${uri.scheme}://${uri.encodedAuthority}"
        val attempts = listOfNotNull(track.headers.takeIf { it.isNotEmpty() }, emptyMap(), mapOf("Referer" to "$origin/", "Origin" to origin))
        for (headers in attempts) get(client, track.url, headers)?.let { return it }
        return sourceClient?.let { get(it, track.url, track.headers) }
    }

    private fun get(client: OkHttpClient, url: String, headers: Map<String, String>): ByteArray? = runCatching {
        val request = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        client.newCall(request).execute().use { r -> if (r.isSuccessful) r.body.bytes().takeIf { it.isNotEmpty() } else null }
    }.getOrNull()

    private fun normalize(body: ByteArray, url: String): Pair<ByteArray, String> {
        val text = String(body, Charsets.UTF_8).removePrefix("﻿")
        val head = text.trimStart().take(64).lowercase()
        val ext = Uri.parse(url).lastPathSegment?.substringAfterLast('.', "")?.lowercase()
        return when {
            head.startsWith("[script info]") || ext == "ass" || ext == "ssa" -> body to "ass"
            head.startsWith("webvtt") -> body to "vtt"
            ext == "srt" || SRT_CUE.containsMatchIn(text.trimStart()) -> body to "srt"
            head.startsWith("<?xml") || head.startsWith("<tt") -> body to "ttml"
            else -> "WEBVTT\n\n$text".toByteArray() to "vtt"
        }
    }

    private fun mimeOf(ext: String) = when (ext) {
        "ass" -> MimeTypes.TEXT_SSA
        "srt" -> MimeTypes.APPLICATION_SUBRIP
        "ttml" -> MimeTypes.APPLICATION_TTML
        else -> MimeTypes.TEXT_VTT
    }

    private fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
