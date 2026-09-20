package dev.moku.mobile.backend

import dev.moku.mobile.library.ContentFilterMatcher
import dev.moku.mobile.library.ContentFilterRule

/**
 * Wraps any local [ContentBackend] and drops results [ContentFilterMatcher] says should be
 * hidden at the configured [level] — the local-mode equivalent of Tsunagu's server-side
 * `content_filter_level`/`recomputeContentFilter`. Deliberately a decorator rather than baking
 * filtering into each `Local*Backend`: those stay pure "talk to the extension" wrappers, and
 * filtering is app-level policy layered on top, same spirit as [dev.moku.mobile.library
 * .LocalLibraryRepository] living outside the backends rather than inside them.
 */
class FilteredContentBackend(
    private val delegate: ContentBackend,
    private val rules: List<ContentFilterRule>,
    private val level: Int,
) : ContentBackend by delegate {

    override suspend fun popularMedia(page: Int): BackendPage<BackendMedia> = delegate.popularMedia(page).filtered()
    override suspend fun latestUpdates(page: Int): BackendPage<BackendMedia> = delegate.latestUpdates(page).filtered()
    override suspend fun search(query: String, page: Int, filters: List<BackendFilter>): BackendPage<BackendMedia> =
        delegate.search(query, page, filters).filtered()

    private fun BackendPage<BackendMedia>.filtered(): BackendPage<BackendMedia> {
        if (level == 0 || rules.isEmpty()) return this
        val kept = items.filterNot { ContentFilterMatcher.isHidden(ContentFilterMatcher.blockRank(it, rules), level) }
        return BackendPage(kept, hasNextPage)
    }
}
