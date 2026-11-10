package eu.kanade.tachiyomi

import dev.moku.mobile.BuildConfig

/** Host app details extensions can read (extensions-lib 1.3 and 1.5). */
@Suppress("UNUSED")
object AppInfo {
    fun getVersionCode(): Int = BuildConfig.VERSION_CODE

    fun getVersionName(): String = BuildConfig.VERSION_NAME

    fun getSupportedImageMimeTypes(): List<String> =
        listOf("image/jpeg", "image/png", "image/webp", "image/gif", "image/avif", "image/heif", "image/jxl")
}
