package dev.moku.mobile.backend

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga

/**
 * [ContentBackend] wrapping one real Keiyoushi `Source` loaded on-device via
 * [dev.moku.mobile.extension.ExtensionLoader] — the local, no-server counterpart to
 * [dev.moku.mobile.remote.RemoteTsunaguBackend]. Everything here runs the extension's
 * own network/parsing code directly on the phone; nothing goes through a Tsunagu server.
 *
 * Mangas/chapters are addressed by the source's own opaque `url` field (Tachiyomi's
 * convention — `SManga.url`/`SChapter.url` double as the stable id), so [BackendMedia.id]
 * and [BackendUnit.id] are just that url, not a separately-generated id like the remote
 * backend's DB-assigned ids.
 */
class LocalMangaBackend(
    private val source: Source,
    packageName: String,
) : ContentBackend {

    override val originId: String = "local:manga:$packageName"
    override val displayName: String = source.name
    override val contentType: MediaContentType = MediaContentType.MANGA

    val capabilities = ContentBackendCapabilities(supportsOffline = true)

    /** Mangas are addressed by url; we need the full SManga to make further source calls, so cache it. */
    private val mangaCache = LinkedHashMap<String, SManga>()
    private val chapterCache = LinkedHashMap<String, SChapter>()

    override suspend fun popularMedia(page: Int): BackendPage<BackendMedia> {
        val result = source.getPopularManga(page)
        return BackendPage(result.mangas.map { it.cacheAndConvert() }, result.hasNextPage)
    }

    override suspend fun latestUpdates(page: Int): BackendPage<BackendMedia> {
        val result = source.getLatestUpdates(page)
        return BackendPage(result.mangas.map { it.cacheAndConvert() }, result.hasNextPage)
    }

    override suspend fun search(query: String, page: Int, filters: List<BackendFilter>): BackendPage<BackendMedia> {
        val sourceFilters = if (filters.isEmpty()) source.getFilterList() else filters.toSourceFilterList()
        val result = source.getSearchManga(page, query, sourceFilters)
        return BackendPage(result.mangas.map { it.cacheAndConvert() }, result.hasNextPage)
    }

    override suspend fun filterOptions(): List<BackendFilter> = source.getFilterList().list.map { it.toBackendFilter() }

    override suspend fun mediaDetails(mediaId: String): BackendMedia {
        val manga = mangaCache[mediaId] ?: SManga.create().apply { url = mediaId }
        val update = source.getMangaUpdate(manga, emptyList(), fetchDetails = true, fetchChapters = false)
        return update.manga.cacheAndConvert()
    }

    override suspend fun unitList(mediaId: String): List<BackendUnit> {
        val manga = mangaCache[mediaId] ?: SManga.create().apply { url = mediaId }
        val update = source.getMangaUpdate(manga, emptyList(), fetchDetails = false, fetchChapters = true)
        return update.chapters.map { it.cacheAndConvert(mediaId) }
    }

    override suspend fun unitContent(mediaId: String, unitId: String): BackendUnitContent {
        val chapter = chapterCache[unitId] ?: SChapter.create().apply { url = unitId }
        val pages = source.getPageList(chapter)
        return BackendUnitContent.Pages(pages.mapIndexed { index, page -> page.toBackendPageImage(index) })
    }

    private fun SManga.cacheAndConvert(): BackendMedia {
        mangaCache[url] = this
        return BackendMedia(
            id = url,
            originId = originId,
            contentType = MediaContentType.MANGA,
            title = title,
            thumbnailUrl = thumbnail_url,
            description = description,
            status = status.toStatusString(),
            authors = author?.split(",")?.map { it.trim() }.orEmpty(),
            artists = artist?.split(",")?.map { it.trim() }.orEmpty(),
            genres = genre?.split(",")?.map { it.trim() }.orEmpty(),
            // "favorite"/library membership is app-level state (this app's own library DB,
            // not yet built), not something the extension's SManga model carries.
            inLibrary = false,
        )
    }

    private fun SChapter.cacheAndConvert(mediaId: String): BackendUnit {
        chapterCache[url] = this
        return BackendUnit(
            id = url,
            mediaId = mediaId,
            contentType = MediaContentType.MANGA,
            title = name,
            number = chapter_number,
            scanlator = scanlator,
            uploadedAt = date_upload,
        )
    }

    private fun Int.toStatusString(): String = when (this) {
        SManga.ONGOING -> "ONGOING"
        SManga.COMPLETED -> "COMPLETED"
        SManga.LICENSED -> "LICENSED"
        SManga.PUBLISHING_FINISHED -> "PUBLISHING_FINISHED"
        SManga.CANCELLED -> "CANCELLED"
        SManga.ON_HIATUS -> "ON_HIATUS"
        else -> "UNKNOWN"
    }

    private fun Page.toBackendPageImage(index: Int): BackendPageImage {
        // Local sources resolve the actual image URL lazily via imageUrl/fetchImageUrl in
        // some extensions; imageUrl is already populated for the common case (HttpSource
        // sets it directly from pageListParse). A reader consuming this should call back
        // into the source for imageUrl==null pages — left as a follow-up, not needed for
        // the sources tested so far (MangaTaro populates imageUrl directly).
        return BackendPageImage(index = index, imageUrl = imageUrl.orEmpty())
    }

    private fun Filter<*>.toBackendFilter(): BackendFilter = when (this) {
        is Filter.Header -> BackendFilter.Header(name)
        is Filter.Separator -> BackendFilter.Separator(name)
        is Filter.Select<*> -> BackendFilter.Select(name, values.map { it.toString() }, state)
        is Filter.Text -> BackendFilter.Text(name, state)
        is Filter.CheckBox -> BackendFilter.CheckBox(name, state)
        is Filter.TriState -> BackendFilter.TriState(name, state)
        is Filter.Sort -> BackendFilter.Sort(name, values.toList(), state?.index, state?.ascending)
        is Filter.Group<*> -> BackendFilter.Group(name, state.filterIsInstance<Filter<*>>().map { it.toBackendFilter() })
    }

    private fun List<BackendFilter>.toSourceFilterList(): FilterList {
        // Filters are stateful objects the source instance itself created (via getFilterList());
        // we mutate the same instances in place rather than reconstructing new Filter<*> objects,
        // since sources often close over their own filter instances in searchMangaRequest().
        val live = source.getFilterList()
        forEachIndexed { index, backendFilter -> applyState(live.list.getOrNull(index), backendFilter) }
        return live
    }

    private fun applyState(target: Filter<*>?, backendFilter: BackendFilter) {
        when {
            target is Filter.Select<*> && backendFilter is BackendFilter.Select -> {
                @Suppress("UNCHECKED_CAST")
                (target as Filter.Select<Any>).state = backendFilter.state
            }
            target is Filter.Text && backendFilter is BackendFilter.Text -> target.state = backendFilter.state
            target is Filter.CheckBox && backendFilter is BackendFilter.CheckBox -> target.state = backendFilter.state
            target is Filter.TriState && backendFilter is BackendFilter.TriState -> target.state = backendFilter.state
            target is Filter.Group<*> && backendFilter is BackendFilter.Group -> {
                backendFilter.children.forEachIndexed { i, child ->
                    applyState(target.state.filterIsInstance<Filter<*>>().getOrNull(i), child)
                }
            }
            else -> {}
        }
    }
}
