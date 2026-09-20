package dev.moku.mobile.backend

/**
 * Mirrors Tsunagu's own `ContentType` (see `server-adapters/types.ts` on the desktop
 * side and the GraphQL schema's `ContentType` enum) so a [BackendMedia] from either
 * backend slots into the same library without translation.
 */
enum class MediaContentType { MANGA, ANIME, NOVEL }

/**
 * Backend-agnostic shape the rest of the app (library, reader, player, browse UI) is
 * written against. Two kinds of backend implement this:
 *
 *  - **Local** backends load a real Keiyoushi/Aniyomi extension APK directly on-device
 *    via `DexClassLoader` (see [dev.moku.mobile.extension] — proven end-to-end for
 *    manga already; anime uses the exact same mechanism against a second vendored
 *    runtime, `eu.kanade.tachiyomi.animesource`, since Aniyomi extensions are also
 *    plain Android APKs). Novel sources are the one content type Tsunagu itself runs
 *    as JS plugins through GraalVM — not something ART can host as-is, so on-device
 *    novel support needs a JS engine choice (e.g. QuickJS-android) before a
 *    `LocalNovelBackend` can exist; see [ContentBackendCapabilities].
 *  - **Remote** backends ([dev.moku.mobile.remote.RemoteTsunaguBackend]) are a thin
 *    GraphQL client against a self-hosted Tsunagu server, which already runs all
 *    three extension kinds in its own JVM sandbox — so the remote backend supports
 *    all three content types from day one, regardless of what's implemented locally.
 *
 * IMPORTANT: local and remote are deliberately two separate persistence realms, never
 * merged into one library. A Tsunagu server already owns its own library/progress/
 * downloads/tracking state server-side; the on-device Room DB (`dev.moku.mobile.library`)
 * only ever stores rows for `local:`-prefixed [originId]s. There is no "unified library"
 * that blends a connected server's media with on-device media — [originId] exists so a
 * *local* library row can tell which local extension it came from (and a remote UI layer
 * can tell which server + extension it's showing), not so the two can be joined together.
 */
interface ContentBackend {

    /** Stable id for this backend instance, e.g. "local:manga:<packageName>" or "remote:<serverUrl>#<extensionId>". */
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

/**
 * What a given [ContentBackend] instance can actually do — surfaced in the UI so, e.g.,
 * a user isn't shown a "read offline" affordance for a remote-only novel source before
 * a local novel engine exists. A backend that doesn't support a content type at all
 * simply isn't offered for it (browse/extensions screen filters by [MediaContentType]).
 */
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

/** The resolved content for one [BackendUnit], shaped per content type. */
sealed interface BackendUnitContent {
    data class Pages(val pages: List<BackendPageImage>) : BackendUnitContent
    data class Video(
        val streamUrl: String,
        val headers: Map<String, String> = emptyMap(),
        val subtitleUrl: String? = null,
        /**
         * Every quality/source variant the extension returned (Aniyomi sources routinely
         * expose several), [streamUrl] being whichever one [dev.moku.mobile.backend.LocalAnimeBackend]
         * already picked as the default — kept alongside it (not discarded) so a future quality
         * picker isn't a second network round-trip, matching Tsunagu's own `serveVideo?quality=`
         * selector but resolved client-side instead of via a server query param.
         */
        val qualities: List<VideoVariant> = emptyList(),
    ) : BackendUnitContent
    data class Text(val html: String) : BackendUnitContent
}

data class VideoVariant(val label: String, val url: String, val headers: Map<String, String> = emptyMap())

data class BackendPageImage(
    val index: Int,
    /** For a local backend this is a direct source image URL. For remote it's the Tsunagu `/content/...` proxy URL. */
    val imageUrl: String,
    /** Headers (referer/UA) that must accompany the request — only meaningful for the local backend. */
    val headers: Map<String, String> = emptyMap(),
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
