package dev.moku.mobile.core

import android.content.Context
import android.util.Log
import dev.moku.mobile.extension.ExtensionLoadException
import dev.moku.mobile.extension.ExtensionLoader
import dev.moku.mobile.novel.LoadedNovelExtension
import dev.moku.mobile.novel.NovelItem
import dev.moku.mobile.novel.NovelPluginLoader
import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import sandbox.v1.ExtensionServiceGrpc
import sandbox.v1.Sandbox
import uy.kohesive.injekt.injectLazy
import java.io.File
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "ExtensionHost"
private val RESOLUTION = Regex("""(\d{3,4})\s*[pP]""")

class ExtensionHost(private val context: Context) : ExtensionServiceGrpc.ExtensionServiceImplBase() {

    private class Hosted(val packageName: String, val version: String, val source: Any) {
        val contentType: Sandbox.ContentType
            get() = when (source) {
                is AnimeSource -> Sandbox.ContentType.ANIME
                is LoadedNovelExtension -> Sandbox.ContentType.NOVEL
                else -> Sandbox.ContentType.MANGA
            }
    }

    private val network: NetworkHelper by injectLazy()

    private val loaded = ConcurrentHashMap<String, Hosted>()

    // Sources need the full model a listing returned, not just its url, for later calls.
    private val mangas = ConcurrentHashMap<String, SManga>()
    private val chapters = ConcurrentHashMap<String, SChapter>()
    private val animes = ConcurrentHashMap<String, SAnime>()
    private val episodes = ConcurrentHashMap<String, SEpisode>()

    private val codeDir: File
        get() = File(context.filesDir, "host-extensions").apply { mkdirs() }

    override fun loadExtensions(request: Sandbox.LoadExtensionsRequest, out: StreamObserver<Sandbox.ExtensionList>) {
        val list = Sandbox.ExtensionList.newBuilder()
        for (toLoad in request.extensionsList) {
            runCatching { load(File(toLoad.jarPath), toLoad.extensionId, toLoad.lang) }
                .onSuccess { list.addExtensions(it.toProto()) }
                .onFailure { Log.w(TAG, "loading ${toLoad.extensionId} failed", it) }
        }
        out.reply(list.build())
    }

    override fun listLoadedExtensions(request: Sandbox.Empty, out: StreamObserver<Sandbox.ExtensionList>) {
        out.reply(Sandbox.ExtensionList.newBuilder().addAllExtensions(loaded.values.map { it.toProto() }).build())
    }

    override fun unloadExtension(request: Sandbox.ExtensionRequest, out: StreamObserver<Sandbox.Empty>) {
        loaded.remove(request.extensionId)
        out.reply(Sandbox.Empty.getDefaultInstance())
    }

