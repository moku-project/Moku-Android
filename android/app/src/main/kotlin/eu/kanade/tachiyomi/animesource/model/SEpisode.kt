package eu.kanade.tachiyomi.animesource.model

@Suppress("unused")
interface SEpisode {

    var url: String
    var name: String
    var date_upload: Long
    var episode_number: Float
    var scanlator: String?
    var memo: kotlinx.serialization.json.JsonObject?

    companion object {
        fun create(): SEpisode = SEpisodeImpl()
    }
}
