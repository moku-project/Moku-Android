package eu.kanade.tachiyomi.util

import java.net.URI

/** Strips scheme and host from a URL, keeping path, query and fragment as sources store them. */
fun sourceUrlWithoutDomain(value: String): String = try {
    val uri = URI(value)
    if (uri.rawAuthority == null) {
        value
    } else {
        buildString {
            append(uri.rawPath.orEmpty())
            uri.rawQuery?.let { append('?').append(it) }
            uri.rawFragment?.let { append('#').append(it) }
        }
    }
} catch (_: Exception) {
    value
}
