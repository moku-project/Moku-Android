package dev.moku.mobile.extension

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import dalvik.system.DexClassLoader
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.AnimeSourceFactory
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory
import java.io.File
import java.security.MessageDigest

private const val MANGA_EXTENSION_FEATURE = "tachiyomi.extension"
private const val METADATA_MANGA_SOURCE_CLASS = "tachiyomi.extension.class"
private const val ANIME_EXTENSION_FEATURE = "tachiyomi.animeextension"
private const val METADATA_ANIME_SOURCE_CLASS = "tachiyomi.animeextension.class"

class ExtensionLoadException(message: String) : Exception(message)

data class LoadedMangaExtension(
    val packageName: String,
    val versionName: String,
    val signingFingerprint: String,
    val sources: List<Source>,
)

data class LoadedAnimeExtension(
    val packageName: String,
    val versionName: String,
    val signingFingerprint: String,
    val sources: List<AnimeSource>,
)

/**
 * Loads a Keiyoushi manga extension APK sitting in app-private storage — never installed
 * via PackageManager. Used by [dev.moku.mobile.backend.LocalMangaBackend] and by extension
 * install/management flows.
 *
 * Anime (Aniyomi `AnimeSource`/`AnimeSourceFactory`) extensions use the identical
 * mechanism against a second vendored runtime that doesn't exist yet in this project —
 * see the kdoc on [dev.moku.mobile.backend.ContentBackend] for why that's a "redo this,"
 * not "solve a new problem," follow-up.
 */
object ExtensionLoader {

    fun loadManga(context: Context, apkFile: File): LoadedMangaExtension {
        val pkgManager = context.packageManager
        val flags = PackageManager.GET_META_DATA or PackageManager.GET_CONFIGURATIONS or
            (if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES)
        val pkgInfo = pkgManager.getPackageArchiveInfo(apkFile.absolutePath, flags)
            ?: throw ExtensionLoadException("getPackageArchiveInfo returned null for ${apkFile.name} — not a parseable APK")

        val appInfo = pkgInfo.applicationInfo
            ?: throw ExtensionLoadException("No applicationInfo in parsed manifest for ${apkFile.name}")
        // getPackageArchiveInfo doesn't set these from the real file path, so point them at
        // our copy — DexClassLoader below needs sourceDir to find the code.
        appInfo.sourceDir = apkFile.absolutePath
        appInfo.publicSourceDir = apkFile.absolutePath

        val features = pkgInfo.reqFeatures.orEmpty().mapNotNull { it.name }
        if (features.none { it == MANGA_EXTENSION_FEATURE }) {
            throw ExtensionLoadException("${pkgInfo.packageName}: missing uses-feature '$MANGA_EXTENSION_FEATURE' — not a manga extension APK")
        }

        val entryClassNames = appInfo.metaData?.getString(METADATA_MANGA_SOURCE_CLASS)
            ?: throw ExtensionLoadException("${pkgInfo.packageName}: no $METADATA_MANGA_SOURCE_CLASS meta-data found")

        val classLoader = DexClassLoader(
            apkFile.absolutePath,
            context.codeCacheDir.absolutePath,
            null,
            context.classLoader,
        )

        val sources = entryClassNames.split(";").flatMap { rawName ->
            val className = if (rawName.startsWith(".")) pkgInfo.packageName + rawName else rawName
            val instance = Class.forName(className, false, classLoader)
                .getDeclaredConstructor()
                .newInstance()
            when (instance) {
                is SourceFactory -> instance.createSources()
                is Source -> listOf(instance)
                else -> emptyList()
            }
        }

        if (sources.isEmpty()) {
            throw ExtensionLoadException("${pkgInfo.packageName}: entry point instantiated but produced no Source instances")
        }

        return LoadedMangaExtension(
            packageName = pkgInfo.packageName,
            versionName = pkgInfo.versionName ?: "unknown",
            signingFingerprint = signingFingerprint(pkgInfo),
            sources = sources,
        )
    }

    fun loadAnime(context: Context, apkFile: File): LoadedAnimeExtension {
        val pkgManager = context.packageManager
        val flags = PackageManager.GET_META_DATA or PackageManager.GET_CONFIGURATIONS or
            (if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES)
        val pkgInfo = pkgManager.getPackageArchiveInfo(apkFile.absolutePath, flags)
            ?: throw ExtensionLoadException("getPackageArchiveInfo returned null for ${apkFile.name} — not a parseable APK")

        val appInfo = pkgInfo.applicationInfo
            ?: throw ExtensionLoadException("No applicationInfo in parsed manifest for ${apkFile.name}")
        appInfo.sourceDir = apkFile.absolutePath
        appInfo.publicSourceDir = apkFile.absolutePath

        val features = pkgInfo.reqFeatures.orEmpty().mapNotNull { it.name }
        if (features.none { it == ANIME_EXTENSION_FEATURE }) {
            throw ExtensionLoadException("${pkgInfo.packageName}: missing uses-feature '$ANIME_EXTENSION_FEATURE' — not an anime extension APK")
        }

        val entryClassNames = appInfo.metaData?.getString(METADATA_ANIME_SOURCE_CLASS)
            ?: throw ExtensionLoadException("${pkgInfo.packageName}: no $METADATA_ANIME_SOURCE_CLASS meta-data found")

        val classLoader = DexClassLoader(
            apkFile.absolutePath,
            context.codeCacheDir.absolutePath,
            null,
            context.classLoader,
        )

        val sources = entryClassNames.split(";").flatMap { rawName ->
            val className = if (rawName.startsWith(".")) pkgInfo.packageName + rawName else rawName
            val instance = Class.forName(className, false, classLoader)
                .getDeclaredConstructor()
                .newInstance()
            when (instance) {
                is AnimeSourceFactory -> instance.createSources()
                is AnimeSource -> listOf(instance)
                else -> emptyList()
            }
        }

        if (sources.isEmpty()) {
            throw ExtensionLoadException("${pkgInfo.packageName}: entry point instantiated but produced no AnimeSource instances")
        }

        return LoadedAnimeExtension(
            packageName = pkgInfo.packageName,
            versionName = pkgInfo.versionName ?: "unknown",
            signingFingerprint = signingFingerprint(pkgInfo),
            sources = sources,
        )
    }

    private fun signingFingerprint(pkgInfo: PackageInfo): String {
        val signatures = if (Build.VERSION.SDK_INT >= 28) {
            pkgInfo.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            pkgInfo.signatures
        } ?: return "none"

        val digest = MessageDigest.getInstance("SHA-256")
        return signatures.joinToString { sig ->
            digest.digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }
        }
    }
}
