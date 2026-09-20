package dev.moku.mobile.remote

import dev.moku.mobile.backend.BackendFilter
import dev.moku.mobile.backend.BackendMedia
import dev.moku.mobile.backend.BackendPage
import dev.moku.mobile.backend.BackendPageImage
import dev.moku.mobile.backend.BackendUnit
import dev.moku.mobile.backend.BackendUnitContent
import dev.moku.mobile.backend.ContentBackend
import dev.moku.mobile.backend.ContentBackendCapabilities
import dev.moku.mobile.backend.MediaContentType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * [ContentBackend] over a self-hosted Tsunagu instance's existing GraphQL API — the same
 * `search`/`popularManga`/`latestUpdates`/`resolveMedia` queries Moku's
 * `server-adapters/tsunagu/discovery.ts` already issues. Tsunagu is doing the actual
 * extension execution (manga/anime in its JVM sandbox, novels via its GraalVM JS
 * bridge); this class only ever talks HTTP+GraphQL, so it supports all three content
 * types identically regardless of what's implemented as a local on-device backend —
 * see [dev.moku.mobile.backend.ContentBackend]'s kdoc for why local/remote maturity differs.
 *
 * One instance wraps one remote `extensionId`; [contentType] comes from the extension's
 * own metadata (Tsunagu's `Extension.contentType`) rather than being assumed.
 */
class RemoteTsunaguBackend(
    private val client: TsunaguClient,
    private val server: TsunaguServer,
    private val extensionId: String,
    override val displayName: String,
    override val contentType: MediaContentType,
) : ContentBackend {

    override val originId: String = "remote:${server.baseUrl}#$extensionId"

    val capabilities = ContentBackendCapabilities(supportsOffline = false)

    override suspend fun popularMedia(page: Int): BackendPage<BackendMedia> {
        val data = client.query(
            """query PopularManga(${'$'}extensionId: ID!, ${'$'}page: Int) {
                popularManga(extensionId: ${'$'}extensionId, page: ${'$'}page) {
                    results { id externalId title thumbnailUrl inLibrary status genres }
                    hasNextPage
                }
            }""",
            buildJsonObject { put("extensionId", extensionId); put("page", page) },
        )
        return parseSearchResponse(data["popularManga"]!!.jsonObject)
    }

    override suspend fun latestUpdates(page: Int): BackendPage<BackendMedia> {
        val data = client.query(
            """query LatestUpdates(${'$'}extensionId: ID!, ${'$'}page: Int) {
                latestUpdates(extensionId: ${'$'}extensionId, page: ${'$'}page) {
                    results { id externalId title thumbnailUrl inLibrary status genres }
                    hasNextPage
                }
            }""",
            buildJsonObject { put("extensionId", extensionId); put("page", page) },
        )
        return parseSearchResponse(data["latestUpdates"]!!.jsonObject)
    }

    override suspend fun search(query: String, page: Int, filters: List<BackendFilter>): BackendPage<BackendMedia> {
        val data = client.query(
            """query Search(${'$'}extensionId: ID!, ${'$'}query: String!, ${'$'}page: Int, ${'$'}filters: [FilterInput!]) {
                search(extensionId: ${'$'}extensionId, query: ${'$'}query, page: ${'$'}page, filters: ${'$'}filters) {
                    results { id externalId title thumbnailUrl inLibrary status genres }
                    hasNextPage
                }
            }""",
            buildJsonObject {
                put("extensionId", extensionId)
                put("query", query)
                put("page", page)
                put("filters", filters.toFilterInput())
            },
        )
        return parseSearchResponse(data["search"]!!.jsonObject)
    }

    override suspend fun filterOptions(): List<BackendFilter> {
        val data = client.query(
            """${FILTER_NODE_FRAGMENT}
            query FilterOptions(${'$'}extensionId: ID!) {
                filterOptions(extensionId: ${'$'}extensionId) { ...FilterNodeFields }
            }""",
            buildJsonObject { put("extensionId", extensionId) },
        )
        return data["filterOptions"]!!.jsonArray.map { it.jsonObject.toBackendFilter() }
    }

    override suspend fun mediaDetails(mediaId: String): BackendMedia {
        val data = client.query(
            """query ResolveMedia(${'$'}extensionId: ID!, ${'$'}externalId: String!) {
                resolveMedia(extensionId: ${'$'}extensionId, externalId: ${'$'}externalId, syncChapters: false) {
                    id extensionId externalId title description thumbnailUrl status author artist genres inLibrary
                }
            }""",
            buildJsonObject { put("extensionId", extensionId); put("externalId", mediaId) },
        )
        return data["resolveMedia"]!!.jsonObject.toBackendMedia()
    }

    /** For anime this returns episodes; Tsunagu's `chapters` field is shared across content types (a chapter/episode both have number/title/uploadedAt). */
    override suspend fun unitList(mediaId: String): List<BackendUnit> {
        val data = client.query(
            """query ResolveMedia(${'$'}extensionId: ID!, ${'$'}externalId: String!) {
                resolveMedia(extensionId: ${'$'}extensionId, externalId: ${'$'}externalId, syncChapters: true) {
                    id
                    chapters { id mediaId externalId title number scanlator sourceOrder uploadedAt }
                }
            }""",
            buildJsonObject { put("extensionId", extensionId); put("externalId", mediaId) },
        )
        val media = data["resolveMedia"]!!.jsonObject
        val resolvedMediaId = media.str("id")
        return media["chapters"]?.jsonArray.orEmpty().map { it.jsonObject.toBackendUnit(resolvedMediaId) }
    }

    override suspend fun unitContent(mediaId: String, unitId: String): BackendUnitContent {
        // Route shape confirmed against ContentHandler.ServeHTTP in
        // backend/internal/api/rest/content.go: /content/{mediaId}/{chapterId}/(pages/{n 1-indexed}|video|text).
        // {mediaId} there is Tsunagu's resolved DB id, NOT the extension's externalId a search result
        // carries before it's ever been resolved — so resolve first, same as unitList does.
        val data = client.query(
            """query ResolveMediaForContent(${'$'}extensionId: ID!, ${'$'}externalId: String!) {
                resolveMedia(extensionId: ${'$'}extensionId, externalId: ${'$'}externalId, syncChapters: true) {
                    id
                    chapters { id pageCount }
                }
            }""",
            buildJsonObject { put("extensionId", extensionId); put("externalId", mediaId) },
        )
        val resolved = data["resolveMedia"]!!.jsonObject
        val resolvedMediaId = resolved.str("id")
        val base = server.baseUrl.trimEnd('/')
        val contentBase = "$base/content/$resolvedMediaId/$unitId"

        return when (contentType) {
            MediaContentType.ANIME -> {
                // Tsunagu resolves/transcodes video server-side (serveVideo/serveHLS/serveDASH) —
                // the client just needs the URL, auth is via the same Bearer token as GraphQL.
                BackendUnitContent.Video(
                    streamUrl = "$contentBase/video",
                    headers = server.token?.let { mapOf("Authorization" to "Bearer $it") }.orEmpty(),
                )
            }
            MediaContentType.NOVEL -> {
                // serveText returns the full chapter body for one unit — no pagination server-side.
                BackendUnitContent.Text(html = client.rawGetText("$contentBase/text"))
            }
            MediaContentType.MANGA -> {
                val pageCount = resolved["chapters"]!!.jsonArray
                    .map { it.jsonObject }
                    .firstOrNull { it.str("id") == unitId }
                    ?.get("pageCount")?.jsonPrimitive?.int ?: 0

                BackendUnitContent.Pages(
                    // Pages are served 1-indexed (/pages/{pageNumber}) but BackendPageImage.index stays
                    // 0-based to match the local backend's convention — the URL is adjusted here, not there.
                    (0 until pageCount).map { index -> BackendPageImage(index = index, imageUrl = "$contentBase/pages/${index + 1}") },
                )
            }
        }
    }

    private fun parseSearchResponse(obj: JsonObject): BackendPage<BackendMedia> {
        val results = obj["results"]!!.jsonArray.map { it.jsonObject.toBackendMedia() }
        val hasNextPage = obj["hasNextPage"]?.jsonPrimitive?.contentOrNull == "true"
        return BackendPage(results, hasNextPage)
    }

    private fun JsonObject.toBackendMedia(): BackendMedia {
        val mediaId = strOrNull("externalId") ?: str("id")
        return BackendMedia(
            id = mediaId,
            originId = originId,
            contentType = contentType,
            title = str("title"),
            thumbnailUrl = strOrNull("thumbnailUrl"),
            description = strOrNull("description"),
            status = strOrNull("status"),
            authors = strOrNull("author")?.split(",")?.map { it.trim() }.orEmpty(),
            artists = strOrNull("artist")?.split(",")?.map { it.trim() }.orEmpty(),
            genres = this["genres"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty(),
            inLibrary = this["inLibrary"]?.jsonPrimitive?.contentOrNull == "true",
        )
    }

    private fun JsonObject.toBackendUnit(mediaId: String): BackendUnit = BackendUnit(
        id = str("id"),
        mediaId = mediaId,
        contentType = contentType,
        title = strOrNull("title"),
        number = this["number"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull(),
        scanlator = strOrNull("scanlator"),
    )

    private fun JsonObject.toBackendFilter(): BackendFilter {
        return when (val type = str("__typename")) {
            "HeaderFilter" -> BackendFilter.Header(str("name"))
            "SeparatorFilter" -> BackendFilter.Separator(str("name"))
            "SelectFilter" -> BackendFilter.Select(str("name"), stringList("values"), this["state"]!!.jsonPrimitive.int)
            "TextFilter" -> BackendFilter.Text(str("name"), str("state"))
            "CheckBoxFilter" -> BackendFilter.CheckBox(str("name"), this["state"]!!.jsonPrimitive.contentOrNull == "true")
            "TriStateFilter" -> BackendFilter.TriState(str("name"), this["state"]!!.jsonPrimitive.int)
            "SortFilter" -> BackendFilter.Sort(
                str("name"),
                stringList("values"),
                this["index"]?.jsonPrimitive?.contentOrNull?.toIntOrNull(),
                this["ascending"]?.jsonPrimitive?.contentOrNull?.toBoolean(),
            )
            "GroupFilter" -> BackendFilter.Group(
                str("name"),
                this["children"]!!.jsonArray.map { it.jsonObject.toBackendFilter() },
            )
            else -> error("Unknown FilterNode type $type")
        }
    }

    private fun JsonObject.stringList(key: String): List<String> =
        this[key]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()

    private fun List<BackendFilter>.toFilterInput(): JsonArray = kotlinx.serialization.json.buildJsonArray {
        this@toFilterInput.forEach { filter ->
            add(
                buildJsonObject {
                    put("name", filter.name)
                    when (filter) {
                        is BackendFilter.Select -> put("select", buildJsonObject { put("state", filter.state) })
                        is BackendFilter.Text -> put("text", buildJsonObject { put("state", filter.state) })
                        is BackendFilter.CheckBox -> put("checkbox", buildJsonObject { put("state", filter.state) })
                        is BackendFilter.TriState -> put("tristate", buildJsonObject { put("state", filter.state) })
                        is BackendFilter.Sort -> put(
                            "sort",
                            buildJsonObject {
                                put("hasState", filter.index != null)
                                filter.index?.let { put("index", it) }
                                filter.ascending?.let { put("ascending", it) }
                            },
                        )
                        is BackendFilter.Group -> put(
                            "group",
                            buildJsonObject { put("children", filter.children.toFilterInput()) },
                        )
                        else -> {}
                    }
                },
            )
        }
    }

    private companion object {
        const val FILTER_NODE_FRAGMENT = """
            fragment FilterNodeFields on FilterNode {
                __typename
                ... on HeaderFilter    { name }
                ... on SeparatorFilter { name }
                ... on SelectFilter    { name values state }
                ... on TextFilter      { name state }
                ... on CheckBoxFilter  { name state }
                ... on TriStateFilter  { name state }
                ... on SortFilter      { name values hasState index ascending }
                ... on GroupFilter { name children { ...FilterNodeFields } }
            }
        """
    }
}
