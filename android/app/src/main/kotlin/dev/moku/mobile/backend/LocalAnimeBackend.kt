package dev.moku.mobile.backend

import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import dev.moku.mobile.core.AnimeVideoLoader

/** An anime extension running on the phone. An episode plays the source's preferred video, else its first. */
class LocalAnimeBackend(
    private val source: AnimeSource,
    packageName: String,
) : ContentBackend {

    override val originId: String = "local:anime:$packageName"
    override val displayName: String = source.name
    override val contentType: MediaContentType = MediaContentType.ANIME

    val capabilities = ContentBackendCapabilities(supportsOffline = true)


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
        val anime = SourceEntries.get<SAnime>(originId, "media", mediaId) ?: SAnime.create().apply { url = mediaId }
        return anime.mergeDetails(source.getAnimeDetails(anime)).cacheAndConvert()
    }

    override suspend fun unitList(mediaId: String): List<BackendUnit> {
        val anime = SourceEntries.get<SAnime>(originId, "media", mediaId) ?: SAnime.create().apply { url = mediaId }
        return source.getEpisodeList(anime).map { it.cacheAndConvert(mediaId) }
    }

    @Suppress("DEPRECATION")
    override suspend fun unitContent(mediaId: String, unitId: String): BackendUnitContent {
        val episode = SourceEntries.get<SEpisode>(originId, "unit", unitId) ?: SEpisode.create().apply { url = unitId }
        val http = source as? AnimeHttpSource
        val videos = if (http != null) {
            AnimeVideoLoader.loadVideos(http, episode)
        } else {
            source.getVideoList(episode).filter { it.videoUrl.isNotBlank() && it.videoUrl != "null" }
        }
        val video = videos.firstOrNull { it.preferred } ?: videos.firstOrNull()
            ?: return BackendUnitContent.Video(streamUrl = "")
        fun Video.headerMap() = (headers ?: http?.headers)?.toMap().orEmpty()
        // Every source's subtitles are offered, each with its video's headers since subtitle hosts check the same Referer.
        val subtitles = (listOf(video) + videos).flatMap { v -> v.subtitleTracks.map { SubtitleTrack(it.lang, it.url, v.headerMap()) } }
            .filter { it.url.isNotBlank() }
            .distinctBy { it.url }
        return BackendUnitContent.Video(
            streamUrl = video.videoUrl,
            headers = video.headerMap(),
            qualities = videos.map { VideoVariant(label = it.videoTitle, url = it.videoUrl, headers = it.headerMap()) },
            subtitles = subtitles,
            skipMarkers = video.timestamps.map { SkipMarker(it.name.ifBlank { it.type.name }, (it.start * 1000).toLong(), (it.end * 1000).toLong()) },
            sourceClient = http?.client,
        )
    }

    private fun okhttp3.Headers.toMap(): Map<String, String> = names().associateWith { get(it).orEmpty() }

    private fun SAnime.cacheAndConvert(): BackendMedia {
        (source as? AnimeHttpSource)?.let { CoverHeaders.remember(thumbnail_url, it.headers.names().associateWith { n -> it.headers[n].orEmpty() }) }
        SourceEntries.put(originId, "media", url, this)
        return BackendMedia(
            id = url,
            originId = originId,
            contentType = MediaContentType.ANIME,
            title = unsetAsNull { title }.orEmpty(),
            thumbnailUrl = thumbnail_url,
            description = description,
            status = status.toStatusString(),
            authors = author?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty(),
            artists = artist?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty(),
            genres = genre?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty(),
            inLibrary = false,
        )
    }

    private fun SEpisode.cacheAndConvert(mediaId: String): BackendUnit {
        SourceEntries.put(originId, "unit", url, this)
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
