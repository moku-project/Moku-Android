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
import dev.moku.mobile.sync.ServerOrigin
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** One extension on a Tsunagu server, which runs it; this side only speaks GraphQL. */
class RemoteTsunaguBackend(
    private val client: TsunaguClient,
    private val instanceId: String,
    private val extensionId: String,
    override val displayName: String,
    override val contentType: MediaContentType,
) : ContentBackend {

    override val originId: String = ServerOrigin.of(instanceId, contentType, extensionId)

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
        suspend fun resolve(sync: Boolean) = client.query(
            """query ResolveMedia(${'$'}extensionId: ID!, ${'$'}externalId: String!, ${'$'}sync: Boolean!) {
                resolveMedia(extensionId: ${'$'}extensionId, externalId: ${'$'}externalId, syncChapters: ${'$'}sync) {
                    id
                    chapters { id mediaId externalId title number scanlator sourceOrder uploadedAt }
                }
            }""",
            buildJsonObject { put("extensionId", extensionId); put("externalId", mediaId); put("sync", sync) },
        )
        // When the source is down the server still has the chapters it last saw, and its downloads still open.
        val data = try {
            resolve(sync = true)
        } catch (e: GraphQLException) {
            resolve(sync = false)
        }
        val media = data["resolveMedia"]!!.jsonObject
        val resolvedMediaId = media.str("id")
        return media["chapters"]?.jsonArray.orEmpty().map { it.jsonObject.toBackendUnit(resolvedMediaId) }
    }

    override suspend fun unitContent(mediaId: String, unitId: String): BackendUnitContent {
        val server = ServerSession.current ?: throw AuthRequiredException()
        // `pages` asks the server's source for the page list (or its download), the same field Moku reads.
        val wantPages = contentType == MediaContentType.MANGA
        val chapter = client.query(
            "query Chapter(\$id: ID!) { chapter(id: \$id) { mediaId${if (wantPages) " pages" else ""} } }",
            buildJsonObject { put("id", unitId) },
        )["chapter"]?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.jsonObject
            ?: throw java.io.IOException("The server no longer has this chapter")
        val contentBase = "${server.baseUrl}/content/${chapter.str("mediaId")}/$unitId"

        return when (contentType) {
            MediaContentType.ANIME -> BackendUnitContent.Video(
                streamUrl = "$contentBase/video",
                headers = server.token?.let { mapOf("Authorization" to "Bearer $it") }.orEmpty(),
            )
            MediaContentType.NOVEL -> BackendUnitContent.Text(html = client.rawGetText("$contentBase/text"))
            MediaContentType.MANGA -> BackendUnitContent.Pages(
                chapter["pages"]?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.jsonArray.orEmpty()
                    .mapIndexed { index, url -> BackendPageImage(index = index, imageUrl = ServerSession.absolute(url.jsonPrimitive.content)!!) },
            )
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
            thumbnailUrl = ServerSession.absolute(strOrNull("thumbnailUrl")),
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
        uploadedAt = strOrNull("uploadedAt")?.let { runCatching { java.time.OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() },
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
