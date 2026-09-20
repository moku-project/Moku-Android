package dev.moku.mobile.backend

import dev.moku.mobile.novel.LoadedNovelExtension

/**
 * [ContentBackend] wrapping one real Keiyoushi/LNReader novel plugin loaded on-device via
 * [dev.moku.mobile.novel.NovelPluginLoader] — the novel counterpart of [LocalMangaBackend]/
 * [LocalAnimeBackend]. Unlike those two (real Android APK/dex, DexClassLoader), novel
 * plugins are plain JS run inside a WebView-hosted runtime (see [dev.moku.mobile.novel.NovelJsRuntime]);
 * that's a genuinely different loading mechanism, not just a different vendored runtime,
 * which is why it's a separate backend class rather than a third variant of the other two.
 *
 * Known gaps versus the manga/anime backends: [filterOptions] returns nothing (per-source
 * filter schemas aren't wired up yet) and [unitContent] returns raw plugin-supplied HTML/text
 * with no htmlparser2-based plugins supported (see [dev.moku.mobile.novel.NovelBootstrapJs] kdoc).
 */
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
            authors = novel.author?.split(",")?.map { it.trim() }.orEmpty(),
            genres = novel.genres?.split(",")?.map { it.trim() }.orEmpty(),
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
