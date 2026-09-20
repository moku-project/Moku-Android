package dev.moku.mobile.backend

import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video

/**
 * [ContentBackend] wrapping one real Aniyomi `AnimeSource` loaded on-device via
 * [dev.moku.mobile.extension.ExtensionLoader.loadAnime] — the anime counterpart of
 * [LocalMangaBackend]. Same DexClassLoader mechanism, same Injekt-provided NetworkHelper,
 * a second vendored runtime (`eu.kanade.tachiyomi.animesource`) instead of `source`.
 *
 * "Chapters" (this app's [BackendUnit]) are Aniyomi's episodes; "pages" become a single
 * [BackendUnitContent.Video] resolved from the source's own `getVideoList()` — Aniyomi
 * sources routinely return several quality variants per episode, so [unitContent] picks
 * the source's own `preferred` pick (falling back to the first) rather than exposing a
 * quality-selection UI concept the shared [ContentBackend] interface doesn't model yet.
 */
class LocalAnimeBackend(
    private val source: AnimeSource,
    packageName: String,
) : ContentBackend {

    override val originId: String = "local:anime:$packageName"
    override val displayName: String = source.name
    override val contentType: MediaContentType = MediaContentType.ANIME

    val capabilities = ContentBackendCapabilities(supportsOffline = true)

    private val animeCache = LinkedHashMap<String, SAnime>()
    private val episodeCache = LinkedHashMap<String, SEpisode>()

    override suspend fun popularMedia(page: Int): BackendPage<BackendMedia> {
        val result = source.getPopularAnime(page)
        return BackendPage(result.animes.map { it.cacheAndConvert() }, result.hasNextPage)
    }

    override suspend fun latestUpdates(page: Int): BackendPage<BackendMedia> {
        val result = source.getLatestUpdates(page)
        return BackendPage(result.animes.map { it.cacheAndConvert() }, result.hasNextPage)
    }

    override suspend fun search(query: String, page: Int, filters: List<BackendFilter>): BackendPage<BackendMedia> {
        val sourceFilters = if (filters.isEmpty()) source.getFilterList() else filters.toSourceFilterList()
        val result = source.getSearchAnime(page, query, sourceFilters)
        return BackendPage(result.animes.map { it.cacheAndConvert() }, result.hasNextPage)
    }

    override suspend fun filterOptions(): List<BackendFilter> = source.getFilterList().list.map { it.toBackendFilter() }

    override suspend fun mediaDetails(mediaId: String): BackendMedia {
        val anime = animeCache[mediaId] ?: SAnime.create().apply { url = mediaId }
        return source.getAnimeDetails(anime).cacheAndConvert()
    }

    override suspend fun unitList(mediaId: String): List<BackendUnit> {
        val anime = animeCache[mediaId] ?: SAnime.create().apply { url = mediaId }
        return source.getEpisodeList(anime).map { it.cacheAndConvert(mediaId) }
    }

    override suspend fun unitContent(mediaId: String, unitId: String): BackendUnitContent {
        val episode = episodeCache[unitId] ?: SEpisode.create().apply { url = unitId }
        val videos = source.getVideoList(episode)
        val video = videos.firstOrNull { it.preferred } ?: videos.firstOrNull()
            ?: return BackendUnitContent.Video(streamUrl = "")
        return BackendUnitContent.Video(
            streamUrl = video.videoUrl,
            headers = video.headers?.toMap().orEmpty(),
            subtitleUrl = video.subtitleTracks.firstOrNull()?.url,
            qualities = videos.map { VideoVariant(label = it.videoTitle, url = it.videoUrl, headers = it.headers?.toMap().orEmpty()) },
        )
    }

    private fun okhttp3.Headers.toMap(): Map<String, String> = names().associateWith { get(it).orEmpty() }

    private fun SAnime.cacheAndConvert(): BackendMedia {
        animeCache[url] = this
        return BackendMedia(
            id = url,
            originId = originId,
            contentType = MediaContentType.ANIME,
            title = title,
            thumbnailUrl = thumbnail_url,
            description = description,
            status = status.toStatusString(),
            authors = author?.split(",")?.map { it.trim() }.orEmpty(),
            artists = artist?.split(",")?.map { it.trim() }.orEmpty(),
            genres = genre?.split(",")?.map { it.trim() }.orEmpty(),
            inLibrary = false,
        )
    }

    private fun SEpisode.cacheAndConvert(mediaId: String): BackendUnit {
        episodeCache[url] = this
        return BackendUnit(
            id = url,
            mediaId = mediaId,
            contentType = MediaContentType.ANIME,
            title = name,
            number = episode_number,
            scanlator = scanlator,
            uploadedAt = date_upload,
        )
    }

    private fun Int.toStatusString(): String = when (this) {
        SAnime.ONGOING -> "ONGOING"
        SAnime.COMPLETED -> "COMPLETED"
        SAnime.LICENSED -> "LICENSED"
        SAnime.PUBLISHING_FINISHED -> "PUBLISHING_FINISHED"
        SAnime.CANCELLED -> "CANCELLED"
        SAnime.ON_HIATUS -> "ON_HIATUS"
        else -> "UNKNOWN"
    }

    private fun AnimeFilter<*>.toBackendFilter(): BackendFilter = when (this) {
        is AnimeFilter.Header -> BackendFilter.Header(name)
        is AnimeFilter.Separator -> BackendFilter.Separator(name)
        is AnimeFilter.Select<*> -> BackendFilter.Select(name, values.map { it.toString() }, state)
        is AnimeFilter.Text -> BackendFilter.Text(name, state)
        is AnimeFilter.CheckBox -> BackendFilter.CheckBox(name, state)
        is AnimeFilter.TriState -> BackendFilter.TriState(name, state)
        is AnimeFilter.Sort -> BackendFilter.Sort(name, values.toList(), state?.index, state?.ascending)
        is AnimeFilter.Group<*> -> BackendFilter.Group(name, state.filterIsInstance<AnimeFilter<*>>().map { it.toBackendFilter() })
    }

    private fun List<BackendFilter>.toSourceFilterList(): AnimeFilterList {
        val live = source.getFilterList()
        forEachIndexed { index, backendFilter -> applyState(live.list.getOrNull(index), backendFilter) }
        return live
    }

    private fun applyState(target: AnimeFilter<*>?, backendFilter: BackendFilter) {
        when {
            target is AnimeFilter.Select<*> && backendFilter is BackendFilter.Select -> {
                @Suppress("UNCHECKED_CAST")
                (target as AnimeFilter.Select<Any>).state = backendFilter.state
            }
            target is AnimeFilter.Text && backendFilter is BackendFilter.Text -> target.state = backendFilter.state
            target is AnimeFilter.CheckBox && backendFilter is BackendFilter.CheckBox -> target.state = backendFilter.state
            target is AnimeFilter.TriState && backendFilter is BackendFilter.TriState -> target.state = backendFilter.state
            target is AnimeFilter.Group<*> && backendFilter is BackendFilter.Group -> {
                backendFilter.children.forEachIndexed { i, child ->
                    applyState(target.state.filterIsInstance<AnimeFilter<*>>().getOrNull(i), child)
                }
            }
            else -> {}
        }
    }
}
