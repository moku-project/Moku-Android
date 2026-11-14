package dev.moku.mobile.backend

import dev.moku.mobile.library.ContentFilterMatcher
import dev.moku.mobile.library.ContentFilterRule

/** Drops results the content filter hides at [level]. */
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
