package dev.moku.mobile.backend

import dev.moku.mobile.novel.LoadedNovelExtension

/** A novel plugin running on the phone in [dev.moku.mobile.novel.NovelJsRuntime]. */
class LocalNovelBackend(
    private val plugin: LoadedNovelExtension,
    packageName: String,
) : ContentBackend {

    override val originId: String = "local:novel:$packageName"
    override val displayName: String = plugin.name
    override val contentType: MediaContentType = MediaContentType.NOVEL

    val capabilities = ContentBackendCapabilities(supportsOffline = true, supportsFilters = false)

    override suspend fun popularMedia(page: Int): BackendPage<BackendMedia> {
        val items = plugin.popularNovels(page, showLatest = false)
        return BackendPage(items.map { it.toBackendMedia() }, hasNextPage = items.isNotEmpty())
    }

    override suspend fun latestUpdates(page: Int): BackendPage<BackendMedia> {
        val items = plugin.popularNovels(page, showLatest = true)
        return BackendPage(items.map { it.toBackendMedia() }, hasNextPage = items.isNotEmpty())
    }

    override suspend fun search(query: String, page: Int, filters: List<BackendFilter>): BackendPage<BackendMedia> {
        val items = plugin.searchNovels(query, page)
        return BackendPage(items.map { it.toBackendMedia() }, hasNextPage = items.isNotEmpty())
    }

    override suspend fun filterOptions(): List<BackendFilter> = emptyList()

    override suspend fun mediaDetails(mediaId: String): BackendMedia {
        val novel = plugin.parseNovel(mediaId)
        return BackendMedia(
            id = novel.path,
            originId = originId,
            contentType = MediaContentType.NOVEL,
            title = novel.name,
            thumbnailUrl = novel.cover,
            description = novel.summary,
            status = novel.status,
            authors = novel.author?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty(),
            genres = novel.genres?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty(),
        )
    }

    override suspend fun unitList(mediaId: String): List<BackendUnit> {
        val novel = plugin.parseNovel(mediaId)
        return novel.chapters.map { c ->
            BackendUnit(
                id = c.path,
                mediaId = mediaId,
                contentType = MediaContentType.NOVEL,
                title = c.name,
                number = c.chapterNumber?.toFloat(),
            )
        }
    }

    override suspend fun unitContent(mediaId: String, unitId: String): BackendUnitContent {
        return BackendUnitContent.Text(html = plugin.parseChapter(unitId))
    }

    private fun dev.moku.mobile.novel.NovelItem.toBackendMedia(): BackendMedia = BackendMedia(
        id = path,
        originId = originId,
        contentType = MediaContentType.NOVEL,
        title = name,
        thumbnailUrl = cover,
    )
}
