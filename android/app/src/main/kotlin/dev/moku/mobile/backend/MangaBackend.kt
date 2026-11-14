package dev.moku.mobile.backend

/** Matches Tsunagu's ContentType. */
enum class MediaContentType { MANGA, ANIME, NOVEL }

/** A source of titles: an extension running on the phone, or one on a Tsunagu server. */
interface ContentBackend {

    /** Where titles come from: `local:<type>:<packageName>` or `server:<instanceId>:<type>:<extensionId>`. */
    val originId: String

    val displayName: String

    val contentType: MediaContentType

    suspend fun popularMedia(page: Int): BackendPage<BackendMedia>

    suspend fun latestUpdates(page: Int): BackendPage<BackendMedia>

    suspend fun search(query: String, page: Int, filters: List<BackendFilter> = emptyList()): BackendPage<BackendMedia>

    suspend fun filterOptions(): List<BackendFilter>

    suspend fun mediaDetails(mediaId: String): BackendMedia

    /** Chapters for manga/novels, episodes for anime — same shape, [BackendUnit.contentType] disambiguates. */
    suspend fun unitList(mediaId: String): List<BackendUnit>

    /** The actual payload for one chapter/episode: image pages, a video stream, or novel text. */
    suspend fun unitContent(mediaId: String, unitId: String): BackendUnitContent
}

/** What a backend can do, so the UI only offers what works. */
data class ContentBackendCapabilities(
    val supportsOffline: Boolean,
    val supportsFilters: Boolean = true,
)

data class BackendPage<T>(val items: List<T>, val hasNextPage: Boolean)

data class BackendMedia(
    val id: String,
    val originId: String,
    val contentType: MediaContentType,
    val title: String,
    val thumbnailUrl: String?,
    val description: String? = null,
    val status: String? = null,
    val authors: List<String> = emptyList(),
    val artists: List<String> = emptyList(),
    val genres: List<String> = emptyList(),
    val inLibrary: Boolean = false,
)

data class BackendUnit(
    val id: String,
    val mediaId: String,
    val contentType: MediaContentType,
    val title: String?,
    val number: Float?,
    val scanlator: String? = null,
    val uploadedAt: Long? = null,
)

sealed interface BackendUnitContent {
    data class Pages(val pages: List<BackendPageImage>) : BackendUnitContent
    data class Video(
        val streamUrl: String,
        val headers: Map<String, String> = emptyMap(),
        val subtitleUrl: String? = null,
        val qualities: List<VideoVariant> = emptyList(),
        val subtitles: List<SubtitleTrack> = emptyList(),
    ) : BackendUnitContent
    data class Text(val html: String) : BackendUnitContent
}

data class VideoVariant(val label: String, val url: String, val headers: Map<String, String> = emptyMap())

data class SubtitleTrack(val label: String, val url: String)

data class BackendPageImage(
    val index: Int,
    val imageUrl: String,
    val headers: Map<String, String> = emptyMap(),
    // Fetches through the source's own client so its interceptors and image request headers apply.
    val open: (suspend () -> okhttp3.Response)? = null,
)

/** Mirrors Tsunagu's `FilterNode`/Tachiyomi's `Filter<*>` sealed hierarchy closely enough to translate either direction. */
sealed interface BackendFilter {
    val name: String

    data class Header(override val name: String) : BackendFilter
    data class Separator(override val name: String) : BackendFilter
    data class Select(override val name: String, val values: List<String>, val state: Int) : BackendFilter
    data class Text(override val name: String, val state: String) : BackendFilter
    data class CheckBox(override val name: String, val state: Boolean) : BackendFilter
    data class TriState(override val name: String, val state: Int) : BackendFilter
    data class Sort(override val name: String, val values: List<String>, val index: Int?, val ascending: Boolean?) : BackendFilter
    data class Group(override val name: String, val children: List<BackendFilter>) : BackendFilter
}