    override fun peekExtension(request: Sandbox.PeekExtensionRequest, out: StreamObserver<Sandbox.ExtensionMetadata>) = call(out) {
        val file = File(request.filePath)
        if (!file.exists()) throw StatusRuntimeException(Status.NOT_FOUND.withDescription("file not found: ${request.filePath}"))
        val hosted = if (file.extension == "js") {
            // A plugin's id is only known once its code has run.
            val plugin = runBlocking { NovelPluginLoader.load(context, file.nameWithoutExtension, file.readText()) }
            Hosted(plugin.id, plugin.version, plugin).also { loaded[plugin.id] = it }
        } else {
            val packageName = context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)?.packageName
                ?: throw StatusRuntimeException(Status.INVALID_ARGUMENT.withDescription("not an extension APK: ${file.name}"))
            load(file, packageName, "")
        }
        Sandbox.ExtensionMetadata.newBuilder()
            .setPackageName(hosted.packageName)
            .setName(hosted.source.sourceName())
            .setContentType(hosted.contentType)
            .setLang(hosted.source.sourceLang())
            .build()
    }

    override fun search(request: Sandbox.SearchRequest, out: StreamObserver<Sandbox.SearchResponse>) = call(out) {
        when (val source = sourceOf(request.extensionId)) {
            is CatalogueSource -> runBlocking {
                val page = source.getSearchManga(request.page, request.query, source.getFilterList())
                mangaPage(request.extensionId, page.mangas, page.hasNextPage)
            }
            is AnimeCatalogueSource -> runBlocking {
                val page = source.getSearchAnime(request.page, request.query, source.getFilterList())
                animePage(request.extensionId, page.animes, page.hasNextPage)
            }
            is LoadedNovelExtension -> novelPage(runBlocking { source.searchNovels(request.query, request.page) })
            else -> throw unimplemented()
        }
    }

    override fun getPopularManga(request: Sandbox.BrowseRequest, out: StreamObserver<Sandbox.SearchResponse>) = call(out) {
        when (val source = sourceOf(request.extensionId)) {
            is CatalogueSource -> runBlocking {
                val page = source.getPopularManga(request.page)
                mangaPage(request.extensionId, page.mangas, page.hasNextPage)
            }
            is AnimeCatalogueSource -> runBlocking {
                val page = source.getPopularAnime(request.page)
                animePage(request.extensionId, page.animes, page.hasNextPage)
            }
            is LoadedNovelExtension -> novelPage(runBlocking { source.popularNovels(request.page) })
            else -> throw unimplemented()
        }
    }

    override fun getLatestUpdates(request: Sandbox.BrowseRequest, out: StreamObserver<Sandbox.SearchResponse>) = call(out) {
        when (val source = sourceOf(request.extensionId)) {
            is CatalogueSource -> runBlocking {
                val page = source.getLatestUpdates(request.page)
                mangaPage(request.extensionId, page.mangas, page.hasNextPage)
            }
            is AnimeCatalogueSource -> runBlocking {
                val page = source.getLatestUpdates(request.page)
                animePage(request.extensionId, page.animes, page.hasNextPage)
            }
            is LoadedNovelExtension -> novelPage(runBlocking { source.popularNovels(request.page, showLatest = true) })
            else -> throw unimplemented()
        }
    }

    override fun getFilterList(request: Sandbox.GetFilterListRequest, out: StreamObserver<Sandbox.GetFilterListResponse>) {
        out.reply(Sandbox.GetFilterListResponse.getDefaultInstance())
    }

    override fun getDetails(request: Sandbox.EntryRequest, out: StreamObserver<Sandbox.EntryDetails>) = call(out) {
        val key = entryKey(request.extensionId, request.sourceEntryId)
        when (val source = sourceOf(request.extensionId)) {
            is Source -> runBlocking {
                val stub = mangas[key] ?: SManga.create().apply { url = request.sourceEntryId }
                val manga = source.getMangaUpdate(stub, emptyList(), fetchDetails = true, fetchChapters = false).manga
                manga.url = request.sourceEntryId
                mangas[key] = manga
                manga.toDetails()
            }
            is AnimeSource -> runBlocking {
                val stub = animes[key] ?: SAnime.create().apply { url = request.sourceEntryId }
                val anime = source.getAnimeDetails(stub)
                anime.url = request.sourceEntryId
                animes[key] = anime
                anime.toDetails()
            }
            is LoadedNovelExtension -> {
                val novel = runBlocking { source.parseNovel(request.sourceEntryId) }
                Sandbox.EntryDetails.newBuilder()
                    .setSourceEntryId(novel.path)
                    .setTitle(novel.name)
                    .setDescription(novel.summary ?: "")
                    .setCoverUrl(novel.cover ?: "")
                    .addAllAuthors(listOfNotNull(novel.author).filter { it.isNotBlank() })
                    .addAllGenres(splitGenres(novel.genres))
                    .setStatus(novel.status ?: "")
                    .build()
            }
            else -> throw unimplemented()
        }
    }

    override fun getChapters(request: Sandbox.EntryRequest, out: StreamObserver<Sandbox.ChapterList>) = call(out) {
        val novel = sourceOf(request.extensionId) as? LoadedNovelExtension
        if (novel != null) {
            val chapters = runBlocking { novel.parseNovel(request.sourceEntryId) }.chapters
            return@call Sandbox.ChapterList.newBuilder().addAllChapters(
                chapters.map {
                    Sandbox.ChapterSummary.newBuilder()
                        .setSourceChapterId(it.path)
                        .setName(it.name)
                        .setNumber(it.chapterNumber ?: 0.0)
                        .build()
                },
            ).build()
        }
        val source = sourceOf(request.extensionId) as? Source ?: throw unimplemented()
        val key = entryKey(request.extensionId, request.sourceEntryId)
        val stub = mangas[key] ?: SManga.create().apply { url = request.sourceEntryId }
        val list = runBlocking { source.getMangaUpdate(stub, emptyList(), fetchDetails = false, fetchChapters = true).chapters }
        val builder = Sandbox.ChapterList.newBuilder()
        for (chapter in list) {
            chapters[entryKey(key, chapter.url)] = chapter
            builder.addChapters(
                Sandbox.ChapterSummary.newBuilder()
                    .setSourceChapterId(chapter.url)
                    .setName(chapter.name)
                    // Widening the Float directly turns 19.1f into 19.100000381469727.
                    .setNumber(chapter.chapter_number.toString().toDouble())
                    .setUploadTimestamp(chapter.date_upload),
            )
        }
        builder.build()
    }

    override fun getPages(request: Sandbox.ChapterRequest, out: StreamObserver<Sandbox.PageList>) = call(out) {
        val source = sourceOf(request.extensionId) as? HttpSource ?: throw unimplemented()
        val key = entryKey(entryKey(request.extensionId, request.sourceEntryId), request.sourceChapterId)
        val chapter = chapters[key] ?: SChapter.create().apply { url = request.sourceChapterId; name = "" }
        val urls = runBlocking {
            val slots = Semaphore(3)
            source.getPageList(chapter).map { page ->
                async(Dispatchers.IO) {
                    if (page.imageUrl == null) page.imageUrl = slots.withPermit { source.getImageUrl(page) }
                    source.resolveImageUrl(page)
                }
            }.awaitAll()
        }
        Sandbox.PageList.newBuilder().addAllPageUrls(urls).build()
    }

    override fun getEpisodes(request: Sandbox.EntryRequest, out: StreamObserver<Sandbox.EpisodeList>) = call(out) {
        val source = sourceOf(request.extensionId) as? AnimeSource ?: throw unimplemented()
        val key = entryKey(request.extensionId, request.sourceEntryId)
        val stub = animes[key] ?: SAnime.create().apply { url = request.sourceEntryId }
        val list = runBlocking { source.getEpisodeList(stub) }
        val builder = Sandbox.EpisodeList.newBuilder()
        for (episode in list) {
            episodes[entryKey(key, episode.url)] = episode
            builder.addEpisodes(
                Sandbox.EpisodeSummary.newBuilder()
                    .setSourceEpisodeId(episode.url)
                    .setName(episode.name)
                    .setNumber(episode.episode_number.toString().toDouble())
                    .setUploadTimestamp(episode.date_upload),
            )
        }
        builder.build()
    }

    override fun getVideoStream(request: Sandbox.EpisodeRequest, out: StreamObserver<Sandbox.StreamInfo>) = call(out) {
        val source = sourceOf(request.extensionId) as? AnimeHttpSource ?: throw unimplemented()
        val key = entryKey(entryKey(request.extensionId, request.sourceEntryId), request.sourceEpisodeId)
        val episode = episodes[key] ?: SEpisode.create().apply { url = request.sourceEpisodeId; name = "" }
        val videos = runBlocking { AnimeVideoLoader.loadVideos(source, episode) }
        val preferred = videos.firstOrNull { it.preferred }
            ?: videos.firstOrNull { "localhost" in it.videoUrl || "127.0.0.1" in it.videoUrl }
            ?: videos.firstOrNull()
            ?: throw StatusRuntimeException(Status.NOT_FOUND.withDescription("no videos for episode ${request.sourceEpisodeId}"))
        streamInfo(preferred, videos)
    }

    private fun streamInfo(preferred: Video, all: List<Video>): Sandbox.StreamInfo {
        val info = Sandbox.StreamInfo.newBuilder()
            .setStreamUrl(preferred.videoUrl)
            .setQuality(preferred.videoTitle)
            .addAllSubtitles(subtitles(preferred))
            .putAllHeaders(headers(preferred))
        for (video in all) {
            info.addSources(
                Sandbox.VideoSource.newBuilder()
                    .setUrl(video.videoUrl)
                    .setLabel(video.videoTitle)
                    .setResolution(video.resolution ?: RESOLUTION.find(video.videoTitle)?.groupValues?.get(1)?.toIntOrNull() ?: 0)
                    .setPreferred(video === preferred || video.preferred)
                    .addAllSubtitles(subtitles(video))
                    .putAllHeaders(headers(video)),
            )
        }
        for (track in preferred.audioTracks) {
            info.addAudioTracks(Sandbox.AudioTrack.newBuilder().setUrl(track.url).setLang(track.lang))
        }
        for (ts in preferred.timestamps) {
            info.addTimestamps(
                Sandbox.SkipTimestamp.newBuilder()
                    .setStartMs((ts.start * 1000).toLong())
                    .setEndMs((ts.end * 1000).toLong())
                    .setName(ts.name)
                    .setType(ts.type.name.uppercase()),
            )
        }
        return info.build()
    }

    private fun subtitles(video: Video): List<Sandbox.SubtitleTrack> =
        video.subtitleTracks.map { Sandbox.SubtitleTrack.newBuilder().setUrl(it.url).setLang(it.lang).build() }

    private fun headers(video: Video): Map<String, String> =
        video.headers?.let { h -> h.names().associateWith { h[it] ?: "" } } ?: emptyMap()

    override fun getImageBytes(request: Sandbox.ImageRequest, out: StreamObserver<Sandbox.ImageData>) = call(out) {
        val (client, headers) = when (val source = sourceOf(request.extensionId)) {
            is HttpSource -> source.client to source.headers
            is AnimeHttpSource -> source.client to source.headers
            is LoadedNovelExtension -> network.client to okhttp3.Headers.headersOf("Referer", source.site)
            else -> throw unimplemented()
        }
        client.newCall(GET(request.imageUrl, headers)).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw StatusRuntimeException(Status.UNAVAILABLE.withDescription("image HTTP ${resp.code}"))
            }
            Sandbox.ImageData.newBuilder()
                .setData(com.google.protobuf.ByteString.copyFrom(resp.body.bytes()))
                .setContentType(resp.header("Content-Type") ?: "image/jpeg")
                .build()
        }
    }

    override fun getChapterText(request: Sandbox.ChapterRequest, out: StreamObserver<Sandbox.TextContent>) = call(out) {
        val novel = sourceOf(request.extensionId) as? LoadedNovelExtension ?: throw unimplemented()
        val html = runBlocking { novel.parseChapter(request.sourceChapterId) }
        Sandbox.TextContent.newBuilder().setContent(html).setFormat("html").build()
    }

    override fun getSourcePreferences(request: Sandbox.GetSourcePreferencesRequest, out: StreamObserver<Sandbox.GetSourcePreferencesResponse>) {
        out.reply(Sandbox.GetSourcePreferencesResponse.getDefaultInstance())
    }

    override fun setSourcePreference(request: Sandbox.SetSourcePreferenceRequest, out: StreamObserver<Sandbox.Empty>) {
        out.onError(unimplemented())
    }

    private fun load(apk: File, extensionId: String, lang: String): Hosted {
        if (apk.extension == "js") {
            val plugin = runBlocking { NovelPluginLoader.load(context, extensionId, apk.readText()) }
            return Hosted(extensionId, plugin.version, plugin).also { loaded[extensionId] = it }
        }

        // Android only loads code from files the app can't write, so each APK gets a read-only copy.
        val copy = File(codeDir, "$extensionId.apk")
        if (copy.exists()) {
            copy.setWritable(true, false)
            copy.delete()
        }
        apk.copyTo(copy)
        copy.setWritable(false, false)

        val hosted = try {
            val ext = ExtensionLoader.loadManga(context, copy)
            Hosted(ext.packageName, ext.versionName, ext.sources.pick(lang) { it.lang })
        } catch (e: ExtensionLoadException) {
            val ext = runCatching { ExtensionLoader.loadAnime(context, copy) }.getOrElse { throw e }
            Hosted(ext.packageName, ext.versionName, ext.sources.pick(lang) { (it as? AnimeCatalogueSource)?.lang ?: "" })
        }
        loaded[extensionId] = hosted
        return hosted
    }

    // Multi-language extensions ship one source per language.
    private fun <T> List<T>.pick(lang: String, langOf: (T) -> String): T =
        firstOrNull { lang.isNotEmpty() && langOf(it) == lang } ?: firstOrNull { langOf(it) == "en" } ?: first()

    private fun sourceOf(extensionId: String): Any =
        loaded[extensionId]?.source
            ?: throw StatusRuntimeException(Status.NOT_FOUND.withDescription("extension not found: $extensionId"))

    private fun mangaPage(extensionId: String, list: List<SManga>, hasNext: Boolean): Sandbox.SearchResponse {
        val builder = Sandbox.SearchResponse.newBuilder().setHasNextPage(hasNext)
        for (manga in list) {
            mangas[entryKey(extensionId, manga.url)] = manga
            builder.addResults(
                Sandbox.EntrySummary.newBuilder()
                    .setSourceEntryId(manga.url)
                    .setTitle(manga.title)
                    .setCoverUrl(manga.thumbnail_url ?: "")
                    .addAllGenres(splitGenres(manga.genre)),
            )
        }
        return builder.build()
    }

    private fun novelPage(list: List<NovelItem>): Sandbox.SearchResponse =
        Sandbox.SearchResponse.newBuilder()
            .setHasNextPage(list.isNotEmpty())
            .addAllResults(
                list.map {
                    Sandbox.EntrySummary.newBuilder().setSourceEntryId(it.path).setTitle(it.name).setCoverUrl(it.cover ?: "").build()
                },
            )
            .build()

    private fun animePage(extensionId: String, list: List<SAnime>, hasNext: Boolean): Sandbox.SearchResponse {
        val builder = Sandbox.SearchResponse.newBuilder().setHasNextPage(hasNext)
        for (anime in list) {
            animes[entryKey(extensionId, anime.url)] = anime
            builder.addResults(
                Sandbox.EntrySummary.newBuilder()
                    .setSourceEntryId(anime.url)
                    .setTitle(anime.title)
                    .setCoverUrl(anime.thumbnail_url ?: "")
                    .addAllGenres(splitGenres(anime.genre)),
            )
        }
        return builder.build()
    }

    private fun SManga.toDetails(): Sandbox.EntryDetails =
        Sandbox.EntryDetails.newBuilder()
            .setSourceEntryId(url)
            .setTitle(title)
            .setDescription(description ?: "")
            .setCoverUrl(thumbnail_url ?: "")
            .addAllAuthors(listOfNotNull(author).filter { it.isNotBlank() })
            .addAllArtists(listOfNotNull(artist).filter { it.isNotBlank() })
            .addAllGenres(splitGenres(genre))
            .setStatus(statusName(status))
            .build()

    private fun SAnime.toDetails(): Sandbox.EntryDetails =
        Sandbox.EntryDetails.newBuilder()
            .setSourceEntryId(url)
            .setTitle(title)
            .setDescription(description ?: "")
            .setCoverUrl(thumbnail_url ?: "")
            .addAllAuthors(listOfNotNull(author).filter { it.isNotBlank() })
            .addAllGenres(splitGenres(genre))
            .setStatus(statusName(status))
            .build()

    private fun Hosted.toProto(): Sandbox.Extension =
        Sandbox.Extension.newBuilder()
            .setId(packageName)
            .setName(packageName)
            .setVersion(version)
            .setContentType(contentType)
            .setLang(source.sourceLang())
            .setSupportsLatest(
                when (source) {
                    is CatalogueSource -> source.supportsLatest
                    is AnimeCatalogueSource -> source.supportsLatest
                    else -> false
                },
            )
            .setSourceId(
                when (source) {
                    is Source -> source.id
                    is AnimeSource -> source.id
                    else -> 0L
                },
            )
            .build()

    private fun Any.sourceName(): String = when (this) {
        is Source -> name
        is AnimeSource -> name
        is LoadedNovelExtension -> name
        else -> ""
    }

    private fun Any.sourceLang(): String = when (this) {
        is CatalogueSource -> lang
        is AnimeCatalogueSource -> lang
        is LoadedNovelExtension -> lang
        else -> "all"
    }
    private fun statusName(status: Int): String = when (status) {
        SManga.ONGOING -> "Ongoing"
        SManga.COMPLETED -> "Completed"
        SManga.LICENSED -> "Licensed"
        SManga.PUBLISHING_FINISHED -> "Publishing Finished"
        SManga.CANCELLED -> "Cancelled"
        SManga.ON_HIATUS -> "On Hiatus"
        else -> ""
    }

    private fun splitGenres(genre: String?): List<String> =
        genre.orEmpty().split(Regex("[,;/|]")).map { it.trim() }.filter { it.isNotEmpty() }

    private fun entryKey(scope: String, id: String) = "$scope\u0000$id"

    private fun unimplemented() = StatusRuntimeException(Status.UNIMPLEMENTED)

    private fun <T> call(out: StreamObserver<T>, block: () -> T) {
        val result = try {
            block()
        } catch (e: StatusRuntimeException) {
            out.onError(e)
            return
        } catch (e: Throwable) {
            Log.w(TAG, "extension call failed", e)
            out.onError(StatusRuntimeException(Status.INTERNAL.withDescription(e.message ?: e.javaClass.simpleName).withCause(e)))
            return
        }
        out.reply(result)
    }

    private fun <T> StreamObserver<T>.reply(value: T) {
        onNext(value)
        onCompleted()
    }
}
