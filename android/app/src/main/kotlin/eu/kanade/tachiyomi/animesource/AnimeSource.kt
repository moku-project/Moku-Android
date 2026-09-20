// Vendored from Tsunagu's own JVM sandbox (sandbox/src/main/kotlin/eu/kanade/tachiyomi/animesource/),
// which already ported Aniyomi's animesource runtime for its (JVM, not Android) extension
// host. Reused verbatim where possible: Aniyomi extensions are real Android APK/dex, so
// the same DexClassLoader mechanism proven for manga (see ExtensionLoader) applies directly —
// this vendoring is "redo the manga spike with a second runtime," not a new problem.
package eu.kanade.tachiyomi.animesource

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import rx.Observable

interface AnimeSource {

    val id: Long

    val name: String

    val lang: String
        get() = ""

    val supportsLatest: Boolean

    suspend fun getPopularAnime(page: Int): AnimesPage

    suspend fun getLatestUpdates(page: Int): AnimesPage

    suspend fun getSearchAnime(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): AnimesPage

    suspend fun getAnimeDetails(anime: SAnime): SAnime

    suspend fun getEpisodeList(anime: SAnime): List<SEpisode>

    suspend fun getVideoList(episode: SEpisode): List<Video>

    fun getFilterList(): AnimeFilterList = AnimeFilterList()

    @Deprecated("Use the suspend API instead", ReplaceWith("getAnimeDetails"))
    fun fetchAnimeDetails(anime: SAnime): Observable<SAnime> = throw UnsupportedOperationException()

    @Deprecated("Use the suspend API instead", ReplaceWith("getEpisodeList"))
    fun fetchEpisodeList(anime: SAnime): Observable<List<SEpisode>> = throw UnsupportedOperationException()

    @Deprecated("Use the suspend API instead", ReplaceWith("getVideoList"))
    fun fetchVideoList(episode: SEpisode): Observable<List<Video>> = throw UnsupportedOperationException()
}
