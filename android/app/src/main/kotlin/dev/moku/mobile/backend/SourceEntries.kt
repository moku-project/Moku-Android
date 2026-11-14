package dev.moku.mobile.backend

import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.source.model.SManga
import java.util.Collections

// Backends are rebuilt per screen, so a source's own entries live here; sources often need a listing's fields again.
internal object SourceEntries {
    private const val MAX = 4000
    private val entries: MutableMap<String, Any> = Collections.synchronizedMap(
        object : LinkedHashMap<String, Any>(256, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Any>?) = size > MAX
        },
    )

    fun put(originId: String, kind: String, url: String, entry: Any) {
        entries["$originId|$kind|$url"] = entry
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> get(originId: String, kind: String, url: String): T? = entries["$originId|$kind|$url"] as? T
}

/** Reads a field a source may have left unset (lateinit) as null instead of throwing. */
internal inline fun <T> unsetAsNull(get: () -> T): T? =
    try { get() } catch (_: UninitializedPropertyAccessException) { null }

// Details often come back as a fresh object with only some fields set, so they're merged into the known entry.
internal fun SManga.mergeDetails(from: SManga): SManga = apply {
    unsetAsNull { from.title }?.takeIf { it.isNotBlank() }?.let { title = it }
    from.artist?.let { artist = it }
    from.author?.let { author = it }
    from.description?.let { description = it }
    from.genre?.let { genre = it }
    if (from.status != SManga.UNKNOWN) status = from.status
    from.thumbnail_url?.let { thumbnail_url = it }
    initialized = true
}

internal fun SAnime.mergeDetails(from: SAnime): SAnime = apply {
    unsetAsNull { from.title }?.takeIf { it.isNotBlank() }?.let { title = it }
    from.artist?.let { artist = it }
    from.author?.let { author = it }
    from.description?.let { description = it }
    from.genre?.let { genre = it }
    if (from.status != SAnime.UNKNOWN) status = from.status
    from.thumbnail_url?.let { thumbnail_url = it }
    initialized = true
}
