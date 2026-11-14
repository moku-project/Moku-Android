package dev.moku.mobile.ui.search

import dev.moku.mobile.backend.BackendMedia

// Ported from Moku's core/util.ts so the phone and the desktop merge sources the same way.

private val EDITION_TAGS = Regex("""\(official\)|\(web comic\)|\(webtoon\)|\(manhwa\)|\(manhua\)""", RegexOption.IGNORE_CASE)

internal fun normalizeTitle(title: String): String = title.lowercase()
    .replace(EDITION_TAGS, "")
    .replace(Regex("[^a-z0-9\\s]"), " ")
    .replace(Regex("^(the|a|an)\\s+"), "")
    .replace(Regex("\\s+"), " ")
    .trim()

private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9]"), " ").replace(Regex("\\s+"), " ").trim()

private fun descFingerprint(desc: String?): String? = desc?.let(::norm)?.takeIf { it.length >= 60 }?.take(200)

private fun authorFingerprint(authors: List<String>, artists: List<String>): String? =
    (authors + artists).filter { it.isNotBlank() }.map(::norm).sorted().takeIf { it.isNotEmpty() }?.joinToString("|")

/** One entry per series across sources, matched by title, then description, then authors; a library copy wins. */
internal fun dedupeMedia(items: List<BackendMedia>): List<BackendMedia> {
    val byTitle = HashMap<String, Int>()
    val byDesc = HashMap<String, Int>()
    val byAuthorDesc = HashMap<String, Int>()
    val seenIds = HashSet<String>()
    val out = ArrayList<BackendMedia>()

    for (m in items) {
        if (!seenIds.add(m.originId + "\u0000" + m.id)) continue
        val tk = normalizeTitle(m.title)
        val dk = descFingerprint(m.description)
        val ak = if (dk != null && m.authors.isNotEmpty()) "${authorFingerprint(m.authors, m.artists)}||$dk" else null
        val existing = byTitle[tk] ?: dk?.let(byDesc::get) ?: ak?.let(byAuthorDesc::get)
        val index = if (existing != null) {
            if (!m.inLibrary || out[existing].inLibrary) continue
            out[existing] = m
            existing
        } else {
            out += m
            out.lastIndex
        }
        byTitle[tk] = index
        dk?.let { byDesc[it] = index }
        ak?.let { byAuthorDesc[it] = index }
    }
    return out
}

/** Takes one from each source in turn, so the first source to answer doesn't fill the whole top of the page. */
internal fun <T> interleave(lists: List<List<T>>): List<T> {
    val out = ArrayList<T>(lists.sumOf { it.size })
    var i = 0
    while (true) {
        var any = false
        for (list in lists) {
            if (i < list.size) {
                out += list[i]
                any = true
            }
        }
        if (!any) return out
        i++
    }
}
