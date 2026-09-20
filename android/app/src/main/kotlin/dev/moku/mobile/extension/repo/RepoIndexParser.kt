package dev.moku.mobile.extension.repo

/**
 * Parses a Keiyoushi-format `index.pb` repo index. Field layout mirrors Tsunagu's
 * `backend/internal/repository/protobuf.go` exactly (see [ProtoReader] for why there's
 * no generated-from-.proto parser here):
 *
 * repoIndex message: 1=name 2=badgeLabel 3=signingKey 4=contact 101=extensionList 102=extensionListUrl
 * extension message (repeated inside extensionList field 1): 1=name 2=packageName 3=resources
 *   4=extensionLib 5=versionCode(varint) 6=versionName 7=contentWarning(varint) 8=repeated source
 * resources message: 1=apkUrl 2=iconUrl 501=jarUrl
 * source message: 1=id(varint) 2=name 3=language 4=homeUrl 5=repeated mirrorUrl 7=message
 */
data class RepoExtension(
    val name: String,
    val packageName: String,
    val apkUrl: String,
    val jarUrl: String,
    val iconUrl: String,
    val versionName: String,
    val versionCode: Long,
    val isNsfw: Boolean,
    val lang: String,
)

object RepoIndexParser {

    fun parse(bytes: ByteArray): List<RepoExtension> {
        val reader = bytes.asReader()
        val end = bytes.size
        val extensions = mutableListOf<RepoExtension>()

        while (reader.hasRemaining(end)) {
            val (fieldNum, wireType) = reader.readTag()
            if (fieldNum == 101) {
                val extensionListBytes = reader.readLengthDelimited()
                extensions += parseExtensionList(extensionListBytes)
            } else {
                reader.skip(wireType)
            }
        }
        return extensions
    }

    private fun parseExtensionList(bytes: ByteArray): List<RepoExtension> {
        val reader = bytes.asReader()
        val end = bytes.size
        val extensions = mutableListOf<RepoExtension>()

        while (reader.hasRemaining(end)) {
            val (fieldNum, wireType) = reader.readTag()
            if (fieldNum == 1) {
                extensions += parseExtension(reader.readLengthDelimited())
            } else {
                reader.skip(wireType)
            }
        }
        return extensions
    }

    private fun parseExtension(bytes: ByteArray): RepoExtension {
        val reader = bytes.asReader()
        val end = bytes.size

        var name = ""
        var packageName = ""
        var apkUrl = ""
        var jarUrl = ""
        var iconUrl = ""
        var versionName = ""
        var versionCode = 0L
        var contentWarning = 0L
        val langs = linkedSetOf<String>()

        while (reader.hasRemaining(end)) {
            val (fieldNum, wireType) = reader.readTag()
            when (fieldNum) {
                1 -> name = String(reader.readLengthDelimited())
                2 -> packageName = String(reader.readLengthDelimited())
                3 -> {
                    val (apk, icon, jar) = parseResources(reader.readLengthDelimited())
                    apkUrl = apk; iconUrl = icon; jarUrl = jar
                }
                4 -> reader.readLengthDelimited() // extensionLib, unused on-device
                5 -> versionCode = reader.readVarint()
                6 -> versionName = String(reader.readLengthDelimited())
                7 -> contentWarning = reader.readVarint()
                8 -> langs += parseSourceLang(reader.readLengthDelimited())
                else -> reader.skip(wireType)
            }
        }

        return RepoExtension(
            name = name,
            packageName = packageName,
            apkUrl = apkUrl,
            jarUrl = jarUrl,
            iconUrl = iconUrl,
            versionName = versionName,
            versionCode = versionCode,
            isNsfw = contentWarning > 0,
            lang = if (langs.size == 1) langs.first() else "all",
        )
    }

    private fun parseResources(bytes: ByteArray): Triple<String, String, String> {
        val reader = bytes.asReader()
        val end = bytes.size
        var apkUrl = ""
        var iconUrl = ""
        var jarUrl = ""

        while (reader.hasRemaining(end)) {
            val (fieldNum, wireType) = reader.readTag()
            when (fieldNum) {
                1 -> apkUrl = String(reader.readLengthDelimited())
                2 -> iconUrl = String(reader.readLengthDelimited())
                501 -> jarUrl = String(reader.readLengthDelimited())
                else -> reader.skip(wireType)
            }
        }
        return Triple(apkUrl, iconUrl, jarUrl)
    }

    private fun parseSourceLang(bytes: ByteArray): String {
        val reader = bytes.asReader()
        val end = bytes.size
        var lang = ""

        while (reader.hasRemaining(end)) {
            val (fieldNum, wireType) = reader.readTag()
            if (fieldNum == 3) {
                lang = String(reader.readLengthDelimited())
            } else {
                reader.skip(wireType)
            }
        }
        return lang.lowercase().substringBefore(",").trim()
    }
}
